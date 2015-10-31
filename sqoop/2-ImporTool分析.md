# ImportTool分析 #

[1-程序流程.md](1-程序流程.md)分析了RunnerTool.run()函数会根据不同的参数调用不同的SqoopTool的run函数，本文用ImportTool分析SqoopTool执行流程，涉及到数据库的都默认为连接Mysql。

## 功能 ##

ImportTool实现从数据库导入至HDFS、Hive、HBase；同时查询功能也调用此类；

## ImportTool继承关系 ##

![sqoop_packages](picture\ImportTool.png)

`org.apache.sqoop.tool.ImportTool#run`：

```java
  public int run(SqoopOptions options) {
    HiveImport hiveImport = null;

    if (allTables) {
      // We got into this method, but we should be in a subclass.
      // (This method only handles a single table)
      // This should not be reached, but for sanity's sake, test here.
      LOG.error("ImportTool.run() can only handle a single table.");
      return 1;
    }

	// 初始化
    if (!init(options)) {
      return 1;
    }

    codeGenerator.setManager(manager);

    try {
      if (options.doHiveImport()) {
        hiveImport = new HiveImport(options, manager, options.getConf(), false);
      }

      // Import a single table (or query) the user specified.
      importTable(options, options.getTableName(), hiveImport);
    } catch (IllegalArgumentException iea) {
			....略.....
    } finally {
      destroy(options);
    }

    return 0;
  }
```

从以上可以看到大概的流程：
1. 初始化，主要是初始化数据库连接管理器，用来操作与数据库相关的工作；
2. 根据上文获得的数据库连接管理，调用`org.apache.sqoop.tool.CodeGenTool`类从数据库中获取表的信息，生成、编译、打包ORM代码；
3. 根据ImportJobContext对象信息，生成`org.apache.sqoop.mapreduce.ImportJobBase`对象实例，例如导入HDFS则生成它的子类实现`org.apache.sqoop.mapreduce.DataDrivenImportJob`，此对象封装了Mapreduce job的任务，执行`importer.runImport()`提交任务至集群，运行Mapreduce任务。

## 重要步骤分析 ##
### 初始化数据库连接管理 ###
TODO

### 自动生成ORM代码　###

生成ORM代码功能主要由`org.apache.sqoop.tool.CodeGenTool`实现。
在`org.apache.sqoop.tool.CodeGenTool#generateORM`中：

```java
  public String generateORM(SqoopOptions options, String tableName)
      throws IOException {
              --------- 略 -------------
    LOG.info("Beginning code generation");
    CompilationManager compileMgr = new CompilationManager(options);
    ClassWriter classWriter = new ClassWriter(options, manager, tableName,
        compileMgr);
    classWriter.generate();		// 生成.java文件
    compileMgr.compile();		// 编译成.class
    compileMgr.jar();			// 打包成jar
    String jarFile = compileMgr.getJarFilename();
    this.generatedJarFiles.add(jarFile);
    return jarFile;
  }
```

#### 生成.java文件 ####
1. 连接数据库信息，执行Sql语句：`SELECT t.* FROM TABLE_NAME AS t LIMIT 1`，得到ResultSet对象，从对象中获取所需要的信息，字段名，字段类型。并对字段名名检查其符合Java规范。
2. 接着通过`org.apache.sqoop.orm.ClassWriter#generateClassForColumns`函数生成.java文件。

#### 编译成.class ####
通过内置的java命令，生成.class文件

#### 打包成jar ####

### 配置Mapreduce job ###

`org.apache.sqoop.mapreduce.DataDrivenImportJob`完成Mapreduce job的配置过程。

#### 继承关系 ####

![sqoop_packages](picture\DataDirvenImportJob.png)

#### 源代码 ####
`org.apache.sqoop.manager.SqlManager#importTable`
```java
  public void runImport(String tableName, String ormJarFile, String splitByCol,
  ----------- 略 ------------------
    loadJars(conf, ormJarFile, tableClassName);

    Job job = createJob(conf);
    try {
      // Set the external jar to use for the job.
      job.getConfiguration().set("mapred.jar", ormJarFile);
      if (options.getMapreduceJobName() != null) {
        job.setJobName(options.getMapreduceJobName());
      }
		// 配置job相关信息
      propagateOptionsToJob(job);
      configureInputFormat(job, tableName, tableClassName, splitByCol);
      configureOutputFormat(job, tableName, tableClassName);
      configureMapper(job, tableName, tableClassName);
      configureNumTasks(job);
      cacheJars(job, getContext().getConnManager());

      jobSetup(job);
      setJob(job);
      boolean success = runJob(job);	// 运行job
      if (!success) {
        throw new ImportException("Import job failed!");
      }

      completeImport(job);

      if (options.isValidationEnabled()) {
        validateImport(tableName, conf, job);
      }
    } catch (InterruptedException ie) {
      throw new IOException(ie);
    } catch (ClassNotFoundException cnfe) {
      throw new IOException(cnfe);
    } finally {
      unloadJars();
      jobTeardown(job);
    }
  }
```

#### 实现思路 ####
为了方便MapReduce直接访问关系型数据库，Hadoop中分别提供了DBInputFormat类和DBOutputFormat类。DBInputFormat用于从关系数据库输入到HDFS，该类将关系数据库中的一条记录作为向Mapper输入的value值；DBOutputFormat用于将HDFS中的文件输出到关系数据库，该类将Reducer输出的key值存储到数据库。`org.apache.sqoop.mapreduce.DataDrivenImportJob`就是对是使用了DBInputFormat类完成了Mapreduce访问关系型数据库。使用DBInputFormat类通常类有这几个步骤：
1. 使用`DBConfiguration.configureDB(JobConf job, String driverClass, String dbUrl, String userName, String passwd)`方法设置数据库相关信息；
2. 使用`DBInputFormat.setInput(JobConf job, Class<? extends DBWritable> inputClass, String tableName, String conditions, String orderBy, String... fieldNames)`方法初始化job设置，其中inputClass实现DBWritable接口，对应数据库表中的字段；
3. 编写Mapreduce函数，输入输出格式配置等，运行job；