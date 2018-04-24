##### 一般来说，Lineage链较长、宽依赖的RDD需要采用检查点机制。这种情况下，集群的节点故障可能导致每个父RDD的数据块丢失，因此需要全部重新计算，将窄依赖的RDD数据存到物理存储中可以实现优化。
##### CheckpointRDD类的定义：
```scala
/**
 * An RDD that recovers checkpointed data from storage.
 */
// CheckpointRDD继承RDD，为了保证checkpointrdd不被再次checkpoint，重写了doCheckpoint,checkpoint等方法。
private[spark] abstract class CheckpointRDD[T: ClassTag](sc: SparkContext)
  extends RDD[T](sc, Nil) {

  // CheckpointRDD should not be checkpointed again
  override def doCheckpoint(): Unit = { }
  override def checkpoint(): Unit = { }
  override def localCheckpoint(): this.type = this

  // Note: There is a bug in MiMa that complains about `AbstractMethodProblem`s in the
  // base [[org.apache.spark.rdd.RDD]] class if we do not override the following methods.
  // scalastyle:off
  protected override def getPartitions: Array[Partition] = ???
  override def compute(p: Partition, tc: TaskContext): Iterator[T] = ???
  // scalastyle:on

```
##### ReliableCheckpointRDD类：
```scala
/**
 * An RDD that reads from checkpoint files previously written to reliable storage.
 */
private[spark] class ReliableCheckpointRDD[T: ClassTag](
    sc: SparkContext,
    val checkpointPath: String,
    _partitioner: Option[Partitioner] = None
  ) extends CheckpointRDD[T](sc) {

  @transient private val hadoopConf = sc.hadoopConfiguration
  @transient private val cpath = new Path(checkpointPath)
  @transient private val fs = cpath.getFileSystem(hadoopConf)
  private val broadcastedConf = sc.broadcast(new SerializableConfiguration(hadoopConf))

  // Fail fast if checkpoint directory does not exist
  require(fs.exists(cpath), s"Checkpoint directory does not exist: $checkpointPath")

  /**
   * Return the path of the checkpoint directory this RDD reads data from.
   */
  override val getCheckpointFile: Option[String] = Some(checkpointPath)

  override val partitioner: Option[Partitioner] = {
    _partitioner.orElse {
      ReliableCheckpointRDD.readCheckpointedPartitionerFile(context, checkpointPath)
    }
  }
```
##### writePartitionToCheckpointFile方法，将RDD的一个partition存入一个CheckpointFile中。
```scala
/**
   * Write an RDD partition's data to a checkpoint file.
   */
  def writePartitionToCheckpointFile[T: ClassTag](
      path: String,
      broadcastedConf: Broadcast[SerializableConfiguration],
      blockSize: Int = -1)(ctx: TaskContext, iterator: Iterator[T]) {
    val env = SparkEnv.get
    // 获取存储路径，默认为hdfs路径
    val outputDir = new Path(path)
    val fs = outputDir.getFileSystem(broadcastedConf.value.value)
    
    // 根据partitionId 生成 checkpointFileName
    val finalOutputName = ReliableCheckpointRDD.checkpointFileName(ctx.partitionId())
    // 拼接路径与文件名
    val finalOutputPath = new Path(outputDir, finalOutputName)
    // 生成临时输出路径
    val tempOutputPath =
      new Path(outputDir, s".$finalOutputName-attempt-${ctx.attemptNumber()}")
    // 得到块大小，默认为64MB
    val bufferSize = env.conf.getInt("spark.buffer.size", 65536)
    
    // 得到文件输出流
    val fileOutputStream = if (blockSize < 0) {
      val fileStream = fs.create(tempOutputPath, false, bufferSize)
      if (env.conf.get(CHECKPOINT_COMPRESS)) {
        CompressionCodec.createCodec(env.conf).compressedOutputStream(fileStream)
      } else {
        fileStream
      }
    } else {
      // This is mainly for testing purpose
      fs.create(tempOutputPath, false, bufferSize,
        fs.getDefaultReplication(fs.getWorkingDirectory), blockSize)
    }
    // 序列化文件输出流
    val serializer = env.serializer.newInstance()
    val serializeStream = serializer.serializeStream(fileOutputStream)
    Utils.tryWithSafeFinally {
    // 写数据
      serializeStream.writeAll(iterator)
    } {
      serializeStream.close()
    }

    if (!fs.rename(tempOutputPath, finalOutputPath)) {
    // 重命名失败
      if (!fs.exists(finalOutputPath)) {
      // 输出路径不存在
        logInfo(s"Deleting tempOutputPath $tempOutputPath")
        fs.delete(tempOutputPath, false)
        throw new IOException("Checkpoint failed: failed to save output of task: " +
          s"${ctx.attemptNumber()} and final output path does not exist: $finalOutputPath")
      } else {
        // Some other copy of this task must've finished before us and renamed it
        logInfo(s"Final output path $finalOutputPath already exists; not overwriting it")
        if (!fs.delete(tempOutputPath, false)) {
          logWarning(s"Error deleting ${tempOutputPath}")
        }
      }
    }
  }
```
##### writeRDDToCheckpointDirectory方法，将RDD写入多个Checkpoint文件中，并返回一个ReliableCheckpointRDD，对应该RDD。
```scala
/**
   * Write RDD to checkpoint files and return a ReliableCheckpointRDD representing the RDD.
   */
  def writeRDDToCheckpointDirectory[T: ClassTag](
      originalRDD: RDD[T],
      checkpointDir: String,
      blockSize: Int = -1): ReliableCheckpointRDD[T] = {
    val checkpointStartTimeNs = System.nanoTime()

    val sc = originalRDD.sparkContext

    // Create the output path for the checkpoint
    val checkpointDirPath = new Path(checkpointDir)
    val fs = checkpointDirPath.getFileSystem(sc.hadoopConfiguration)
    if (!fs.mkdirs(checkpointDirPath)) {
      throw new SparkException(s"Failed to create checkpoint path $checkpointDirPath")
    }

    // Save to file, and reload it as an RDD
    val broadcastedConf = sc.broadcast(
      new SerializableConfiguration(sc.hadoopConfiguration))
    // TODO: This is expensive because it computes the RDD again unnecessarily (SPARK-8582)
    // 该操作重新计算了RDD（不必要的操作），有比较大的开销，或许可以优化
    sc.runJob(originalRDD,
      writePartitionToCheckpointFile[T](checkpointDirPath.toString, broadcastedConf) _)

    if (originalRDD.partitioner.nonEmpty) {
    // 这里不太理解
      writePartitionerToCheckpointDir(sc, originalRDD.partitioner.get, checkpointDirPath)
    }

    val checkpointDurationMs =
      TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - checkpointStartTimeNs)
    logInfo(s"Checkpointing took $checkpointDurationMs ms.")

    val newRDD = new ReliableCheckpointRDD[T](
      sc, checkpointDirPath.toString, originalRDD.partitioner)
      // 比较ReliableCheckpointRDD与原RDD，如果partitions的length（似乎指分区数）不一样，证明该Checkpoint数据有误
    if (newRDD.partitions.length != originalRDD.partitions.length) {
      throw new SparkException(
        s"Checkpoint RDD $newRDD(${newRDD.partitions.length}) has different " +
          s"number of partitions from original RDD $originalRDD(${originalRDD.partitions.length})")
    }
    newRDD
  }
```
##### writePartitionerToCheckpointDir，将一个partitioner写入指定checkpoint文件夹。在写partitioner期间，任何异常都会被记录然后忽略。
```scala
/**
   * Write a partitioner to the given RDD checkpoint directory. This is done on a best-effort
   * basis; any exception while writing the partitioner is caught, logged and ignored.
   */
  private def writePartitionerToCheckpointDir(
    sc: SparkContext, partitioner: Partitioner, checkpointDirPath: Path): Unit = {
    try {
      val partitionerFilePath = new Path(checkpointDirPath, checkpointPartitionerFileName)
      val bufferSize = sc.conf.getInt("spark.buffer.size", 65536)
      val fs = partitionerFilePath.getFileSystem(sc.hadoopConfiguration)
      val fileOutputStream = fs.create(partitionerFilePath, false, bufferSize)
      // 序列化文件输出流
      val serializer = SparkEnv.get.serializer.newInstance()
      val serializeStream = serializer.serializeStream(fileOutputStream)
      Utils.tryWithSafeFinally {
        // 写数据
        serializeStream.writeObject(partitioner)
      } {
        serializeStream.close()
      }
      logDebug(s"Written partitioner to $partitionerFilePath")
    } catch {
      case NonFatal(e) =>
        logWarning(s"Error writing partitioner $partitioner to $checkpointDirPath")
    }
  }
```
##### 读取指定的checkpoint文件
```scala
  /**
   * Read the content of the specified checkpoint file.
   */
  def readCheckpointFile[T](
      path: Path,
      broadcastedConf: Broadcast[SerializableConfiguration],
      context: TaskContext): Iterator[T] = {
    val env = SparkEnv.get
    val fs = path.getFileSystem(broadcastedConf.value.value)
    val bufferSize = env.conf.getInt("spark.buffer.size", 65536)
    val fileInputStream = {
      val fileStream = fs.open(path, bufferSize)
      if (env.conf.get(CHECKPOINT_COMPRESS)) {
        CompressionCodec.createCodec(env.conf).compressedInputStream(fileStream)scala
      } else {
        fileStream
      }
    }
    val serializer = env.serializer.newInstance()
    val deserializeStream = serializer.deserializeStream(fileInputStream)

    // Register an on-task-completion callback to close the input stream.
    context.addTaskCompletionListener(context => deserializeStream.close())

    deserializeStream.asIterator.asInstanceOf[Iterator[T]]
  }

}
```
##### checkpoint之后，需要对原rdd进行记录
```scala
  // Avoid handling doCheckpoint multiple times to prevent excessive recursion
  @transient private var doCheckpointCalled = false

  /**
   * Performs the checkpointing of this RDD by saving this. It is called after a job using this RDD
   * has completed (therefore the RDD has been materialized and potentially stored in memory).
   * doCheckpoint() is called recursively on the parent RDDs.
   */
  private[spark] def doCheckpoint(): Unit = {
    RDDOperationScope.withScope(sc, "checkpoint", allowNesting = false, ignoreParent = true) {
      if (!doCheckpointCalled) {
        doCheckpointCalled = true
        if (checkpointData.isDefined) {
          if (checkpointAllMarkedAncestors) {
            // TODO We can collect all the RDDs that needs to be checkpointed, and then checkpoint
            // them in parallel.
            // Checkpoint parents first because our lineage will be truncated after we
            // checkpoint ourselves
            dependencies.foreach(_.rdd.doCheckpoint())
          }
          checkpointData.get.checkpoint()
        } else {
          dependencies.foreach(_.rdd.doCheckpoint())
        }
      }
    }
  }
```
