TaskScheduler的方法在子类TaskSchedulerImpl中实现
```scala
/**
 * Low-level task scheduler interface, currently implemented exclusively by
 * [[org.apache.spark.scheduler.TaskSchedulerImpl]].
 * This interface allows plugging in different task schedulers. Each TaskScheduler schedules tasks
 * for a single SparkContext. These schedulers get sets of tasks submitted to them from the
 * DAGScheduler for each stage, and are responsible for sending the tasks to the cluster, running
 * them, retrying if there are failures, and mitigating stragglers. They return events to the
 * DAGScheduler.
 */
private[spark] trait TaskScheduler {

  private val appId = "spark-application-" + System.currentTimeMillis

  def rootPool: Pool

  def schedulingMode: SchedulingMode

  def start(): Unit

  // Invoked after system has successfully initialized (typically in spark context).
  // Yarn uses this to bootstrap allocation of resources based on preferred locations,
  // wait for slave registrations, etc.
  def postStartHook() { }

  // Disconnect from the cluster.
  def stop(): Unit

  // Submit a sequence of tasks to run.
  def submitTasks(taskSet: TaskSet): Unit

  // Cancel a stage.
  def cancelTasks(stageId: Int, interruptThread: Boolean): Unit

  /**
   * Kills a task attempt.
   *
   * @return Whether the task was successfully killed.
   */
  def killTaskAttempt(taskId: Long, interruptThread: Boolean, reason: String): Boolean

  // Set the DAG scheduler for upcalls. This is guaranteed to be set before submitTasks is called.
  def setDAGScheduler(dagScheduler: DAGScheduler): Unit

  // Get the default level of parallelism to use in the cluster, as a hint for sizing jobs.
  def defaultParallelism(): Int

  /**
   * Update metrics for in-progress tasks and let the master know that the BlockManager is still
   * alive. Return true if the driver knows about the given block manager. Otherwise, return false,
   * indicating that the block manager should re-register.
   */
  def executorHeartbeatReceived(
      execId: String,
      accumUpdates: Array[(Long, Seq[AccumulatorV2[_, _]])],
      blockManagerId: BlockManagerId): Boolean

  /**
   * Get an application ID associated with the job.
   *
   * @return An application ID
   */
  def applicationId(): String = appId

  /**
   * Process a lost executor
   */
  def executorLost(executorId: String, reason: ExecutorLossReason): Unit

  /**
   * Get an application's attempt ID associated with the job.
   *
   * @return An application's Attempt ID
   */
  def applicationAttemptId(): Option[String]

}
```
在DAGScheduler中，调用了taskScheduler.submitTasks方法来提交TaskSet
```scala
    if (tasks.size > 0) {
      logInfo(s"Submitting ${tasks.size} missing tasks from $stage (${stage.rdd}) (first 15 " +
        s"tasks are for partitions ${tasks.take(15).map(_.partitionId)})")
      // 调用TaskScheduler的submitTasks，提交TaskSet
      taskScheduler.submitTasks(new TaskSet(
        tasks.toArray, stage.id, stage.latestInfo.attemptId, jobId, properties))
      stage.latestInfo.submissionTime = Some(clock.getTimeMillis())
    }
```
TaskSchedulerImpl类中的submitTasks方法
```scala
  override def submitTasks(taskSet: TaskSet) {
    // 获取TaskSet中的所有task
    val tasks = taskSet.tasks
    logInfo("Adding task set " + taskSet.id + " with " + tasks.length + " tasks")
    this.synchronized {
      // 创建TaskSetManager，TaskSetManager负责管理TaskSchedulerImpl中一个单独TaskSet，
      // 跟踪每一个task，如果task失败，负责重试task直到达到task重试次数的最多次数。
      // 并且通过延迟调度来执行task的位置感知调度。
      val manager = createTaskSetManager(taskSet, maxTaskFailures)
      val stage = taskSet.stageId
      val stageTaskSets =
        taskSetsByStageIdAndAttempt.getOrElseUpdate(stage, new HashMap[Int, TaskSetManager])
      stageTaskSets(taskSet.stageAttemptId) = manager
      
      // 确保一个stage不能有两个taskSet同时运行。
      // isZombie是TaskSetManager中所有tasks不需要执行（成功执行或者stage被删除）的一个标记，
      // 如果该TaskSet没有被完全执行并且已经存在和新进来的taskset一样的另一个TaskSet，则抛出异常。
      val conflictingTaskSet = stageTaskSets.exists { case (_, ts) =>
        ts.taskSet != taskSet && !ts.isZombie
      }
      if (conflictingTaskSet) {
        throw new IllegalStateException(s"more than one active taskSet for stage $stage:" +
          s" ${stageTaskSets.toSeq.map{_._2.taskSet.id}.mkString(",")}")
      }
      // 将TaskSetManger加入schedulableBuilder
      schedulableBuilder.addTaskSetManager(manager, manager.taskSet.properties)

      if (!isLocal && !hasReceivedTask) {
        starvationTimer.scheduleAtFixedRate(new TimerTask() {
          override def run() {
            if (!hasLaunchedTask) {
              logWarning("Initial job has not accepted any resources; " +
                "check your cluster UI to ensure that workers are registered " +
                "and have sufficient resources")
            } else {
              this.cancel()
            }
          }
        }, STARVATION_TIMEOUT_MS, STARVATION_TIMEOUT_MS)
      }
      hasReceivedTask = true
    }
    // 向schedulerBackend申请资源
    backend.reviveOffers()
  }
  
  
  // TaskSetManager定义 
  private[spark] class TaskSetManager(
    sched: TaskSchedulerImpl,
    val taskSet: TaskSet,
    // Task失败最大重试次数
    val maxTaskFailures: Int,
    blacklistTracker: Option[BlacklistTracker] = None,
    clock: Clock = new SystemClock()) extends Schedulable with Logging
    
    
  // taskSetsByStageIdAndAttempt定义
  // TaskSetManagers are not thread safe, so any access to one should be synchronized
  // on this class.
  // key为stageId，value为一个HashMap，
  // 其中value的key为stageAttemptId，value为TaskSet。
  private val taskSetsByStageIdAndAttempt = new HashMap[Int, HashMap[Int, TaskSetManager]]
  
  
  // schedulableBuilder的定义
  // schedulableBuilder的类型是SchedulerBuilder，SchedulerBuilder是一个trait
  // schedulableBuilder有两个实现FIFOSchedulerBuilder和 FairSchedulerBuilder
  private var schedulableBuilder: SchedulableBuilder = null
  // 默认调度方式为FIFO
  // default scheduler is FIFO
  private val schedulingModeConf = conf.get(SCHEDULER_MODE_PROPERTY, SchedulingMode.FIFO.toString)
  
  
  // 
private[spark] trait SchedulableBuilder {
  def rootPool: Pool

  def buildPools(): Unit

  def addTaskSetManager(manager: Schedulable, properties: Properties): Unit
}


  // SchedulerBuilder的FIFO实现
private[spark] class FIFOSchedulableBuilder(val rootPool: Pool)
  extends SchedulableBuilder with Logging {

  override def buildPools() {
    // nothing
  }

  override def addTaskSetManager(manager: Schedulable, properties: Properties) {
    // rootPool调度池
    rootPool.addSchedulable(manager)
  }
}

  // addSchedulable方法
  override def addSchedulable(schedulable: Schedulable) {
    require(schedulable != null)
    schedulableQueue.add(schedulable)
    schedulableNameToSchedulable.put(schedulable.name, schedulable)
    schedulable.parent = this
  }
```
addSchedulable方法
```scala
  override def addSchedulable(schedulable: Schedulable) {
    require(schedulable != null)
    schedulableQueue.add(schedulable)
    schedulableNameToSchedulable.put(schedulable.name, schedulable)
    // 将manager的parent设置为该rootPool
    schedulable.parent = this
  }
```
schedulableBuilder是SparkContext 中newTaskSchedulerImpl(sc)在创建TaskSchedulerImpl的时候通过scheduler.initialize(backend)的initialize方法进行实例化的。
```scala
  // 初始化调度池
  val rootPool: Pool = new Pool("", schedulingMode, 0, 0)
  
  // initialize方法
  def initialize(backend: SchedulerBackend) {
    this.backend = backend
    schedulableBuilder = {
      schedulingMode match {
        case SchedulingMode.FIFO =>
          new FIFOSchedulableBuilder(rootPool)
        case SchedulingMode.FAIR =>
          new FairSchedulableBuilder(rootPool, conf)
        case _ =>
          throw new IllegalArgumentException(s"Unsupported $SCHEDULER_MODE_PROPERTY: " +
          s"$schedulingMode")
      }
    }
    schedulableBuilder.buildPools()
  }
```
在粗粒度调度模式下，backend.reviveOffers()调用的是CoarseGrainedSchedulerBackend的reviveOffers()方法（待理解）
```scala
  override def reviveOffers() {
    driverEndpoint.send(ReviveOffers)
  }
```
driverEndpoint收到ReviveOffer消息后将调用makeOffers方法
```scala
    override def receive: PartialFunction[Any, Unit] = {
      case StatusUpdate(executorId, taskId, state, data) =>
        scheduler.statusUpdate(taskId, state, data.value)
        if (TaskState.isFinished(state)) {
          executorDataMap.get(executorId) match {
            case Some(executorInfo) =>
              executorInfo.freeCores += scheduler.CPUS_PER_TASK
              makeOffers(executorId)
            case None =>
              // Ignoring the update since we don't know about the executor.
              logWarning(s"Ignored task status update ($taskId state $state) " +
                s"from unknown executor with ID $executorId")
          }
        }

      case ReviveOffers =>
        makeOffers()
      // ...
    }  
    
    
    // makeOffers方法
    // Make fake resource offers on all executors
    private def makeOffers() {
      // Make sure no executor is killed while some task is launching on it
      val taskDescs = CoarseGrainedSchedulerBackend.this.synchronized {
        // Filter out executors under killing
        val activeExecutors = executorDataMap.filterKeys(executorIsAlive)
        // 将Executor封装成WorkerOffer对象
        val workOffers = activeExecutors.map { case (id, executorData) =>
          new WorkerOffer(id, executorData.executorHost, executorData.freeCores)
        }.toIndexedSeq
        // master向workers分配资源，以轮偱调度的方式将tasks分配到各个节点上，
        // 来做到各个节点上的tasks负载均衡
        scheduler.resourceOffers(workOffers)
      }
      if (!taskDescs.isEmpty) {
        launchTasks(taskDescs)
      }
    }
```
再重点看resourceOffers方法
```scala
  def resourceOffers(offers: IndexedSeq[WorkerOffer]): Seq[Seq[TaskDescription]] = synchronized {
    // Mark each slave as alive and remember its hostname
    // Also track if new executor is added
    // 标记是否有新的executor加入
    var newExecAvail = false
    for (o <- offers) {
      // 更新host信息
      if (!hostToExecutors.contains(o.host)) {
        hostToExecutors(o.host) = new HashSet[String]()
      }
      // 更新executor信息
      if (!executorIdToRunningTaskIds.contains(o.executorId)) {
        hostToExecutors(o.host) += o.executorId
        executorAdded(o.executorId, o.host)
        executorIdToHost(o.executorId) = o.host
        executorIdToRunningTaskIds(o.executorId) = HashSet[Long]()
        newExecAvail = true
      }
      // 更新rack信息（不理解）
      for (rack <- getRackForHost(o.host)) {
        hostsByRack.getOrElseUpdate(rack, new HashSet[String]()) += o.host
      }
    }
 
    // Before making any offers, remove any nodes from the blacklist whose blacklist has expired. Do
    // this here to avoid a separate thread and added synchronization overhead, and also because
    // updating the blacklist is only relevant when task offers are being made.
    blacklistTrackerOpt.foreach(_.applyBlacklistTimeout())

    val filteredOffers = blacklistTrackerOpt.map { blacklistTracker =>
      offers.filter { offer =>
        !blacklistTracker.isNodeBlacklisted(offer.host) &&
          !blacklistTracker.isExecutorBlacklisted(offer.executorId)
      }
    }.getOrElse(offers)
    
    // 随机打乱offers，避免多个task集中分配到某些节点上，为了负载均衡
    val shuffledOffers = shuffleOffers(filteredOffers)
    // Build a list of tasks to assign to each worker.
    // 建一个二维数组，保存每个Executor上将要分配的那些tas
    val tasks = shuffledOffers.map(o => new ArrayBuffer[TaskDescription](o.cores))
    // 每个executor上可用的cpu核心数
    val availableCpus = shuffledOffers.map(o => o.cores).toArray
    // 从调度池中获取排序过的TaskSet
    // 详见下文
    val sortedTaskSets = rootPool.getSortedTaskSetQueue
    for (taskSet <- sortedTaskSets) {
      logDebug("parentName: %s, name: %s, runningTasks: %s".format(
        taskSet.parent.name, taskSet.name, taskSet.runningTasks))
      if (newExecAvail) {
        taskSet.executorAdded()
      }
    }

    // Take each TaskSet in our scheduling order, and then offer it each node in increasing order
    // of locality levels so that it gets a chance to launch local tasks on all of them.
    // NOTE: the preferredLocality order: PROCESS_LOCAL, NODE_LOCAL, NO_PREF, RACK_LOCAL, ANY
    // 依次按照本地性级别顺序尝试启动task
    // 根据taskSet及locality遍历所有可用的executor，找出可以在各个executor上启动的task， 
    // 加到tasks:Seq[Seq[TaskDescription]]中
    // 数据本地性级别顺序：PROCESS_LOCAL, NODE_LOCAL, NO_PREF, RACK_LOCAL, ANY
    for (taskSet <- sortedTaskSets) {
      var launchedAnyTask = false
      var launchedTaskAtCurrentMaxLocality = false
      // 按照就近原则分配
      for (currentMaxLocality <- taskSet.myLocalityLevels) {
        do {
          //resourceOfferSingleTaskSet为单个TaskSet分配资源，
          //若该LocalityLevel的节点下不能再为之分配资源了，
          //则返回false         
          launchedTaskAtCurrentMaxLocality = resourceOfferSingleTaskSet(
            taskSet, currentMaxLocality, shuffledOffers, availableCpus, tasks)
          launchedAnyTask |= launchedTaskAtCurrentMaxLocality
        } while (launchedTaskAtCurrentMaxLocality)
      }
      if (!launchedAnyTask) {
        taskSet.abortIfCompletelyBlacklisted(hostToExecutors)
      }
    }

    if (tasks.size > 0) {
      hasLaunchedTask = true
    }
    return tasks
  }
```
resourceOfferSingleTaskSet方法
```scala
  private def resourceOfferSingleTaskSet(
      taskSet: TaskSetManager,
      maxLocality: TaskLocality,
      shuffledOffers: Seq[WorkerOffer],
      availableCpus: Array[Int],
      tasks: IndexedSeq[ArrayBuffer[TaskDescription]]) : Boolean = {
    var launchedTask = false
    // nodes and executors that are blacklisted for the entire application have already been
    // filtered out by this point
    // 遍历各个executor
    for (i <- 0 until shuffledOffers.size) {
      val execId = shuffledOffers(i).executorId
      val host = shuffledOffers(i).host
      if (availableCpus(i) >= CPUS_PER_TASK) {
        try {
        // TaskSetManager类的resourceOffer方法（待读）
          for (task <- taskSet.resourceOffer(execId, host, maxLocality)) {
            tasks(i) += task
            val tid = task.taskId
            taskIdToTaskSetManager(tid) = taskSet
            taskIdToExecutorId(tid) = execId
            executorIdToRunningTaskIds(execId).add(tid)
            availableCpus(i) -= CPUS_PER_TASK
            // 再次检查Task是否被启动了
            assert(availableCpus(i) >= 0)
            launchedTask = true
          }
        } catch {
          case e: TaskNotSerializableException =>
            logError(s"Resource offer failed, task set ${taskSet.name} was not serializable")
            // Do not offer resources for this task, but don't throw an error to allow other
            // task sets to be submitted.
            return launchedTask
        }
      }
    }
    return launchedTask
  }
```
 TaskSetManager类的resourceOffer方法
