Spark Standalone cluster模式启动，主要工作是启动Master和Worker守护进程  
也就是sbin目录下的两个脚本
```
start-master.sh
start-slave.sh
```
其中分别调用的类为
```
org.apache.spark.deploy.master.Master
org.apache.spark.deploy.worker.Worker
```
#### Master
先看看Master类构造参数  
Master实际上可以被当做一个Endpoint
```scala
private[deploy] class Master(
    // rpcEnv，详见RPC系统
    override val rpcEnv: RpcEnv,
    address: RpcAddress,
    webUiPort: Int,
    val securityMgr: SecurityManager,
    val conf: SparkConf)
  // LeaderElectable负责选举出新的Master
  extends ThreadSafeRpcEndpoint with Logging with LeaderElectable
```
看看Master伴生对象的main函数
```scala
private[deploy] object Master extends Logging {
  val SYSTEM_NAME = "sparkMaster"
  val ENDPOINT_NAME = "Master"

  def main(argStrings: Array[String]) {
    Utils.initDaemon(log)
    // 创建SparkConf对象
    val conf = new SparkConf
    // Master参数
    val args = new MasterArguments(argStrings, conf)
    // 创建一个masterEndpoint并获取返回的rpcEnv
    val (rpcEnv, _, _) = startRpcEnvAndEndpoint(args.host, args.port, args.webUiPort, conf)
    rpcEnv.awaitTermination()
  }

  /**
   * Start the Master and return a three tuple of:
   *   (1) The Master RpcEnv
   *   (2) The web UI bound port
   *   (3) The REST server bound port, if any
   */
  def startRpcEnvAndEndpoint(
      host: String,
      port: Int,
      webUiPort: Int,
      conf: SparkConf): (RpcEnv, Int, Option[Int]) = {
    // securityManager主要用于权限设置，比如在使用yarn作为资源调度框架时，用于生成secret key进行登录。
    // 该类默认只用一个实例，所有的app使用同一个实例
    val securityMgr = new SecurityManager(conf)
    // 新建RpcEnv实例
    val rpcEnv = RpcEnv.create(SYSTEM_NAME, host, port, conf, securityMgr)
    // 新建一个Endpoint实例
    val masterEndpoint = rpcEnv.setupEndpoint(ENDPOINT_NAME,
      new Master(rpcEnv, rpcEnv.address, webUiPort, securityMgr, conf))
    val portsResponse = masterEndpoint.askSync[BoundPortsResponse](BoundPortsRequest)
    (rpcEnv, portsResponse.webUIPort, portsResponse.restPort)
  }
}
```
MasterArguments类  

