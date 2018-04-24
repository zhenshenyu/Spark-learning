### SparkContext.scala源码解读
首先看看源码中对SparkContext的定义
```scala
/**
 * Main entry point for Spark functionality. A SparkContext represents the connection to a Spark
 * cluster, and can be used to create RDDs, accumulators and broadcast variables on that cluster.
 *
 * Only one SparkContext may be active per JVM.  You must `stop()` the active SparkContext before
 * creating a new one.  This limitation may eventually be removed; see SPARK-2243 for more details.
 *
 * @param config a Spark Config object describing the application configuration. Any settings in
 *   this config overrides the default configs as well as system properties.
 */
```
##### SparkContext是Spark所有功能的主入口，一个SparkContext代表了与Spark集群的连接，可以用它在集群上创建RDD，计算，广播变量。
SparkContest的主构造函数只需要传入一个SparkConf类
```scala
class SparkContext(config: SparkConf) extends Logging
```
在创建一个SparkContext对象的过程中，首先会载入SparkConf配置文件，对应不同的参数类型，共有3种构造函数。
```scala
  /**
   * Create a SparkContext that loads settings from system properties (for instance, when
   * launching with ./bin/spark-submit).
   */
   // 使用spark-submit提交时，从系统配置文件中读取配置
  def this() = this(new SparkConf())

  /**
   * Alternative constructor that allows setting common Spark properties directly
   *
   * @param master Cluster URL to connect to (e.g. mesos://host:port, spark://host:port, local[4]).
   * @param appName A name for your application, to display on the cluster web UI
   * @param conf a [[org.apache.spark.SparkConf]] object specifying other Spark parameters
   */
  def this(master: String, appName: String, conf: SparkConf) =
    this(SparkContext.updatedConf(conf, master, appName))

  /**
   * Alternative constructor that allows setting common Spark properties directly
   *
   * @param master Cluster URL to connect to (e.g. mesos://host:port, spark://host:port, local[4]).
   * @param appName A name for your application, to display on the cluster web UI.
   * @param sparkHome Location where Spark is installed on cluster nodes.
   * @param jars Collection of JARs to send to the cluster. These can be paths on the local file
   *             system or HDFS, HTTP, HTTPS, or FTP URLs.
   * @param environment Environment variables to set on worker nodes.
   */
  def this(
      master: String,
      appName: String,
      sparkHome: String = null,
      jars: Seq[String] = Nil,
      environment: Map[String, String] = Map()) = {
    this(SparkContext.updatedConf(new SparkConf(), master, appName, sparkHome, jars, environment))
  }
```
在应用运行过程中修改配置文件是无效的
```scala
  private[spark] def conf: SparkConf = _conf

  /**
   * Return a copy of this SparkContext's configuration. The configuration ''cannot'' be
   * changed at runtime.
   */
  def getConf: SparkConf = conf.clone()
```
SparkContext中的私有变量
```scala
  /* ------------------------------------------------------------------------------------- *
   | Private variables. These variables keep the internal state of the context, and are    |
   | not accessible by the outside world. They're mutable since we want to initialize all  |
   | of them to some neutral value ahead of time, so that calling "stop()" while the       |
   | constructor is still running is safe.                                                 |
   * ------------------------------------------------------------------------------------- */

  private var _conf: SparkConf = _
  private var _eventLogDir: Option[URI] = None
  private var _eventLogCodec: Option[String] = None
  private var _env: SparkEnv = _
  private var _jobProgressListener: JobProgressListener = _
  private var _statusTracker: SparkStatusTracker = _
  private var _progressBar: Option[ConsoleProgressBar] = None
  private var _ui: Option[SparkUI] = None
  private var _hadoopConfiguration: Configuration = _
  private var _executorMemory: Int = _
  private var _schedulerBackend: SchedulerBackend = _
  private var _taskScheduler: TaskScheduler = _
  private var _heartbeatReceiver: RpcEndpointRef = _
  @volatile private var _dagScheduler: DAGScheduler = _
  private var _applicationId: String = _
  private var _applicationAttemptId: Option[String] = None
  private var _eventLogger: Option[EventLoggingListener] = None
  private var _executorAllocationManager: Option[ExecutorAllocationManager] = None
  private var _cleaner: Option[ContextCleaner] = None
  private var _listenerBusStarted: Boolean = false
  private var _jars: Seq[String] = _
  private var _files: Seq[String] = _
  private var _shutdownHookRef: AnyRef = _


  // Environment variables to pass to our executors.
  private[spark] val executorEnvs = HashMap[String, String]()

  // Set SPARK_USER for user who is running SparkContext.
  val sparkUser = Utils.getCurrentUserName()
```