```scala
  /**
   * Respond to an offer of a single executor from the scheduler by finding a task
   *
   * NOTE: this function is either called with a maxLocality which
   * would be adjusted by delay scheduling algorithm or it will be with a special
   * NO_PREF locality which will be not modified
   *
   * @param execId the executor Id of the offered resource
   * @param host  the host Id of the offered resource
   * @param maxLocality the maximum locality we want to schedule the tasks at
   */
  @throws[TaskNotSerializableException]
  def resourceOffer(
      execId: String,
      host: String,
      maxLocality: TaskLocality.TaskLocality)
    : Option[TaskDescription] =
  {
    val offerBlacklisted = taskSetBlacklistHelperOpt.exists { blacklist =>
      blacklist.isNodeBlacklistedForTaskSet(host) ||
        blacklist.isExecutorBlacklistedForTaskSet(execId)
    }
    if (!isZombie && !offerBlacklisted) {
      val curTime = clock.getTimeMillis()

      var allowedLocality = maxLocality

      if (maxLocality != TaskLocality.NO_PREF) {
        allowedLocality = getAllowedLocalityLevel(curTime)
        if (allowedLocality > maxLocality) {
          // We're not allowed to search for farther-away tasks
          allowedLocality = maxLocality
        }
      }

      dequeueTask(execId, host, allowedLocality).map { case ((index, taskLocality, speculative)) =>
        // Found a task; do some bookkeeping and return a task description
        val task = tasks(index)
        val taskId = sched.newTaskId()
        // Do various bookkeeping
        copiesRunning(index) += 1
        val attemptNum = taskAttempts(index).size
        val info = new TaskInfo(taskId, index, attemptNum, curTime,
          execId, host, taskLocality, speculative)
        taskInfos(taskId) = info
        taskAttempts(index) = info :: taskAttempts(index)
        // Update our locality level for delay scheduling
        // NO_PREF will not affect the variables related to delay scheduling
        if (maxLocality != TaskLocality.NO_PREF) {
          currentLocalityIndex = getLocalityIndex(taskLocality)
          lastLaunchTime = curTime
        }
        // Serialize and return the task
        val serializedTask: ByteBuffer = try {
          ser.serialize(task)
        } catch {
          // If the task cannot be serialized, then there's no point to re-attempt the task,
          // as it will always fail. So just abort the whole task-set.
          case NonFatal(e) =>
            val msg = s"Failed to serialize task $taskId, not attempting to retry it."
            logError(msg, e)
            abort(s"$msg Exception during serialization: $e")
            throw new TaskNotSerializableException(e)
        }
        if (serializedTask.limit > TaskSetManager.TASK_SIZE_TO_WARN_KB * 1024 &&
          !emittedTaskSizeWarning) {
          emittedTaskSizeWarning = true
          logWarning(s"Stage ${task.stageId} contains a task of very large size " +
            s"(${serializedTask.limit / 1024} KB). The maximum recommended task size is " +
            s"${TaskSetManager.TASK_SIZE_TO_WARN_KB} KB.")
        }
        addRunningTask(taskId)

        // We used to log the time it takes to serialize the task, but task size is already
        // a good proxy to task serialization time.
        // val timeTaken = clock.getTime() - startTime
        val taskName = s"task ${info.id} in stage ${taskSet.id}"
        logInfo(s"Starting $taskName (TID $taskId, $host, executor ${info.executorId}, " +
          s"partition ${task.partitionId}, $taskLocality, ${serializedTask.limit} bytes)")

        sched.dagScheduler.taskStarted(task, info)
        new TaskDescription(
          taskId,
          attemptNum,
          execId,
          taskName,
          index,
          sched.sc.addedFiles,
          sched.sc.addedJars,
          task.localProperties,
          serializedTask)
      }
    } else {
      None
    }
  }
```
回到CoarseGrainedSchedulerBackend.scala的makeOffers方法，最后调用到了launchTasks(taskDescs)方法来发送任务  
先将task进行序列化， 如果当前task序列化后的大小超过了128MB-200KB，跳过当前task，并把对应的taskSetManager置为zombie模式，若大小不超过限制，则发送消息到executor启动task执行。
```scala
    // Launch tasks returned by a set of resource offers
    private def launchTasks(tasks: Seq[Seq[TaskDescription]]) {
      for (task <- tasks.flatten) {
        val serializedTask = TaskDescription.encode(task)
        if (serializedTask.limit >= maxRpcMessageSize) {
          scheduler.taskIdToTaskSetManager.get(task.taskId).foreach { taskSetMgr =>
            try {
              var msg = "Serialized task %s:%d was %d bytes, which exceeds max allowed: " +
                "spark.rpc.message.maxSize (%d bytes). Consider increasing " +
                "spark.rpc.message.maxSize or using broadcast variables for large values."
              msg = msg.format(task.taskId, task.index, serializedTask.limit, maxRpcMessageSize)
              taskSetMgr.abort(msg)
            } catch {
              case e: Exception => logError("Exception in error callback", e)
            }
          }
        }
        else {
          // 减少改task所对应的executor信息的core数量
          val executorData = executorDataMap(task.executorId)
          executorData.freeCores -= scheduler.CPUS_PER_TASK

          logDebug(s"Launching task ${task.taskId} on executor id: ${task.executorId} hostname: " +
            s"${executorData.executorHost}.")
            
          // 向executorEndpoint 发送LaunchTask信号
          executorData.executorEndpoint.send(LaunchTask(new SerializableBuffer(serializedTask)))
        }
      }
    }
```
executorEndpoint接收到LaunchTask信号（包含SerializableBuffer(serializedTask) ）后，会开始执行任务。  
再看看上文中的val sortedTaskSets = rootPool.getSortedTaskSetQueue，从调度池中获取排序过的TaskSet。
```scala
  override def getSortedTaskSetQueue: ArrayBuffer[TaskSetManager] = {
    var sortedTaskSetQueue = new ArrayBuffer[TaskSetManager]
    // 进行排序
    val sortedSchedulableQueue =
      schedulableQueue.asScala.toSeq.sortWith(taskSetSchedulingAlgorithm.comparator)
    for (schedulable <- sortedSchedulableQueue) {
      sortedTaskSetQueue ++= schedulable.getSortedTaskSetQueue
    }
    sortedTaskSetQueue
  }
```
taskSetSchedulingAlgorithm是在创建Pool时初始化的，根据传入的schedulingMode参数确定调度模式。（前文提到过，Pool是在initialize方法中初始化的）
```scala
  private val taskSetSchedulingAlgorithm: SchedulingAlgorithm = {
    schedulingMode match {
      case SchedulingMode.FAIR =>
        new FairSchedulingAlgorithm()
      case SchedulingMode.FIFO =>
        new FIFOSchedulingAlgorithm()
      case _ =>
        val msg = s"Unsupported scheduling mode: $schedulingMode. Use FAIR or FIFO instead."
        throw new IllegalArgumentException(msg)
    }
  }
```
总体来说，Standalone模式下，Driver获得资源的过程如下
1. 在SparkContext实例化的时候调用createTaskScheduler来创建TaskSchedulerImpl和SparkDeploySchedulerBackend
1. 同时在SparkContext实例化的时候会调用TaskSchedulerImpl的start，在start方法中会调用SparkDeploySchedulerBackend的start，在该start方法中会创建AppClient对象并调用AppClient对象的start方法
1. 在AppClient对象的start方法中会创建ClientEndpoint，在创建ClientEndpoint会传入Command来指定具体为当前应用程序启动的Executor进行的入口类的名称为CoarseGrainedExecutorBackend
1. 然后ClientEndpoint启动并通过tryRegisterMaster来注册当前的应用
1. 程序到Master中，Master接受到注册信息后如何可以运行程序，则会为该程序生产Job ID并通过schedule来分配计算资源，具体计算资源的分配是通过应用程序的运行方式、Memory、cores等配置信息来决定的
1. 最后Master会发送指令给Worker，Worker中为当前应用程序分配计算资源时会首先分配ExecutorRunner，ExecutorRunner内部会通过Thread的方式构建ProcessBuilder来启动另外一个JVM进程，这个JVM进程启动时候加载的main方法所在的类的名称就是在创建ClientEndpoint时传入的Command来指定具体名称为CoarseGrainedExecutorBackend的类，
1. 此时JVM在通过ProcessBuilder启动的时候获得了CoarseGrainedExecutorBackend后加载并调用其中的main方法，在main方法中会实例化CoarseGrainedExecutorBackend本身这个消息循环体。
1. 而CoarseGrainedExecutorBackend在实例化的时候会通过回调onStart向DriverEndpoint发送RegisterExecutor来注册当前的CoarseGrainedExecutorBackend，此时DriverEndpoint收到到该注册信息并保存在了SparkDeploySchedulerBackend实例的内存数据结构中，这样Driver就获得了计算资源！  
[原文链接](http://www.jianshu.com/p/92ccc64c2f20)


---

FIFO的核心调度算法
```scala
private[spark] class FIFOSchedulingAlgorithm extends SchedulingAlgorithm {
  override def comparator(s1: Schedulable, s2: Schedulable): Boolean = {
    // priority实际上是jobID
    val priority1 = s1.priority
    val priority2 = s2.priority
    // 先比较jobID大小
    var res = math.signum(priority1 - priority2)
    // 如果jobID相同，则比较stageID
    if (res == 0) {
      val stageId1 = s1.stageId
      val stageId2 = s2.stageId
      res = math.signum(stageId1 - stageId2)
    }
    res < 0
  }
}
```
