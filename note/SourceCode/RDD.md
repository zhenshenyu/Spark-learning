#### RDD.scala源码解读
源码中的定义：
```
/**
 * A Resilient Distributed Dataset (RDD), the basic abstraction in Spark. Represents an immutable,
 * partitioned collection of elements that can be operated on in parallel. This class contains the
 * basic operations available on all RDDs, such as `map`, `filter`, and `persist`. In addition,
 * [[org.apache.spark.rdd.PairRDDFunctions]] contains operations available only on RDDs of key-value
 * pairs, such as `groupByKey` and `join`;
 * [[org.apache.spark.rdd.DoubleRDDFunctions]] contains operations available only on RDDs of
 * Doubles; and
 * [[org.apache.spark.rdd.SequenceFileRDDFunctions]] contains operations available on RDDs that
 * can be saved as SequenceFiles.
 * All operations are automatically available on any RDD of the right type (e.g. RDD[(Int, Int)]
 * through implicit.
 *
 * Internally, each RDD is characterized by five main properties:
 *
 *  - A list of partitions
 *  - A function for computing each split
 *  - A list of dependencies on other RDDs
 *  - Optionally, a Partitioner for key-value RDDs (e.g. to say that the RDD is hash-partitioned)
 *  - Optionally, a list of preferred locations to compute each split on (e.g. block locations for
 *    an HDFS file)
 *
 * All of the scheduling and execution in Spark is done based on these methods, allowing each RDD
 * to implement its own way of computing itself. Indeed, users can implement custom RDDs (e.g. for
 * reading data from a new storage system) by overriding these functions. Please refer to the
 * <a href="http://people.csail.mit.edu/matei/papers/2012/nsdi_spark.pdf">Spark paper</a>
 * for more details on RDD internals.
 */
```
RDD类中包含了所有RDD的基本操作，比如map、filter、perisist。
- org.apache.spark.rdd.PairRDDFunctions中的操作，如groupByKey与join，只能被键值对RDD调用。
- org.apache.spark.rdd.DoubleRDDFunctions中的操作（如sum,mean），只能被Doubles RDD调用。
- org.apache.spark.rdd.SequenceFileRDDFunctions中的操作（如saveAsSequenceFile），只能被可以存为sequencefile的RDD调用。  
> SequenceFile是hdfs的一种数据类型。
##### 每个RDD包含以下
- 一个记录portitions的list
- 计算每个分区的函数
- 一个记录与其他RDD关系（dependencies）的list
- 对于键值对RDD来说，有一个Partitioner（指定RDD的分区方式，如HashPartitioner或RangePartitioner）
- 要运行的计算/执行最好在哪(几)个机器上运行。*比如：hadoop默认有三个位置，或者spark cache到内存是可能通过StorageLevel设置了多个副本，所以一个partition可能返回多个最佳位置。*

---

##### class RDD定义
```scala
abstract class RDD[T: ClassTag](
    @transient private var _sc: SparkContext,
    @transient private var deps: Seq[Dependency[_]]
  ) extends Serializable with Logging
```
id
```scala
  /** A unique ID for this RDD (within its SparkContext). */
  // 该RDD的唯一id
  val id: Int = sc.newRddId()
```
name
```scala
  /** A friendly name for this RDD */
  @transient var name: String = null

  /** Assign a name to this RDD */
  def setName(_name: String): this.type = {
    name = _name
    this
  }
```
sc
```scala
private def sc: SparkContext = {
    if (_sc == null) {
      throw new SparkException(
        "This RDD lacks a SparkContext. It could happen in the following cases: \n(1) RDD " +
        "transformations and actions are NOT invoked by the driver, but inside of other " +
        "transformations; for example, rdd1.map(x => rdd2.values.count() * x) is invalid " +
        "because the values transformation and count action cannot be performed inside of the " +
        "rdd1.map transformation. For more information, see SPARK-5063.\n(2) When a Spark " +
        "Streaming job recovers from checkpoint, this exception will be hit if a reference to " +
        "an RDD not defined by the streaming job is used in DStream operations. For more " +
        "information, See SPARK-13758.")
    }
    _sc
  }
```
sparkContext
```scala
  /** The SparkContext that created this RDD. */
  def sparkContext: SparkContext = sc
```
creationSite
```scala
  /** User code that created this RDD (e.g. `textFile`, `parallelize`). */
  // 创建该RDD的用户代码
  @transient private[spark] val creationSite = sc.getCallSite()
```
dependencies_
```scala
  // Our dependencies and partitions will be gotten by calling subclass's methods below, and will
  // be overwritten when we're checkpointed
  private var dependencies_ : Seq[Dependency[_]] = null
  
  /**
   * Get the list of dependencies of this RDD, taking into account whether the
   * RDD is checkpointed or not.
   */
  final def dependencies: Seq[Dependency[_]] = {
    checkpointRDD.map(r => List(new OneToOneDependency(r))).getOrElse {
      if (dependencies_ == null) {
        dependencies_ = getDependencies
      }
      dependencies_
    }
  }
```
partitions_
```scala
  // Our dependencies and partitions will be gotten by calling subclass's methods below, and will
  // be overwritten when we're checkpointed
@transient private var partitions_ : Array[Partition] = null

  /**
   * Get the array of partitions of this RDD, taking into account whether the
   * RDD is checkpointed or not.
   */
  final def partitions: Array[Partition] = {
    checkpointRDD.map(_.partitions).getOrElse {
      if (partitions_ == null) {
        partitions_ = getPartitions
        partitions_.zipWithIndex.foreach { case (partition, index) =>
          require(partition.index == index,
            s"partitions($index).partition == ${partition.index}, but it should equal $index")
        }
      }
      partitions_
    }
  }
```
partitioner
```scala
/** Optionally overridden by subclasses to specify how they are partitioned. */
// 由子类重写，记录RDD的分区方式
  @transient val partitioner: Option[Partitioner] = None
```
doCheckpointCalled
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
storageLevel
```scala
  private var storageLevel: StorageLevel = StorageLevel.NONE
  
// 以下是storage.StorageLevel.scala文件中的class StorageLevel定义
/**
 * :: DeveloperApi ::
 * Flags for controlling the storage of an RDD. Each StorageLevel records whether to use memory,
 * or ExternalBlockStore, whether to drop the RDD to disk if it falls out of memory or
 * ExternalBlockStore, whether to keep the data in memory in a serialized format, and whether
 * to replicate the RDD partitions on multiple nodes.
 *
 * The [[org.apache.spark.storage.StorageLevel]] singleton object contains some static constants
 * for commonly useful storage levels. To create your own storage level object, use the
 * factory method of the singleton object (`StorageLevel(...)`).
 */
@DeveloperApi
class StorageLevel private(
    // 使用外存（硬盘）
    private var _useDisk: Boolean,
    // 使用内存
    private var _useMemory: Boolean,
    // 使用堆外内存
    // 堆外内存是Java虚拟机里面的概念，堆外内存意味着把内存对象分配在Java虚拟机的堆以外的内存，
    // 这些内存直接受操作系统管理（而不是虚拟机）。这样做的结果就是能保持一个较小的堆，以减少垃圾收集对应用的影响。
    private var _useOffHeap: Boolean,
    // 反序列化
    private var _deserialized: Boolean,
    //备份数
    private var _replication: Int = 1)
  extends Externalizable{...}
  
// object StorageLevel定义
/**
 * Various [[org.apache.spark.storage.StorageLevel]] defined and utility functions for creating
 * new storage levels.
 */
object StorageLevel {
  val NONE = new StorageLevel(false, false, false, false)
  val DISK_ONLY = new StorageLevel(true, false, false, false)
  val DISK_ONLY_2 = new StorageLevel(true, false, false, false, 2)
  val MEMORY_ONLY = new StorageLevel(false, true, false, true)
  val MEMORY_ONLY_2 = new StorageLevel(false, true, false, true, 2)
  val MEMORY_ONLY_SER = new StorageLevel(false, true, false, false)
  val MEMORY_ONLY_SER_2 = new StorageLevel(false, true, false, false, 2)
  val MEMORY_AND_DISK = new StorageLevel(true, true, false, true)
  val MEMORY_AND_DISK_2 = new StorageLevel(true, true, false, true, 2)
  val MEMORY_AND_DISK_SER = new StorageLevel(true, true, false, false)
  val MEMORY_AND_DISK_SER_2 = new StorageLevel(true, true, false, false, 2)
  val OFF_HEAP = new StorageLevel(true, true, true, false, 1)
  // 总共有12个存储级别
  ...
  }
```