```scala
/**
 * Command-line parser for the master.
 */
private[master] class MasterArguments(args: Array[String], conf: SparkConf) extends Logging {
  var host = Utils.localHostName()
  var port = 7077
  var webUiPort = 8080
  // 配置文件，默认为spark-default.conf
  var propertiesFile: String = null

  // Check for settings in environment variables
  // 首先检查环境变量
  if (System.getenv("SPARK_MASTER_IP") != null) {
    logWarning("SPARK_MASTER_IP is deprecated, please use SPARK_MASTER_HOST")
    host = System.getenv("SPARK_MASTER_IP")
  }

  if (System.getenv("SPARK_MASTER_HOST") != null) {
    host = System.getenv("SPARK_MASTER_HOST")
  }
  if (System.getenv("SPARK_MASTER_PORT") != null) {
    port = System.getenv("SPARK_MASTER_PORT").toInt
  }
  if (System.getenv("SPARK_MASTER_WEBUI_PORT") != null) {
    webUiPort = System.getenv("SPARK_MASTER_WEBUI_PORT").toInt
  }

  parse(args.toList)

  // This mutates the SparkConf, so all accesses to it must be made after this line
  propertiesFile = Utils.loadDefaultSparkProperties(conf, propertiesFile)
  
  // 如果传入的SparkConf实例中包含"spark.master.ui.port"，将覆盖环境变量的SPARK_MASTER_WEBUI_PORT
  if (conf.contains("spark.master.ui.port")) {
    webUiPort = conf.get("spark.master.ui.port").toInt
  }

  // 解析命令行传入的参数，命令行参数会把环境变量和Spark属性都覆盖
  @tailrec
  private def parse(args: List[String]): Unit = args match {
    case ("--ip" | "-i") :: value :: tail =>
      Utils.checkHost(value, "ip no longer supported, please use hostname " + value)
      host = value
      parse(tail)

    case ("--host" | "-h") :: value :: tail =>
      Utils.checkHost(value, "Please use hostname " + value)
      host = value
      parse(tail)

    case ("--port" | "-p") :: IntParam(value) :: tail =>
      port = value
      parse(tail)

    case "--webui-port" :: IntParam(value) :: tail =>
      webUiPort = value
      parse(tail)

    case ("--properties-file") :: value :: tail =>
      propertiesFile = value
      parse(tail)

    case ("--help") :: tail =>
      printUsageAndExit(0)

    case Nil => // No-op

    case _ =>
      printUsageAndExit(1)
  }

  /**
   * Print usage and exit JVM with the given exit code.
   */
  private def printUsageAndExit(exitCode: Int) {
    // scalastyle:off println
    System.err.println(
      "Usage: Master [options]\n" +
      "\n" +
      "Options:\n" +
      "  -i HOST, --ip HOST     Hostname to listen on (deprecated, please use --host or -h) \n" +
      "  -h HOST, --host HOST   Hostname to listen on\n" +
      "  -p PORT, --port PORT   Port to listen on (default: 7077)\n" +
      "  --webui-port PORT      Port for web UI (default: 8080)\n" +
      "  --properties-file FILE Path to a custom Spark properties file.\n" +
      "                         Default is conf/spark-defaults.conf.")
    // scalastyle:on println
    System.exit(exitCode)
  }
}
```
创建完EndPoint后，执行了master的onStart
```scala
  override def onStart(): Unit = {
    logInfo("Starting Spark master at " + masterUrl)
    logInfo(s"Running Spark version ${org.apache.spark.SPARK_VERSION}")
    webUi = new MasterWebUI(this, webUiPort)
    webUi.bind()
    masterWebUiUrl = "http://" + masterPublicAddress + ":" + webUi.boundPort
    if (reverseProxy) {
      masterWebUiUrl = conf.get("spark.ui.reverseProxyUrl", masterWebUiUrl)
      logInfo(s"Spark Master is acting as a reverse proxy. Master, Workers and " +
       s"Applications UIs are available at $masterWebUiUrl")
    }
    // 这里会启一个定时调度，检查timeout的worker进程。
    // 如果有worker超时，则将状态置为DEAD，并清理一些内存中关于该worker的信息，
    // 如果该worker中有Executor进程，则向driver发送ExecutorUpdated消息，表明该Executor也已经不可用了，
    // 如果该worker中有Driver进程，且配置driver是可以relaunch的，则重新调度在可用的worker节点上启动，
    // 否则删除该Driver的内存信息。
    // 只有在该worker超时很多次之后，才真正删除，之前只是让该worker不被选中执行任务。  
    checkForWorkerTimeOutTask = forwardMessageThread.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = Utils.tryLogNonFatalError {
        self.send(CheckForWorkerTimeOut)
      }
    }, 0, WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)

    if (restServerEnabled) {
      val port = conf.getInt("spark.master.rest.port", 6066)
      restServer = Some(new StandaloneRestServer(address.host, port, conf, self, masterUrl))
    }
    restServerBoundPort = restServer.map(_.start())

    masterMetricsSystem.registerSource(masterSource)
    masterMetricsSystem.start()
    applicationMetricsSystem.start()
    // Attach the master and app metrics servlet handler to the web ui after the metrics systems are
    // started.
    masterMetricsSystem.getServletHandlers.foreach(webUi.attachHandler)
    applicationMetricsSystem.getServletHandlers.foreach(webUi.attachHandler)

    val serializer = new JavaSerializer(conf)
    val (persistenceEngine_, leaderElectionAgent_) = RECOVERY_MODE match {
      case "ZOOKEEPER" =>
        logInfo("Persisting recovery state to ZooKeeper")
        val zkFactory =
          new ZooKeeperRecoveryModeFactory(conf, serializer)
        (zkFactory.createPersistenceEngine(), zkFactory.createLeaderElectionAgent(this))
      case "FILESYSTEM" =>
        val fsFactory =
          new FileSystemRecoveryModeFactory(conf, serializer)
        (fsFactory.createPersistenceEngine(), fsFactory.createLeaderElectionAgent(this))
      case "CUSTOM" =>
        val clazz = Utils.classForName(conf.get("spark.deploy.recoveryMode.factory"))
        val factory = clazz.getConstructor(classOf[SparkConf], classOf[Serializer])
          .newInstance(conf, serializer)
          .asInstanceOf[StandaloneRecoveryModeFactory]
        (factory.createPersistenceEngine(), factory.createLeaderElectionAgent(this))
      case _ =>
        (new BlackHolePersistenceEngine(), new MonarchyLeaderAgent(this))
    }
    persistenceEngine = persistenceEngine_
    leaderElectionAgent = leaderElectionAgent_
  }
```
#### Worker
Worker类的构造参数
```scala
private[deploy] class Worker(
    override val rpcEnv: RpcEnv,
    webUiPort: Int,
    cores: Int,
    memory: Int,
    masterRpcAddresses: Array[RpcAddress],
    endpointName: String,
    workDirPath: String = null,
    val conf: SparkConf,
    val securityMgr: SecurityManager)
  extends ThreadSafeRpcEndpoint with Logging
```
Worker伴生对象  
main方法与master类似，创建sparkConf，参数解析，以及构造worker对象并创建rpcEnv用于对外或者本身的信息交互。
```scala
private[deploy] object Worker extends Logging {
  val SYSTEM_NAME = "sparkWorker"
  val ENDPOINT_NAME = "Worker"

  def main(argStrings: Array[String]) {
    Utils.initDaemon(log)
    // 新建SparkConf实例
    val conf = new SparkConf
    // 解析SparkConf参数
    val args = new WorkerArguments(argStrings, conf)
    // 与Master
    val rpcEnv = startRpcEnvAndEndpoint(args.host, args.port, args.webUiPort, args.cores,
      args.memory, args.masters, args.workDir, conf = conf)
    rpcEnv.awaitTermination()
  }

  def startRpcEnvAndEndpoint(
      host: String,
      port: Int,
      webUiPort: Int,
      cores: Int,
      memory: Int,
      masterUrls: Array[String],
      workDir: String,
      workerNumber: Option[Int] = None,
      conf: SparkConf = new SparkConf): RpcEnv = {

    // The LocalSparkCluster runs multiple local sparkWorkerX RPC Environments
    val systemName = SYSTEM_NAME + workerNumber.map(_.toString).getOrElse("")
    val securityMgr = new SecurityManager(conf)
    val rpcEnv = RpcEnv.create(systemName, host, port, conf, securityMgr)
    val masterAddresses = masterUrls.map(RpcAddress.fromSparkURL(_))
    rpcEnv.setupEndpoint(ENDPOINT_NAME, new Worker(rpcEnv, webUiPort, cores, memory,
      masterAddresses, ENDPOINT_NAME, workDir, conf, securityMgr))
    rpcEnv
  }

  def isUseLocalNodeSSLConfig(cmd: Command): Boolean = {
    val pattern = """\-Dspark\.ssl\.useNodeLocalConf\=(.+)""".r
    val result = cmd.javaOpts.collectFirst {
      case pattern(_result) => _result.toBoolean
    }
    result.getOrElse(false)
  }
}
```
执行onStart方法向master注册
```scala
  override def onStart() {
    assert(!registered)
    logInfo("Starting Spark worker %s:%d with %d cores, %s RAM".format(
      host, port, cores, Utils.megabytesToString(memory)))
    logInfo(s"Running Spark version ${org.apache.spark.SPARK_VERSION}")
    logInfo("Spark home: " + sparkHome)
    // 创建工作目录
    createWorkDir()
    // 是否额外的启动一个shuffle服务，确保被executor所读写的shuffle文件在executor退出后被保存。
    shuffleService.startIfEnabled()
    webUi = new WorkerWebUI(this, workDir, webUiPort)
    webUi.bind()

    workerWebUiUrl = s"http://$publicAddress:${webUi.boundPort}"
    // 向master注册
    registerWithMaster()

    metricsSystem.registerSource(workerSource)
    metricsSystem.start()
    // Attach the worker metrics servlet handler to the web ui after the metrics system is started.
    metricsSystem.getServletHandlers.foreach(webUi.attachHandler)
  }
  
  
  private def registerWithMaster() {
    // onDisconnected may be triggered multiple times, so don't attempt registration
    // if there are outstanding registration attempts scheduled.
    registrationRetryTimer match {
      case None =>
        registered = false
        // 这里向所有的master rpcEnv发送RegisterWorker消息
        // 包括主节点与备用节点
        registerMasterFutures = tryRegisterAllMasters()
        connectionAttemptCount = 0
        registrationRetryTimer = Some(forwordMessageScheduler.scheduleAtFixedRate(
          new Runnable {
            override def run(): Unit = Utils.tryLogNonFatalError {
              Option(self).foreach(_.send(ReregisterWithMaster))
            }
          },
          INITIAL_REGISTRATION_RETRY_INTERVAL_SECONDS,
          INITIAL_REGISTRATION_RETRY_INTERVAL_SECONDS,
          TimeUnit.SECONDS))
      case Some(_) =>
        logInfo("Not spawning another attempt to register with the master, since there is an" +
          " attempt scheduled already.")
    }
  }
```
Master接收到RegisterWorker消息后，调用receive方法的case RegisterWorker进行处理。如果成功返RegisteredWorker并带上自己的masterUrl信息，失败则返回RegisterWorkerFailed  

worker从返回的RegisteredWorker消息中得知主master的rpcEnv，用于心跳等信息交互
```scala
    case RegisterWorker(
      id, workerHost, workerPort, workerRef, cores, memory, workerWebUiUrl, masterAddress) =>
      logInfo("Registering worker %s:%d with %d cores, %s RAM".format(
        workerHost, workerPort, cores, Utils.megabytesToString(memory)))
      if (state == RecoveryState.STANDBY) {
        workerRef.send(MasterInStandby)
      } else if (idToWorker.contains(id)) {
        workerRef.send(RegisterWorkerFailed("Duplicate worker ID"))
      } else {
        val worker = new WorkerInfo(id, workerHost, workerPort, cores, memory,
          workerRef, workerWebUiUrl)
        if (registerWorker(worker)) {
          persistenceEngine.addWorker(worker)
          workerRef.send(RegisteredWorker(self, masterWebUiUrl, masterAddress))
          schedule()
        } else {
          val workerAddress = worker.endpoint.address
          logWarning("Worker registration failed. Attempted to re-register worker at same " +
            "address: " + workerAddress)
          workerRef.send(RegisterWorkerFailed("Attempted to re-register worker at same address: "
            + workerAddress))
        }
      }
```
  
至此Master和Worker都启动成功