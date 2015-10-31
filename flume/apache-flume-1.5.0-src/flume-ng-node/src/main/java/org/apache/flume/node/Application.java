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

package org.apache.flume.node;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.apache.commons.cli.*;
import org.apache.flume.*;
import org.apache.flume.instrumentation.MonitorService;
import org.apache.flume.instrumentation.MonitoringType;
import org.apache.flume.lifecycle.LifecycleAware;
import org.apache.flume.lifecycle.LifecycleState;
import org.apache.flume.lifecycle.LifecycleSupervisor;
import org.apache.flume.lifecycle.LifecycleSupervisor.SupervisorPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

public class Application {

    private static final Logger logger = LoggerFactory
            .getLogger(Application.class);

    public static final String CONF_MONITOR_CLASS = "flume.monitoring.type";
    public static final String CONF_MONITOR_PREFIX = "flume.monitoring.";

    private final List<LifecycleAware> components;
    private final LifecycleSupervisor supervisor;
    private MaterializedConfiguration materializedConfiguration;
    private MonitorService monitorServer;

    public Application() {
        this(new ArrayList<LifecycleAware>(0));
    }

    public Application(List<LifecycleAware> components) {
        this.components = components;
        // new LifecycleSupervisor() 启动10个线程用来执行配置文件中的各个组件，并监控组件的整个运行过程
        supervisor = new LifecycleSupervisor();
    }

    public synchronized void start() {
        for (LifecycleAware component : components) {
            supervisor.supervise(component,
                    new SupervisorPolicy.AlwaysRestartPolicy(), LifecycleState.START);
        }
    }


    // 订阅方法，当 eventBus.post(MaterializedConfiguration conf)执行时，会触发执行此方法
    @Subscribe
    public synchronized void handleConfigurationEvent(MaterializedConfiguration conf) {
        // 依次stop各个组件的运行，顺序是：source、sink、channel。之所以有顺序是因为：
        // 一、source是不停的读数据放入channel的；
        // 二、sink是不停的从channel拿数据的，channel两头都在使用应该最后停止，停止向channel发送数据后sink停止才不会丢数据。
        // stop是通过supervisor.unsupervise方法来完成的。
        stopAllComponents();
        // 启动各个组件的，顺序正好和stopAllComponents()停止顺序相反
        startAllComponents(conf);
    }

    public synchronized void stop() {
        supervisor.stop();
        if (monitorServer != null) {
            monitorServer.stop();
        }
    }


    private void stopAllComponents() {
        if (this.materializedConfiguration != null) {
            logger.info("Shutting down configuration: {}", this.materializedConfiguration);
            for (Entry<String, SourceRunner> entry : this.materializedConfiguration
                    .getSourceRunners().entrySet()) {
                try {
                    logger.info("Stopping Source " + entry.getKey());
                    supervisor.unsupervise(entry.getValue());
                } catch (Exception e) {
                    logger.error("Error while stopping {}", entry.getValue(), e);
                }
            }

            for (Entry<String, SinkRunner> entry :
                    this.materializedConfiguration.getSinkRunners().entrySet()) {
                try {
                    logger.info("Stopping Sink " + entry.getKey());
                    supervisor.unsupervise(entry.getValue());
                } catch (Exception e) {
                    logger.error("Error while stopping {}", entry.getValue(), e);
                }
            }

            for (Entry<String, Channel> entry :
                    this.materializedConfiguration.getChannels().entrySet()) {
                try {
                    logger.info("Stopping Channel " + entry.getKey());
                    supervisor.unsupervise(entry.getValue());
                } catch (Exception e) {
                    logger.error("Error while stopping {}", entry.getValue(), e);
                }
            }
        }
        if (monitorServer != null) {
            monitorServer.stop();
        }
    }

    private void startAllComponents(MaterializedConfiguration materializedConfiguration) {
        logger.info("Starting new configuration:{}", materializedConfiguration);

        this.materializedConfiguration = materializedConfiguration;

        for (Entry<String, Channel> entry :
                materializedConfiguration.getChannels().entrySet()) {
            try {
                logger.info("Starting Channel " + entry.getKey());
                supervisor.supervise(entry.getValue(),
                        new SupervisorPolicy.AlwaysRestartPolicy(), LifecycleState.START);
            } catch (Exception e) {
                logger.error("Error while starting {}", entry.getValue(), e);
            }
        }

    /*
     * Wait for all channels to start.
     */
        for (Channel ch : materializedConfiguration.getChannels().values()) {
            while (ch.getLifecycleState() != LifecycleState.START
                    && !supervisor.isComponentInErrorState(ch)) {
                try {
                    logger.info("Waiting for channel: " + ch.getName() +
                            " to start. Sleeping for 500 ms");
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    logger.error("Interrupted while waiting for channel to start.", e);
                    Throwables.propagate(e);
                }
            }
        }

        for (Entry<String, SinkRunner> entry : materializedConfiguration.getSinkRunners()
                .entrySet()) {
            try {
                logger.info("Starting Sink " + entry.getKey());
                supervisor.supervise(entry.getValue(),
                        new SupervisorPolicy.AlwaysRestartPolicy(), LifecycleState.START);
            } catch (Exception e) {
                logger.error("Error while starting {}", entry.getValue(), e);
            }
        }

        for (Entry<String, SourceRunner> entry : materializedConfiguration
                .getSourceRunners().entrySet()) {
            try {
                logger.info("Starting Source " + entry.getKey());
                supervisor.supervise(entry.getValue(),
                        new SupervisorPolicy.AlwaysRestartPolicy(), LifecycleState.START);
            } catch (Exception e) {
                logger.error("Error while starting {}", entry.getValue(), e);
            }
        }

        this.loadMonitoring();
    }


