### Spark调优资料
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
