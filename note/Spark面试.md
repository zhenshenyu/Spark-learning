### Spark调优
#### 开发调优
- 原则一：避免创建重复的RDD
- 原则二：尽可能复用同一个RDD
- 原则三：对多次使用的RDD进行持久化
  - 选择正确的持久化策略（memory disk ser等）
  - disk io代价过大的情况下，也许重新计算以及效率会更高
  - 使用完后及时释放缓存的rdd
- 原则四： 尽量避免使用shuffle算子
- 原则五： 使用map-side预聚合的shuffle操作
  - reduceByKey替代groupByKey
- 原则六：使用高性能的算子
  - mapPartitions替代普通map
  - foreachPartitions替代foreach
  - filter之后进行coalesce
- 原则七：广播大变量
- 原则八：使用Kryo优化序列化性能
- 原则九：优化数据结构


#### 资源调优
![Spark作业运行理](https://img-blog.csdn.net/20160617154014702?watermark/2/text/aHR0cDovL2Jsb2cuY3Nkbi5uZXQv/font/5a6L5L2T/fontsize/400/fill/I0JBQkFCMA==/dissolve/70/gravity/Center)
- spark.default.parallelism
  - 该参数用于设置每个stage的默认task数量。这个参数极为重要，如果不设置可能会直接影响你的Spark作业性能。
  - Spark默认设置的数量是偏少的（比如就几十个task），如果task数量偏少的话，就会导致你前面设置好的Executor的参数都前功尽弃。
- spark.storage.memoryFraction
  - storage内存在executor堆内存中所占比例
- spark.shuffle.memoryFraction
  - execution内存在堆内存中所占比例

##### Spark 1.6以前使用的静态内存模型，和现在的动态内存模型区别
（execution storage比例，executor的多个task内存比例）


#### 数据倾斜调优


- 通过Spark Web UI/History Server查看各个stage中每个task的shuffle read和shuffle write数据量，是否分布不均
- 解决方案
  - 使用使用Hive ETL预处理数据（预处理过程中还是会发生数据倾斜）
  - 过滤导致数据倾斜的key
  - 提高shuffle并行度（极端情况并没有什么用）
  - 局部聚合+全局聚合，reducebykey或sql中的group by操作进行分组聚合时，非常适用。
  - join类操作时，使用广播
  - 使用随机前缀和扩容RDD进行join
   - 该方案的实现思路基本和“解决方案六”类似，首先查看RDD/Hive表中的数据分布情况，找到那个造成数据倾斜的RDD/Hive表，比如有多个key都对应了超过1万条数据。
   -  然后将该RDD的每条数据都打上一个n以内的随机前缀。
   -  同时对另外一个正常的RDD进行扩容，将每条数据都扩容成n条数据，扩容出来的每条数据都依次打上一个0~n的前缀。
   -  最后将两个处理后的RDD进行join即可。


### 其他知识点
#### DAGscheduler
- 在一个action操作触发一个job时，从action的rdd开始，生成final stage
- 使用递归遍历所有父Rdd，根据宽依赖来划分不同stage

#### TASKscheduler
- 每个stage包含一个taskset
- 一个taskset有若干task，每个task执行一样的操作，操作数据不同。

#### BlockManager
BlockManager负责实际的存储管理（BlockStore的三个实现，DickStore、MemoryStore、TachyonStore）
- driver BlockManagerMaster
- executor BlockManager负责管理executor的
- 数据读入，先看本地的block是否存在，否则通过rpc框架进行远程获取
- rdd的eviction
- shuffle时的数据管理

SparkSession

### Spark SQL流程

- 通过Parser模块被解析为语法树
- 语法树借助于Catalog中的表信息解析为Logical Plan
- Optimizer再通过各种基于规则的优化策略进行深入优化，得到Optimized Logical Plan
- 将此逻辑执行计划转换为Physical Plan

##### Catalyst框架进行查询优化