    @SuppressWarnings("unchecked")
    private void loadMonitoring() {
        Properties systemProps = System.getProperties();
        Set<String> keys = systemProps.stringPropertyNames();
        try {
            if (keys.contains(CONF_MONITOR_CLASS)) {
                String monitorType = systemProps.getProperty(CONF_MONITOR_CLASS);
                Class<? extends MonitorService> klass;
                try {
                    //Is it a known type?
                    klass = MonitoringType.valueOf(
                            monitorType.toUpperCase()).getMonitorClass();
                } catch (Exception e) {
                    //Not a known type, use FQCN
                    klass = (Class<? extends MonitorService>) Class.forName(monitorType);
                }
                this.monitorServer = klass.newInstance();
                Context context = new Context();
                for (String key : keys) {
                    if (key.startsWith(CONF_MONITOR_PREFIX)) {
                        context.put(key.substring(CONF_MONITOR_PREFIX.length()),
                                systemProps.getProperty(key));
                    }
                }
                monitorServer.configure(context);
                monitorServer.start();
            }
        } catch (Exception e) {
            logger.warn("Error starting monitoring. "
                    + "Monitoring might not be available.", e);
        }

    }

    public static void main(String[] args) {

        try {

            // 以下是解析 shell 命令行选项，使用了 apache commons-cli
            // 通常情况下命令行处理有三个步骤：定义，解析和询问阶段
            Options options = new Options();

            // 定义阶段：
            // 第一个参数：代表短参数，-shortArg
            // 第二个参数：长参数，--LongArg
            // 第三个参数：指明这个 option 是否需要输入数值
            // 第四个参数：对这个 option 的简要描述，该描述信息在打印命令行帮助信息时，会显示出来
            Option option = new Option("n", "name", true, "the name of this agent");
            option.setRequired(true);
            options.addOption(option);

            option = new Option("f", "conf-file", true, "specify a conf file");
            option.setRequired(true);
            options.addOption(option);

            option = new Option(null, "no-reload-conf", false, "do not reload " +
                    "conf file if changed");
            options.addOption(option);

            option = new Option("h", "help", false, "display help text");
            options.addOption(option);

            // 解析阶段：
            // CommandLineParser提供的方法parse，就是用来解析命令行中的参数。
            // 接口CommandLineParser存在多种实现类，比如BasicParser、PosixParser和GnuParser，
            // PosixParser与GnuParser，顾名思义，其区别在于，
            // 前者把形如-log的选项作为三个选项(l、o和g)处理，而后者作为一个选项处理。
            CommandLineParser parser = new GnuParser();
            CommandLine commandLine = parser.parse(options, args);

            // 询问阶段：
            File configurationFile = new File(commandLine.getOptionValue('f'));
            String agentName = commandLine.getOptionValue('n');
            boolean reload = !commandLine.hasOption("no-reload-conf");

            // 命令中有 -h 或 --help 打印帮助信息
            if (commandLine.hasOption('h')) {
                new HelpFormatter().printHelp("flume-ng agent", options, true);
                return;
            }
      /*
       * The following is to ensure that by default the agent
       * will fail on startup if the file does not exist.
       */
            if (!configurationFile.exists()) {
                // If command line invocation, then need to fail fast
                // 详细说明：Reference：http://flume.apache.org/FlumeUserGuide.html#flume-properties
                // 大概作用：如果配置文件没有找到，但是指定了这个属性，就不会报错，
                // 但是每 30S 轮询一次配置文件地址
                if (System.getProperty(Constants.SYSPROP_CALLED_FROM_SERVICE) == null) {
                    String path = configurationFile.getPath();
                    try {
                        path = configurationFile.getCanonicalPath();
                    } catch (IOException ex) {
                        logger.error("Failed to read canonical path for file: " + path, ex);
                    }
                    throw new ParseException(
                            "The specified configuration file does not exist: " + path);
                }
            }
            List<LifecycleAware> components = Lists.newArrayList();
            Application application;
            // 根据命令中含有"no-reload-conf"参数，决定采用那种加载配置文件方式：
            // 一、没有此参数，会动态加载配置文件，默认每30秒加载一次配置文件，因此可以动态修改配置文件，
            //    实现动态加载功能采用了发布订阅模式，使用guava中的EventBus实现
            // 二、有此参数，则只在启动时加载一次配置文件。
            if (reload) {
                EventBus eventBus = new EventBus(agentName + "-event-bus");
                //这里是发布事件的类，这里的 30s 则是动态加载配置文件时间间隔，单位是s
                PollingPropertiesFileConfigurationProvider configurationProvider =
                        new PollingPropertiesFileConfigurationProvider(agentName,
                                configurationFile, eventBus, 30);
                components.add(configurationProvider);
                application = new Application(components);
                eventBus.register(application); // 将订阅类注册到 EventBus 中
            } else {
                PropertiesFileConfigurationProvider configurationProvider =
                        new PropertiesFileConfigurationProvider(agentName,
                                configurationFile);
                application = new Application();
                application.handleConfigurationEvent(configurationProvider.getConfiguration());
            }
            application.start();

            final Application appReference = application;
            // 这个方法的意思就是在jvm中增加一个关闭的钩子，
            // 当jvm关闭的时候，会执行系统中已经设置的所有通过方法addShutdownHook添加的钩子，
            // 当系统执行完这些钩子后，jvm才会关闭。所以这些钩子可以在jvm关闭的时候进行内存清理、对象销毁等操作
            Runtime.getRuntime().addShutdownHook(new Thread("agent-shutdown-hook") {
                @Override
                public void run() {
                    appReference.stop();
                }
            });

        } catch (Exception e) {
            logger.error("A fatal error occurred while running. Exception follows.",
                    e);
        }
    }
}