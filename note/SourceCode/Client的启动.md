#### Client的启动过程
StandaloneSchedulerBackend类

该类继承了CoarseGrainedSchedulerBackend类  
其主要作用是与Master通信，获取Executor的信息。具体工作由其中的Client对象完成，Client对象对应的类为StandAloneAppClient。AppClient会发送ApplicationDescription给Master，让Master分配资源。具体流程如下：
```scala
  // start方法
  override def start() {
    super.start()

    // SPARK-21159. The scheduler backend should only try to connect to the launcher when in client
    // mode. In cluster mode, the code that submits the application to the Master needs to connect
    // to the launcher instead.
    if (sc.deployMode == "client") {
      launcherBackend.connect()
    }

    // The endpoint for executors to talk to us
    val driverUrl = RpcEndpointAddress(
      sc.conf.get("spark.driver.host"),
      sc.conf.get("spark.driver.port").toInt,
      CoarseGrainedSchedulerBackend.ENDPOINT_NAME).toString
    val args = Seq(
      "--driver-url", driverUrl,
      "--executor-id", "{{EXECUTOR_ID}}",
      "--hostname", "{{HOSTNAME}}",
      "--cores", "{{CORES}}",
      "--app-id", "{{APP_ID}}",
      "--worker-url", "{{WORKER_URL}}")
    val extraJavaOpts = sc.conf.getOption("spark.executor.extraJavaOptions")
      .map(Utils.splitCommandString).getOrElse(Seq.empty)
    val classPathEntries = sc.conf.getOption("spark.executor.extraClassPath")
      .map(_.split(java.io.File.pathSeparator).toSeq).getOrElse(Nil)
    val libraryPathEntries = sc.conf.getOption("spark.executor.extraLibraryPath")
      .map(_.split(java.io.File.pathSeparator).toSeq).getOrElse(Nil)

    // When testing, expose the parent class path to the child. This is processed by
    // compute-classpath.{cmd,sh} and makes all needed jars available to child processes
    // when the assembly is built with the "*-provided" profiles enabled.
    val testingClassPath =
      if (sys.props.contains("spark.testing")) {
        sys.props("java.class.path").split(java.io.File.pathSeparator).toSeq
      } else {
        Nil
      }

    // Start executors with a few necessary configs for registering with the scheduler
    val sparkJavaOpts = Utils.sparkJavaOpts(conf, SparkConf.isExecutorStartupConf)
    val javaOpts = sparkJavaOpts ++ extraJavaOpts
    val command = Command("org.apache.spark.executor.CoarseGrainedExecutorBackend",
      args, sc.executorEnvs, classPathEntries ++ testingClassPath, libraryPathEntries, javaOpts)
    val webUrl = sc.ui.map(_.webUrl).getOrElse("")
    val coresPerExecutor = conf.getOption("spark.executor.cores").map(_.toInt)
    // If we're using dynamic allocation, set our initial executor limit to 0 for now.
    // ExecutorAllocationManager will send the real initial limit to the Master later.
    val initialExecutorLimit =
      if (Utils.isDynamicAllocationEnabled(conf)) {
        Some(0)
      } else {
        None
      }
    // 包含了application的名字，对executor的内存、核数的需求等信息。
    val appDesc = ApplicationDescription(sc.appName, maxCores, sc.executorMemory, command,
      webUrl, sc.eventLogDir, sc.eventLogCodec, coresPerExecutor, initialExecutorLimit)
    // 新建StandaloneAppClient
    client = new StandaloneAppClient(sc.env.rpcEnv, masters, appDesc, this, conf)
    // 启动StandaloneAppClient
    client.start()
    launcherBackend.setState(SparkAppHandle.State.SUBMITTED)
    waitForRegistration()
    launcherBackend.setState(SparkAppHandle.State.RUNNING)
  }
```
StandaloneAppClient.start方法
```scala
  def start() {
    // Just launch an rpcEndpoint; it will call back into the listener.
    // ClientEndpoint为StandaloneAppClient的内部类
    endpoint.set(rpcEnv.setupEndpoint("AppClient", new ClientEndpoint(rpcEnv)))
  }
  
  // endpoint的声明。AtomicReference， java原子性引用，待理解
  private val endpoint = new AtomicReference[RpcEndpointRef]
```
new ClientEndpoint时，会调用其onStart方法，向master发送RegisterApplication请求进行注册  