---

##### object RDD
```scala
/**
 * Defines implicit functions that provide extra functionalities on RDDs of specific types.
 *
 * For example, [[RDD.rddToPairRDDFunctions]] converts an RDD into a [[PairRDDFunctions]] for
 * key-value-pair RDDs, and enabling extra functionalities such as `PairRDDFunctions.reduceByKey`.
 */
// object RDD通过隐式转换为某些类型的RDD提供了额外函数
// 以下面的rddToPairRDDFunctions为例，它将一个普通RDD转化为键值对RDD，
// 并使其能调用一些额外函数，如PairRDDFunctions.reduceByKey
object RDD {

  private[spark] val CHECKPOINT_ALL_MARKED_ANCESTORS =
    "spark.checkpoint.checkpointAllMarkedAncestors"

  // The following implicit functions were in SparkContext before 1.3 and users had to
  // `import SparkContext._` to enable them. Now we move them here to make the compiler find
  // them automatically. However, we still keep the old functions in SparkContext for backward
  // compatibility and forward to the following functions directly.

  implicit def rddToPairRDDFunctions[K, V](rdd: RDD[(K, V)])
    (implicit kt: ClassTag[K], vt: ClassTag[V], ord: Ordering[K] = null): PairRDDFunctions[K, V] = {
    new PairRDDFunctions(rdd)
  }

  implicit def rddToAsyncRDDActions[T: ClassTag](rdd: RDD[T]): AsyncRDDActions[T] = {
    new AsyncRDDActions(rdd)
  }

  implicit def rddToSequenceFileRDDFunctions[K, V](rdd: RDD[(K, V)])
      (implicit kt: ClassTag[K], vt: ClassTag[V],
                keyWritableFactory: WritableFactory[K],
                valueWritableFactory: WritableFactory[V])
    : SequenceFileRDDFunctions[K, V] = {
    implicit val keyConverter = keyWritableFactory.convert
    implicit val valueConverter = valueWritableFactory.convert
    new SequenceFileRDDFunctions(rdd,
      keyWritableFactory.writableClass(kt), valueWritableFactory.writableClass(vt))
  }

  implicit def rddToOrderedRDDFunctions[K : Ordering : ClassTag, V: ClassTag](rdd: RDD[(K, V)])
    : OrderedRDDFunctions[K, V, (K, V)] = {
    new OrderedRDDFunctions[K, V, (K, V)](rdd)
  }

  implicit def doubleRDDToDoubleRDDFunctions(rdd: RDD[Double]): DoubleRDDFunctions = {
    new DoubleRDDFunctions(rdd)
  }

  implicit def numericRDDToDoubleRDDFunctions[T](rdd: RDD[T])(implicit num: Numeric[T])
    : DoubleRDDFunctions = {
    new DoubleRDDFunctions(rdd.map(x => num.toDouble(x)))
  }
}
```