---

##### SparkContext的初始化过程
如果没有配置master或appname，将会返回异常。如果使用yarn client模式，没有配置yarn的appid（似乎对用户透明），也会返回异常
```scala
    _conf = config.clone()
    _conf.validateSettings()
    if (!_conf.contains("spark.master")) {
      throw new SparkException("A master URL must be set in your configuration")
    }
    if (!_conf.contains("spark.app.name")) {
      throw new SparkException("An application name must be set in your configuration")
    }
    // System property spark.yarn.app.id must be set if user code ran by AM on a YARN cluster
    if (master == "yarn" && deployMode == "cluster" && !_conf.contains("spark.yarn.app.id")) {
      throw new SparkException("Detected yarn cluster mode, but isn't running on a cluster. " +
        "Deployment to YARN is not supported directly by SparkContext. Please use spark-submit.")
    }

```
初始化日志路径
```scala
    _eventLogDir =
      if (isEventLogEnabled) {
        val unresolvedDir = conf.get("spark.eventLog.dir", EventLoggingListener.DEFAULT_LOG_DIR)
          .stripSuffix("/")
        Some(Utils.resolveURI(unresolvedDir))
      } else {
        None
      }

```
在初始化SparkEnv前，先初始化JobProgressListener，以获取初始化SparkEnv过程中的一些信息
```scala
    // "_jobProgressListener" should be set up before creating SparkEnv because when creating
    // "SparkEnv", some messages will be posted to "listenerBus" and we should not miss them.
    _jobProgressListener = new JobProgressListener(_conf)
    listenerBus.addListener(jobProgressListener)
```
初始化env，SparkEnv是一个非常重要的变量，其内包含了许多Spark运行时的重要组件（变量）。
```scala
  // An asynchronous listener bus for Spark events
  private[spark] val listenerBus = new LiveListenerBus(this)

  // createSparkEnv的定义
  // This function allows components created by SparkEnv to be mocked in unit tests:
  private[spark] def createSparkEnv(
      conf: SparkConf,
      isLocal: Boolean,
      listenerBus: LiveListenerBus): SparkEnv = {
    SparkEnv.createDriverEnv(conf, isLocal, listenerBus, SparkContext.numDriverCores(master))
  }

    // _env初始化过程
    // Create the Spark execution environment (cache, map output tracker, etc)
    _env = createSparkEnv(_conf, isLocal, listenerBus)
    SparkEnv.set(_env)
```
可以看到，createSparkEnv最终调用到了SparkEnv中的createDriverEnv方法。  
SparkEnv的createDriverEnv方法会调用私有create方法来创建serializer，closureSerializer，cacheManager等，创建完成后会创建SparkEnv对象。
```scala
  // 位于object SparkEnv
  /**
   * Create a SparkEnv for the driver.
   */
  private[spark] def createDriverEnv(
      conf: SparkConf,
      isLocal: Boolean,
      listenerBus: LiveListenerBus,
      numCores: Int,
      mockOutputCommitCoordinator: Option[OutputCommitCoordinator] = None): SparkEnv = {
    assert(conf.contains(DRIVER_HOST_ADDRESS),
      s"${DRIVER_HOST_ADDRESS.key} is not set on the driver!")
    assert(conf.contains("spark.driver.port"), "spark.driver.port is not set on the driver!")
    val bindAddress = conf.get(DRIVER_BIND_ADDRESS)
    val advertiseAddress = conf.get(DRIVER_HOST_ADDRESS)
    val port = conf.get("spark.driver.port").toInt
    val ioEncryptionKey = if (conf.get(IO_ENCRYPTION_ENABLED)) {
      Some(CryptoStreamUtils.createKey(conf))
    } else {
      None
    }
    create(
      conf,
      SparkContext.DRIVER_IDENTIFIER,
      bindAddress,
      advertiseAddress,
      port,
      isLocal,
      numCores,
      ioEncryptionKey,
      listenerBus = listenerBus,
      mockOutputCommitCoordinator = mockOutputCommitCoordinator
    )
  }



  /**
   * Helper method to create a SparkEnv for a driver or an executor.
   */
    private def create(
      conf: SparkConf,
      executorId: String,
      bindAddress: String,
      advertiseAddress: String,
      port: Int,
      isLocal: Boolean,
      numUsableCores: Int,
      ioEncryptionKey: Option[Array[Byte]],
      listenerBus: LiveListenerBus = null,
      mockOutputCommitCoordinator: Option[OutputCommitCoordinator] = None): SparkEnv = {
      // ...
      
      val envInstance = new SparkEnv(
      executorId,
      rpcEnv,
      serializer,
      closureSerializer,
      serializerManager,
      mapOutputTracker, // map任务输出跟踪器 
      shuffleManager,   // shuffle管理器
      broadcastManager, // 广播管理器 
      blockManager,     // block管理器  
      securityManager,
      metricsSystem,
      memoryManager,    // 内存管理器 
      outputCommitCoordinator,
      conf)
      
      // ...
      }
```
初始化SparkUI  
首先通过SparkUI.createLiveUI 创建一个SparkUI实例 _ui。 
然后通过 _ui.foreach(_.bind())启动jetty。bind 方法是继承自WebUI，WebUI直接与Jetty Server API有关联。
```scala
    _ui =
      if (conf.getBoolean("spark.ui.enabled", true)) {
        Some(SparkUI.createLiveUI(this, _conf, listenerBus, _jobProgressListener,
          _env.securityManager, appName, startTime = startTime))
      } else {
        // For tests, do not enable the UI
        None
      }
    // Bind the UI before starting the task scheduler to communicate
    // the bound port to the cluster manager properly
    _ui.foreach(_.bind())
```
获取配置中的executorMemory，默认为1Gb
```scala
    _executorMemory = _conf.getOption("spark.executor.memory")
      .orElse(Option(System.getenv("SPARK_EXECUTOR_MEMORY")))
      .orElse(Option(System.getenv("SPARK_MEM"))
      .map(warnSparkMem))
      .map(Utils.memoryStringToMb)
      .getOrElse(1024)
```
将JVM相关运行参数存储到executorEnvs中
```scala
    // Convert java options to env vars as a work around
    // since we can't set env vars directly in sbt.
    for { (envKey, propKey) <- Seq(("SPARK_TESTING", "spark.testing"))
      value <- Option(System.getenv(envKey)).orElse(Option(System.getProperty(propKey)))} {
      executorEnvs(envKey) = value
    }
    Option(System.getenv("SPARK_PREPEND_CLASSES")).foreach { v =>
      executorEnvs("SPARK_PREPEND_CLASSES") = v
    }
    // The Mesos scheduler backend relies on this environment variable to set executor memory.
    // TODO: Set this only in the Mesos scheduler.
    // 仅在mesos模式下设置
    executorEnvs("SPARK_EXECUTOR_MEMORY") = executorMemory + "m"
    executorEnvs ++= _conf.getExecutorEnv
    executorEnvs("SPARK_USER") = sparkUser
```
在初始化TaskScheduler前，先初始化_heartbeatReceiver
```scala
    // We need to register "HeartbeatReceiver" before "createTaskScheduler" because Executor will
    // retrieve "HeartbeatReceiver" in the constructor. (SPARK-6640)
    _heartbeatReceiver = env.rpcEnv.setupEndpoint(
      HeartbeatReceiver.ENDPOINT_NAME, new HeartbeatReceiver(this))
```
初始化_taskScheduler与_dagScheduler  
详情见**Spark任务调度**
```scala
   // Create and start the scheduler
    val (sched, ts) = SparkContext.createTaskScheduler(this, master, deployMode)
    _schedulerBackend = sched
    _taskScheduler = ts
    _dagScheduler = new DAGScheduler(this)
    _heartbeatReceiver.ask[Boolean](TaskSchedulerIsSet)

    // start TaskScheduler after taskScheduler sets DAGScheduler reference in DAGScheduler's
    // constructor
    _taskScheduler.start()
    
    // 获取applicationId，初始化SparkUI与BlockManager的applicationId
    _applicationId = _taskScheduler.applicationId()
    _applicationAttemptId = taskScheduler.applicationAttemptId()
    _conf.set("spark.app.id", _applicationId)
    if (_conf.getBoolean("spark.ui.reverseProxy", false)) {
      System.setProperty("spark.ui.proxyBase", "/proxy/" + _applicationId)
    }
    _ui.foreach(_.setAppId(_applicationId))
    _env.blockManager.initialize(_applicationId)
    
    
  /**
   * Create a task scheduler based on a given master URL.
   * Return a 2-tuple of the scheduler backend and the task scheduler.
   */
  private def createTaskScheduler(
      sc: SparkContext,
      master: String,
      deployMode: String): (SchedulerBackend, TaskScheduler)


// DAGScheduler构造函数
class DAGScheduler(
    private[scheduler] val sc: SparkContext,
    private[scheduler] val taskScheduler: TaskScheduler,
    listenerBus: LiveListenerBus,
    mapOutputTracker: MapOutputTrackerMaster,
    blockManagerMaster: BlockManagerMaster,
    env: SparkEnv,
    clock: Clock = new SystemClock())
  extends Logging 
  
  def this(sc: SparkContext, taskScheduler: TaskScheduler) = {
    this(
      sc,
      taskScheduler,
      sc.listenerBus,
      sc.env.mapOutputTracker.asInstanceOf[MapOutputTrackerMaster],
      sc.env.blockManager.master,
      sc.env)
  }
  
  def this(sc: SparkContext) = this(sc, sc.taskScheduler)
```
_executorAllocationManager用来动态地分配executor，当配置符合要求时，会为其生成一个ExecutorAllocationManager实例。默认情况下不会创建ExecutorAllocationManager，可以修改属性spark.dynamicAllocation.enabled为true来创建。（这里的case b:不太理解）
```scala
    // Optionally scale number of executors dynamically based on workload. Exposed for testing.
    val dynamicAllocationEnabled = Utils.isDynamicAllocationEnabled(_conf)
    _executorAllocationManager =
      if (dynamicAllocationEnabled) {
        schedulerBackend match {
          case b: ExecutorAllocationClient =>
            Some(new ExecutorAllocationManager(
              schedulerBackend.asInstanceOf[ExecutorAllocationClient], listenerBus, _conf))
          case _ =>
            None
        }
      } else {
        None
      }
    _executorAllocationManager.foreach(_.start())
```
接下来初始化ContextCleaner，ContextCleaner用于清理那些超出应用范围的RDD、ShuffleDependency和Broadcast对象。ContextCleaner的组成如下：
- referenceQueue：缓存顶级的AnyRef引用；
- referenceBuffer：缓存AnyRef的虚引用；
- listeners：缓存清理工作的监听器数组；
- cleaningThread：用于具体清理工作的线程。
- periodicGCInterval：