registered为ClientEndpoint维护的一个AtomicBoolean变量
```scala
    override def onStart(): Unit = {
      try {
        registerWithMaster(1)
      } catch {
        case e: Exception =>
          logWarning("Failed to connect to master", e)
          markDisconnected()
          stop()
      }
    }
    
    private def registerWithMaster(nthRetry: Int) {
      // 向所有的Master请求注册当前App
      registerMasterFutures.set(tryRegisterAllMasters())
      registrationRetryTimer.set(registrationRetryThread.schedule(new Runnable {
        override def run(): Unit = {
          if (registered.get) {
            // 一旦成功连接的一个master，其他请求将被取消
            registerMasterFutures.get.foreach(_.cancel(true))
            registerMasterThreadPool.shutdownNow()
          } else if (nthRetry >= REGISTRATION_RETRIES) {
            // 达到最大retry次数，连接失败
            markDead("All masters are unresponsive! Giving up.")
          } else {
            registerMasterFutures.get.foreach(_.cancel(true))
            registerWithMaster(nthRetry + 1)
          }
        }
      }, REGISTRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }
    
    private def tryRegisterAllMasters(): Array[JFuture[_]] = {
      for (masterAddress <- masterRpcAddresses) yield {
        registerMasterThreadPool.submit(new Runnable {
          override def run(): Unit = try {
            // 判断registered是否为true
            if (registered.get) {
              return
            }
            logInfo("Connecting to master " + masterAddress.toSparkURL + "...")
            val masterRef = rpcEnv.setupEndpointRef(masterAddress, Master.ENDPOINT_NAME)
            // 发送RegisterApplication请求
            masterRef.send(RegisterApplication(appDescription, self))
          } catch {
            case ie: InterruptedException => // Cancelled
            case NonFatal(e) => logWarning(s"Failed to connect to master $masterAddress", e)
          }
        })
      }
    }
```
StandaloneAppClient接收到RegisteredApplication后  
```scala
    override def receive: PartialFunction[Any, Unit] = {
      case RegisteredApplication(appId_, masterRef) =>
        // FIXME How to handle the following cases?
        // 1. A master receives multiple registrations and sends back multiple
        // RegisteredApplications due to an unstable network.
        // 2. Receive multiple RegisteredApplication from different masters because the master is
        // changing.
        // 设置appId
        appId.set(appId_)
        // 将registered设为true
        registered.set(true)
        // masterRef为RpcEndpointRef对象
        master = Some(masterRef)
        // listener为StandaloneAppClientListener对象
        listener.connected(appId.get)
        // ...
    }
```
再看看Master是如何处理RegisterApplication请求并返回RegisteredApplication
```scala
    case RegisterApplication(description, driver) =>
      // TODO Prevent repeated registrations from some driver
      if (state == RecoveryState.STANDBY) {
        // 若之前注册过，直接忽略
        // ignore, don't send response
      } else {
        logInfo("Registering app " + description.name)
        // 创建应用
        val app = createApplication(description, driver)
        // 注册app
        registerApplication(app)
        logInfo("Registered app " + description.name + " with ID " + app.id)
        // 持久化。待读
        persistenceEngine.addApplication(app)
        // 回复RegisteredApplication信号
        driver.send(RegisteredApplication(app.id, self))
        // 进行资源调度
        schedule()
      }
```
再单独分析createApplication，registerApplication，schedule等方法  

