/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flume.lifecycle;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.flume.FlumeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class LifecycleSupervisor implements LifecycleAware {

    private static final Logger logger = LoggerFactory
            .getLogger(LifecycleSupervisor.class);

    private Map<LifecycleAware, Supervisoree> supervisedProcesses;
    private Map<LifecycleAware, ScheduledFuture<?>> monitorFutures;

    private ScheduledThreadPoolExecutor monitorService;

    private LifecycleState lifecycleState;
    private Purger purger;
    private boolean needToPurge;

    public LifecycleSupervisor() {
        lifecycleState = LifecycleState.IDLE;
        supervisedProcesses = new HashMap<LifecycleAware, Supervisoree>();  //存储所有历史上的组件及其监控信息
        monitorFutures = new HashMap<LifecycleAware, ScheduledFuture<?>>();
        monitorService = new ScheduledThreadPoolExecutor(10,
                new ThreadFactoryBuilder().setNameFormat(
                        "lifecycleSupervisor-" + Thread.currentThread().getId() + "-%d")
                        .build());
        monitorService.setMaximumPoolSize(20);
        monitorService.setKeepAliveTime(30, TimeUnit.SECONDS);
        purger = new Purger();
        needToPurge = false;
    }

    @Override
    public synchronized void start() {

        logger.info("Starting lifecycle supervisor {}", Thread.currentThread()
                .getId());
        monitorService.scheduleWithFixedDelay(purger, 2, 2, TimeUnit.HOURS);
        lifecycleState = LifecycleState.START;

        logger.debug("Lifecycle supervisor started");
    }

    @Override
    public synchronized void stop() {

        logger.info("Stopping lifecycle supervisor {}", Thread.currentThread()
                .getId());

        if (monitorService != null) {
            monitorService.shutdown();
            try {
                monitorService.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting for monitor service to stop");
            }
            if (!monitorService.isTerminated()) {
                monitorService.shutdownNow();
                try {
                    while (!monitorService.isTerminated()) {
                        monitorService.awaitTermination(10, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    logger.error("Interrupted while waiting for monitor service to stop");
                }
            }
        }

        for (final Entry<LifecycleAware, Supervisoree> entry : supervisedProcesses
                .entrySet()) {

            if (entry.getKey().getLifecycleState().equals(LifecycleState.START)) {
                entry.getValue().status.desiredState = LifecycleState.STOP;
                entry.getKey().stop();
            }
        }

    /* If we've failed, preserve the error state. */
        if (lifecycleState.equals(LifecycleState.START)) {
            lifecycleState = LifecycleState.STOP;
        }
        supervisedProcesses.clear();
        monitorFutures.clear();
        logger.debug("Lifecycle supervisor stopped");
    }

    public synchronized void fail() {
        lifecycleState = LifecycleState.ERROR;
    }

    public synchronized void supervise(LifecycleAware lifecycleAware,
                                       SupervisorPolicy policy, LifecycleState desiredState) {
        // 检查线程池状态
        if (this.monitorService.isShutdown()
                || this.monitorService.isTerminated()
                || this.monitorService.isTerminating()) {
            throw new FlumeException("Supervise called on " + lifecycleAware + " " +
                    "after shutdown has been initiated. " + lifecycleAware + " will not" +
                    " be started");
        }

        // 如果该组件已经在监控，则拒绝二次监控
        Preconditions.checkState(!supervisedProcesses.containsKey(lifecycleAware),
                "Refusing to supervise " + lifecycleAware + " more than once");

        if (logger.isDebugEnabled()) {
            logger.debug("Supervising service:{} policy:{} desiredState:{}",
                    new Object[]{lifecycleAware, policy, desiredState});
        }

        Supervisoree process = new Supervisoree();
        process.status = new Status();

        process.policy = policy;
        process.status.desiredState = desiredState;
        process.status.error = false;

        MonitorRunnable monitorRunnable = new MonitorRunnable();
        monitorRunnable.lifecycleAware = lifecycleAware;
        monitorRunnable.supervisoree = process;
        monitorRunnable.monitorService = monitorService;

        supervisedProcesses.put(lifecycleAware, process);
        // 创建并执行一个在给定初始延迟后首次启用的定期操作，随后，在每一次执行终止和下一次执行开始之间都存在给定的延迟。
        // 如果任务的任一执行遇到异常，就会取消后续执行
        // 启动MonitorRunnable,结束之后3秒再重新启动，可以用于重试
        ScheduledFuture<?> future = monitorService.scheduleWithFixedDelay(
                monitorRunnable, 0, 3, TimeUnit.SECONDS);
        monitorFutures.put(lifecycleAware, future);
    }

    public synchronized void unsupervise(LifecycleAware lifecycleAware) {

        // 检查正在运行的组件当中是否有此组件
        Preconditions.checkState(supervisedProcesses.containsKey(lifecycleAware),
                "Unaware of " + lifecycleAware + " - can not unsupervise");

        logger.debug("Unsupervising service:{}", lifecycleAware);

        synchronized (lifecycleAware) {
            Supervisoree supervisoree = supervisedProcesses.get(lifecycleAware);
            supervisoree.status.discard = true;     // 标记此组件为已取消监控
            this.setDesiredState(lifecycleAware, LifecycleState.STOP);
            logger.info("Stopping component: {}", lifecycleAware);
            lifecycleAware.stop();
        }
        supervisedProcesses.remove(lifecycleAware);
        //We need to do this because a reconfiguration simply unsupervises old
        //components and supervises new ones.
        monitorFutures.get(lifecycleAware).cancel(false);
        //purges are expensive, so it is done only once every 2 hours.
        needToPurge = true;
        monitorFutures.remove(lifecycleAware);
    }

    public synchronized void setDesiredState(LifecycleAware lifecycleAware,
                                             LifecycleState desiredState) {

        Preconditions.checkState(supervisedProcesses.containsKey(lifecycleAware),
                "Unaware of " + lifecycleAware + " - can not set desired state to "
                        + desiredState);

        logger.debug("Setting desiredState:{} on service:{}", desiredState,
                lifecycleAware);

        Supervisoree supervisoree = supervisedProcesses.get(lifecycleAware);
        supervisoree.status.desiredState = desiredState;
    }

    @Override
    public synchronized LifecycleState getLifecycleState() {
        return lifecycleState;
    }

    public synchronized boolean isComponentInErrorState(LifecycleAware component) {
        return supervisedProcesses.get(component).status.error;

    }

    public static class MonitorRunnable implements Runnable {

        public ScheduledExecutorService monitorService;
        public LifecycleAware lifecycleAware;
        public Supervisoree supervisoree;

        @Override
        public void run() {
            logger.debug("checking process:{} supervisoree:{}", lifecycleAware,
                    supervisoree);

            long now = System.currentTimeMillis();

            try {
                if (supervisoree.status.firstSeen == null) {
                    logger.debug("first time seeing {}", lifecycleAware);

                    supervisoree.status.firstSeen = now;
                }

                supervisoree.status.lastSeen = now;
                synchronized (lifecycleAware) {
                    if (supervisoree.status.discard) {
                        // 该组件已经停止监控
                        // Unsupervise has already been called on this.
                        logger.info("Component has already been stopped {}", lifecycleAware);
                        return;
                    } else if (supervisoree.status.error) {
                        logger.info("Component {} is in error state, and Flume will not"
                                + "attempt to change its state", lifecycleAware);
                        return;
                    }

                    // 获取组件最新状态,没运行start()方法之前是LifecycleState.IDLE状态
                    supervisoree.status.lastSeenState = lifecycleAware.getLifecycleState();

                    if (!lifecycleAware.getLifecycleState().equals(
                            supervisoree.status.desiredState)) {
                        // 该组件最新状态和期望的状态不一致
                        logger.debug("Want to transition {} from {} to {} (failures:{})",
                                new Object[]{lifecycleAware, supervisoree.status.lastSeenState,
                                        supervisoree.status.desiredState,
                                        supervisoree.status.failures});

                        // 根据状态执行相应的操作
                        switch (supervisoree.status.desiredState) {
                            case START:
                                try {
                                    lifecycleAware.start();     // 启动组件，同时其状态也会变为 LifecycleState.START
                                } catch (Throwable e) {
                                    logger.error("Unable to start " + lifecycleAware
                                            + " - Exception follows.", e);
                                    if (e instanceof Error) {
                                        // This component can never recover, shut it down.
                                        supervisoree.status.desiredState = LifecycleState.STOP;
                                        try {
                                            lifecycleAware.stop();
                                            logger.warn("Component {} stopped, since it could not be"
                                                            + "successfully started due to missing dependencies",
                                                    lifecycleAware);
                                        } catch (Throwable e1) {
                                            logger.error("Unsuccessful attempt to "
                                                    + "shutdown component: {} due to missing dependencies."
                                                    + " Please shutdown the agent"
                                                    + "or disable this component, or the agent will be"
                                                    + "in an undefined state.", e1);
                                            supervisoree.status.error = true;
                                            if (e1 instanceof Error) {
                                                throw (Error) e1;
                                            }
                                            // Set the state to stop, so that the conf poller can
                                            // proceed.
                                        }
                                    }
                                    supervisoree.status.failures++;
                                }
                                break;
                            case STOP:
                                try {
                                    lifecycleAware.stop();
                                } catch (Throwable e) {
                                    logger.error("Unable to stop " + lifecycleAware
                                            + " - Exception follows.", e);
                                    if (e instanceof Error) {
                                        throw (Error) e;
                                    }
                                    supervisoree.status.failures++;
                                }
                                break;
                            default:
                                logger.warn("I refuse to acknowledge {} as a desired state",
                                        supervisoree.status.desiredState);
                        }

                        // 两种 SupervisorPolicy，(AlwaysRestartPolicy 和 OnceOnlyPolicy)
                        // 后者还未使用过，前者表示可以重新启动的组件，后者表示只能运行一次的组件
                        if (!supervisoree.policy.isValid(lifecycleAware, supervisoree.status)) {
                            logger.error(
                                    "Policy {} of {} has been violated - supervisor should exit!",
                                    supervisoree.policy, lifecycleAware);
                        }
                    }
                }
            } catch (Throwable t) {
                logger.error("Unexpected error", t);
            }
            logger.debug("Status check complete");
        }
    }

    private class Purger implements Runnable {

        @Override
        public void run() {
            if (needToPurge) {
                monitorService.purge();
                needToPurge = false;
            }
        }
    }

    public static class Status {
        public Long firstSeen;
        public Long lastSeen;
        public LifecycleState lastSeenState;
        public LifecycleState desiredState;
        public int failures;
        public boolean discard;
        public volatile boolean error;

        @Override
        public String toString() {
            return "{ lastSeen:" + lastSeen + " lastSeenState:" + lastSeenState
                    + " desiredState:" + desiredState + " firstSeen:" + firstSeen
                    + " failures:" + failures + " discard:" + discard + " error:" +
                    error + " }";
        }

    }

    public static abstract class SupervisorPolicy {

        abstract boolean isValid(LifecycleAware object, Status status);

        public static class AlwaysRestartPolicy extends SupervisorPolicy {

            @Override
            boolean isValid(LifecycleAware object, Status status) {
                return true;
            }
        }

        public static class OnceOnlyPolicy extends SupervisorPolicy {

            @Override
            boolean isValid(LifecycleAware object, Status status) {
                return status.failures == 0;
            }
        }

    }

    private static class Supervisoree {

        public SupervisorPolicy policy;
        public Status status;

        @Override
        public String toString() {
            return "{ status:" + status + " policy:" + policy + " }";
        }

    }


}