CleanupTaskWeakReference是整个cleanup的核心，这是一个普通类，有三个成员，并且继承WeakReference。referent是一个weak reference，当指向的对象被gc时，把CleanupTaskWeakReference放到队列里
（**这里不理解，第二遍再详细看**）
```scala
    _cleaner =
      if (_conf.getBoolean("spark.cleaner.referenceTracking", true)) {
        Some(new ContextCleaner(this))
      } else {
        None
      }
    _cleaner.foreach(_.start())
    

// CleanupTaskWeakReference定义
/**
 * A WeakReference associated with a CleanupTask.
 *
 * When the referent object becomes only weakly reachable, the corresponding
 * CleanupTaskWeakReference is automatically added to the given reference queue.
 */
private class CleanupTaskWeakReference(
    val task: CleanupTask,
    referent: AnyRef,
    referenceQueue: ReferenceQueue[AnyRef])
  extends WeakReference(referent, referenceQueue)

    
// ContextCleaner定义
private[spark] class ContextCleaner(sc: SparkContext) extends Logging {

  /**
   * A buffer to ensure that `CleanupTaskWeakReference`s are not garbage collected as long as they
   * have not been handled by the reference queue.
   */
  private val referenceBuffer =
    Collections.newSetFromMap[CleanupTaskWeakReference](new ConcurrentHashMap)

  private val referenceQueue = new ReferenceQueue[AnyRef]

  private val listeners = new ConcurrentLinkedQueue[CleanerListener]()

  private val cleaningThread = new Thread() { override def run() { keepCleaning() }}

  private val periodicGCService: ScheduledExecutorService =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("context-cleaner-periodic-gc")
```
SparkContext初始化的最后一部分
```scala
    setupAndStartListenerBus()
    // 在SparkContext的初始化过程中，可能对其环境造成影响，所以需要更新环境。 
    postEnvironmentUpdate()
    postApplicationStart()

    // Post init
    _taskScheduler.postStartHook()
    _env.metricsSystem.registerSource(_dagScheduler.metricsSource)
    _env.metricsSystem.registerSource(new BlockManagerSource(_env.blockManager))
    _executorAllocationManager.foreach { e =>
      _env.metricsSystem.registerSource(e.executorAllocationManagerSource)
    }
    
    // 添加shutdownHook
    // Make sure the context is stopped if the user forgets about it. This avoids leaving
    // unfinished event logs around after the JVM exits cleanly. It doesn't help if the JVM
    // is killed, though.
    logDebug("Adding shutdown hook") // force eager creation of logger
    _shutdownHookRef = ShutdownHookManager.addShutdownHook(
      ShutdownHookManager.SPARK_CONTEXT_SHUTDOWN_PRIORITY) { () =>
      logInfo("Invoking stop() from shutdown hook")
      stop()
    }
```

---

##### SparkContext中的成员函数
**待补充