createApplication方法
```scala
  private def createApplication(desc: ApplicationDescription, driver: RpcEndpointRef):
      ApplicationInfo = {
    val now = System.currentTimeMillis()
    val date = new Date(now)
    // 根据当前时间生成appId
    val appId = newApplicationId(date)
    // 新建ApplicationInfo对象，其中维护了运行状态，executors等信息
    new ApplicationInfo(now, appId, desc, date, driver, defaultCores)
  }
```
registerApplication方法
```scala
  private def registerApplication(app: ApplicationInfo): Unit = {
    val appAddress = app.driver.address
    if (addressToApp.contains(appAddress)) {
      // addressToApp维护了appAddress与App的HashMap
      // HashMap[RpcAddress, ApplicationInfo]
      // 如果该appAddress已被使用，直接返回
      logInfo("Attempted to re-register application at same address: " + appAddress)
      return
    }
    
    // 向applicationMetricsSystem 注册appSource
    applicationMetricsSystem.registerSource(app.appSource)
    // apps为包含所有已注册app的HashSet
    apps += app
    // id与App的MashMap
    idToApp(app.id) = app
    // HashMap[RpcEndpointRef, ApplicationInfo]
    endpointToApp(app.driver) = app
    addressToApp(appAddress) = app
    // ArrayBuffer[ApplicationInfo]
    waitingApps += app
    // 待读
    if (reverseProxy) {
      webUi.addProxyTargets(app.id, app.desc.appUiUrl)
    }
  }
```
schedule方法  
Master注册应用时的最后一步调用了schedule()，该方法负责资源分配与调度
```scala
  /**
   * Schedule the currently available resources among waiting apps. This method will be called
   * every time a new app joins or resource availability changes.
   */
  private def schedule(): Unit = {
    if (state != RecoveryState.ALIVE) {
      return
    }
    // Drivers take strict precedence over executors
    // 获取可用Worker并打乱
    val shuffledAliveWorkers = Random.shuffle(workers.toSeq.filter(_.state == WorkerState.ALIVE))
    val numWorkersAlive = shuffledAliveWorkers.size
    var curPos = 0
    // Client在启动时通过ask发送RequestSubmitDriver请求，将driver添加到waitingDrivers数组中
    // 该调度策略为FIFO的策略，先注册的Driver先分配资源
    for (driver <- waitingDrivers.toList) { // iterate over a copy of waitingDrivers
      // We assign workers to each waiting driver in a round-robin fashion. For each driver, we
      // start from the last worker that was assigned a driver, and continue onwards until we have
      // explored all alive workers.
      var launched = false
      var numWorkersVisited = 0
      // 遍历ALIVE的worker，找到合适的worker启动driver
      while (numWorkersVisited < numWorkersAlive && !launched) {
        val worker = shuffledAliveWorkers(curPos)
        numWorkersVisited += 1
        // 空闲内存与核心数都满足driver的配置需求
        if (worker.memoryFree >= driver.desc.mem && worker.coresFree >= driver.desc.cores) {
          launchDriver(worker, driver)
          waitingDrivers -= driver
          launched = true
        }
        curPos = (curPos + 1) % numWorkersAlive
      }
    }
    // 启动Workers上的Executors，详见下文
    startExecutorsOnWorkers()
  }
  
  // launchDriver方法
  private def launchDriver(worker: WorkerInfo, driver: DriverInfo) {
    logInfo("Launching driver " + driver.id + " on worker " + worker.id)
    worker.addDriver(driver)
    driver.worker = Some(worker)
    // 
    worker.endpoint.send(LaunchDriver(driver.id, driver.desc))
    // 更改driver状态
    driver.state = DriverState.RUNNING
  }
  
  
  // WorkerInfo.addDriver方法
  def addDriver(driver: DriverInfo) {
    drivers(driver.id) = driver
    memoryUsed += driver.desc.mem
    coresUsed += driver.desc.cores
  }
```
startExecutorsOnWorkers方法  
在Workers上启动Executors
```scala
  /**
   * Schedule and launch executors on workers
   */
  private def startExecutorsOnWorkers(): Unit = {
    // Right now this is a very simple FIFO scheduler. We keep trying to fit in the first app
    // in the queue, then the second app, etc.
    // coresLeft定义 coresLeft: Int = requestedCores - coresGranted
    for (app <- waitingApps if app.coresLeft > 0) {
      val coresPerExecutor: Option[Int] = app.desc.coresPerExecutor
      // Filter out workers that don't have enough resources to launch an executor
      // 过滤掉资源不够启动executor的worker
      val usableWorkers = workers.toArray.filter(_.state == WorkerState.ALIVE)
        .filter(worker => worker.memoryFree >= app.desc.memoryPerExecutorMB &&
          worker.coresFree >= coresPerExecutor.getOrElse(1))
        .sortBy(_.coresFree).reverse
      // 确定在每个worker上给这个app分配多少核，详见下文
      val assignedCores = scheduleExecutorsOnWorkers(app, usableWorkers, spreadOutApps)

      // Now that we've decided how many cores to allocate on each worker, let's allocate them
      // 为每个Executor分配资源
      for (pos <- 0 until usableWorkers.length if assignedCores(pos) > 0) {
        allocateWorkerResourceToExecutors(
          app, assignedCores(pos), coresPerExecutor, usableWorkers(pos))
      }
    }
  }
```
scheduleExecutorsOnWorkers方法
```scala
  private def scheduleExecutorsOnWorkers(
      app: ApplicationInfo,
      usableWorkers: Array[WorkerInfo],
      spreadOutApps: Boolean): Array[Int] = {
    val coresPerExecutor = app.desc.coresPerExecutor
    val minCoresPerExecutor = coresPerExecutor.getOrElse(1)
    val oneExecutorPerWorker = coresPerExecutor.isEmpty
    val memoryPerExecutor = app.desc.memoryPerExecutorMB
    val numUsable = usableWorkers.length
    val assignedCores = new Array[Int](numUsable) // Number of cores to give to each worker
    val assignedExecutors = new Array[Int](numUsable) // Number of new executors on each worker
    // 剩余core数 
    var coresToAssign = math.min(app.coresLeft, usableWorkers.map(_.coresFree).sum)

    /** Return whether the specified worker can launch an executor for this app. */
    // 判断是否能启动Executor
    /*判断当前的这个worker是否可以用来启动一个当前app的executor*/
    /**
      * 1.主要判断是否还有core没有被分配
      * 2.判断worker上的core的数量是否充足
      */
    def canLaunchExecutor(pos: Int): Boolean = {
      // 判断 剩余core >= minCoresPerExecutor
      val keepScheduling = coresToAssign >= minCoresPerExecutor
      // 判断 该Worker上的剩余core >= spark.executor.cores
      val enoughCores = usableWorkers(pos).coresFree - assignedCores(pos) >= minCoresPerExecutor
      // If we allow multiple executors per worker, then we can always launch new executors.
      // Otherwise, if there is already an executor on this worker, just give it more cores.
      val launchingNewExecutor = !oneExecutorPerWorker || assignedExecutors(pos) == 0
      // 如果是新建一个executor.判断worker上的内存是否足够，判断executor的数量是否达到了上限
      if (launchingNewExecutor) {
        val assignedMemory = assignedExecutors(pos) * memoryPerExecutor
        // 判断 该Worker上的剩余内存 >= spark.executor.memory
        val enoughMemory = usableWorkers(pos).memoryFree - assignedMemory >= memoryPerExecutor
        // 判断 若分配了该executor后，总共分配的executor数量 <= app executor限制
        val underLimit = assignedExecutors.sum + app.executors.size < app.executorLimit
        keepScheduling && enoughCores && enoughMemory && underLimit
      } else {
        // We're adding cores to an existing executor, so no need
        // to check memory and executor limits
        // 如果是向已经存在的executor上添加资源，只需要判断keepScheduling与enoughCores
        keepScheduling && enoughCores
      }
    }
    
    // Keep launching executors until no more workers can accommodate any
    // more executors, or if we have reached this application's limits
    // 筛选出可以启动executor的worker
    var freeWorkers = (0 until numUsable).filter(canLaunchExecutor)
    // 一个app会尽可能的使用掉集群的所有资源，所以设置spark.cores.max参数是非常有必要的！
    while (freeWorkers.nonEmpty) {
      freeWorkers.foreach { pos =>
        var keepScheduling = true
        while (keepScheduling && canLaunchExecutor(pos)) {
          coresToAssign -= minCoresPerExecutor
          assignedCores(pos) += minCoresPerExecutor

          // If we are launching one executor per worker, then every iteration assigns 1 core
          // to the executor. Otherwise, every iteration assigns cores to a new executor.
          if (oneExecutorPerWorker) {
            assignedExecutors(pos) = 1
          } else {
            assignedExecutors(pos) += 1
          }

          // Spreading out an application means spreading out its executors across as
          // many workers as possible. If we are not spreading out, then we should keep
          // scheduling executors on this worker until we use all of its resources.
          // Otherwise, just move on to the next worker.
          // spreadOutApps定义
          // private val spreadOutApps = conf.getBoolean("spark.deploy.spreadOut", true)
          // 如果spreadOutApps为false，worker的所有剩余资源都将被分配到该app上
          // 该方式下，资源会较集中分配，尽量分配到一个节点上
          if (spreadOutApps) {
            keepScheduling = false
          }
        }
      }
      freeWorkers = freeWorkers.filter(canLaunchExecutor)
    }
    // 返回cores分配比例
    assignedCores
  }
```
startExecutorsOnWorkers方法接收到scheduleExecutorsOnWorkers传回的数组后，  
将调用allocateWorkerResourceToExecutors方法进行真正的资源分配
```scala
  /**
   * Allocate a worker's resources to one or more executors.
   * @param app the info of the application which the executors belong to
   * @param assignedCores number of cores on this worker for this application
   * @param coresPerExecutor number of cores per executor
   * @param worker the worker info
   */
  private def allocateWorkerResourceToExecutors(
      app: ApplicationInfo,
      assignedCores: Int,
      coresPerExecutor: Option[Int],
      worker: WorkerInfo): Unit = {
    // If the number of cores per executor is specified, we divide the cores assigned
    // to this worker evenly among the executors with no remainder.
    // Otherwise, we launch a single executor that grabs all the assignedCores on this worker.
    val numExecutors = coresPerExecutor.map { assignedCores / _ }.getOrElse(1)
    val coresToAssign = coresPerExecutor.getOrElse(assignedCores)
    for (i <- 1 to numExecutors) {
      val exec = app.addExecutor(worker, coresToAssign)
      launchExecutor(worker, exec)
      app.state = ApplicationState.RUNNING
    }
  }


  private def launchExecutor(worker: WorkerInfo, exec: ExecutorDesc): Unit = {
    logInfo("Launching executor " + exec.fullId + " on worker " + worker.id)
    worker.addExecutor(exec)
    // 给worker发送LaunchExecutor信号
    worker.endpoint.send(LaunchExecutor(masterUrl,
      exec.application.id, exec.id, exec.application.desc, exec.cores, exec.memory))
    // 给StandaloneAppClient发送ExecutorAdded信号
    exec.application.driver.send(
      ExecutorAdded(exec.id, worker.id, worker.hostPort, exec.cores, exec.memory))
  }
```
Worker收到LauchExecutor后，将如下进行处理
```scala
    case LaunchExecutor(masterUrl, appId, execId, appDesc, cores_, memory_) =>
      if (masterUrl != activeMasterUrl) {
        logWarning("Invalid Master (" + masterUrl + ") attempted to launch executor.")
      } else {
        try {
          logInfo("Asked to launch executor %s/%d for %s".format(appId, execId, appDesc.name))

          // Create the executor's working directory
          val executorDir = new File(workDir, appId + "/" + execId)
          if (!executorDir.mkdirs()) {
            throw new IOException("Failed to create directory " + executorDir)
          }

          // Create local dirs for the executor. These are passed to the executor via the
          // SPARK_EXECUTOR_DIRS environment variable, and deleted by the Worker when the
          // application finishes.
          val appLocalDirs = appDirectories.getOrElse(appId, {
            val localRootDirs = Utils.getOrCreateLocalRootDirs(conf)
            val dirs = localRootDirs.flatMap { dir =>
              try {
                val appDir = Utils.createDirectory(dir, namePrefix = "executor")
                Utils.chmod700(appDir)
                Some(appDir.getAbsolutePath())
              } catch {
                case e: IOException =>
                  logWarning(s"${e.getMessage}. Ignoring this directory.")
                  None
              }
            }.toSeq
            if (dirs.isEmpty) {
              throw new IOException("No subfolder can be created in " +
                s"${localRootDirs.mkString(",")}.")
            }
            dirs
          })
          appDirectories(appId) = appLocalDirs
          // 一个ExecutorRunner用来管理一个executor的运行
          val manager = new ExecutorRunner(
            appId,
            execId,
            appDesc.copy(command = Worker.maybeUpdateSSLSettings(appDesc.command, conf)),
            cores_,
            memory_,
            self,
            workerId,
            host,
            webUi.boundPort,
            publicAddress,
            sparkHome,
            executorDir,
            workerUri,
            conf,
            appLocalDirs, ExecutorState.RUNNING)
          executors(appId + "/" + execId) = manager
          // manager.start()方法，真正启动executor
          manager.start()
          coresUsed += cores_
          memoryUsed += memory_
          // 向Master发送ExecutorStateChanged信号
          sendToMaster(ExecutorStateChanged(appId, execId, manager.state, None, None))
        } catch {
          case e: Exception =>
            logError(s"Failed to launch executor $appId/$execId for ${appDesc.name}.", e)
            if (executors.contains(appId + "/" + execId)) {
              executors(appId + "/" + execId).kill()
              executors -= appId + "/" + execId
            }
            sendToMaster(ExecutorStateChanged(appId, execId, ExecutorState.FAILED,
              Some(e.toString), None))
        }
      }
```
ExecutorRunner.start()方法
```scala
  private[worker] def start() {
    // 创建worker线程
    workerThread = new Thread("ExecutorRunner for " + fullId) {
      // fetchAndRunExecutor详见下文
      override def run() { fetchAndRunExecutor() }
    }
    // 启动该线程
    workerThread.start()
    // Shutdown hook that kills actors on shutdown.
    // worker关闭时，杀掉executor
    shutdownHook = ShutdownHookManager.addShutdownHook { () =>
      // It's possible that we arrive here before calling `fetchAndRunExecutor`, then `state` will
      // be `ExecutorState.RUNNING`. In this case, we should set `state` to `FAILED`.
      if (state == ExecutorState.RUNNING) {
        state = ExecutorState.FAILED
      }
      killProcess(Some("Worker shutting down")) }
  }
```
fetchAndRunExecutor方法
```scala
  /**
   * Download and run the executor described in our ApplicationDescription
   */
  private def fetchAndRunExecutor() {
    try {
      // Launch the process
      val builder = CommandUtils.buildProcessBuilder(appDesc.command, new SecurityManager(conf),
        memory, sparkHome.getAbsolutePath, substituteVariables)
      val command = builder.command()
      val formattedCommand = command.asScala.mkString("\"", "\" \"", "\"")
      logInfo(s"Launch command: $formattedCommand")

      builder.directory(executorDir)
      builder.environment.put("SPARK_EXECUTOR_DIRS", appLocalDirs.mkString(File.pathSeparator))
      // In case we are running this from within the Spark Shell, avoid creating a "scala"
      // parent process for the executor command
      builder.environment.put("SPARK_LAUNCH_WITH_SCALA", "0")

      // Add webUI log urls
      val baseUrl =
        if (conf.getBoolean("spark.ui.reverseProxy", false)) {
          s"/proxy/$workerId/logPage/?appId=$appId&executorId=$execId&logType="
        } else {
          s"http://$publicAddress:$webUiPort/logPage/?appId=$appId&executorId=$execId&logType="
        }
      builder.environment.put("SPARK_LOG_URL_STDERR", s"${baseUrl}stderr")
      builder.environment.put("SPARK_LOG_URL_STDOUT", s"${baseUrl}stdout")

      process = builder.start()
      val header = "Spark Executor Command: %s\n%s\n\n".format(
        formattedCommand, "=" * 40)

      // Redirect its stdout and stderr to files
      val stdout = new File(executorDir, "stdout")
      stdoutAppender = FileAppender(process.getInputStream, stdout, conf)

      val stderr = new File(executorDir, "stderr")
      Files.write(header, stderr, StandardCharsets.UTF_8)
      stderrAppender = FileAppender(process.getErrorStream, stderr, conf)

      // Wait for it to exit; executor may exit with code 0 (when driver instructs it to shutdown)
      // or with nonzero exit code
      val exitCode = process.waitFor()
      state = ExecutorState.EXITED
      val message = "Command exited with code " + exitCode
      worker.send(ExecutorStateChanged(appId, execId, state, Some(message), Some(exitCode)))
    } catch {
      case interrupted: InterruptedException =>
        logInfo("Runner thread for executor " + fullId + " interrupted")
        state = ExecutorState.KILLED
        killProcess(None)
      case e: Exception =>
        logError("Error running executor", e)
        state = ExecutorState.FAILED
        killProcess(Some(e.toString))
    }
  }
```
StandaloneAppClient接收到ExecutorAdded信号后
```scala
      case ExecutorAdded(id: Int, workerId: String, hostPort: String, cores: Int, memory: Int) =>
        val fullId = appId + "/" + id
        logInfo("Executor added: %s on %s (%s) with %d cores".format(fullId, workerId, hostPort,
          cores))
        listener.executorAdded(fullId, workerId, hostPort, cores, memory)
```