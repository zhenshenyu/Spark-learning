## spark中的partition和block的关系
##### RDD计算的核心函数是iterator()函数：
```scala
final def iterator(split: Partition, context: TaskContext): Iterator[T] = {
    if (storageLevel != StorageLevel.NONE) {
      getOrCompute(split, context)
    } else {
      computeOrReadCheckpoint(split, context)
    }
  }
```
##### 如果当前RDD的storage level不是NONE的话，表示该RDD在BlockManager中有存储，那么调用CacheManager中的getOrCompute()函数计算RDD，在这个函数中partition和block发生了关系：
##### 首先根据RDD id和partition index构造出block id (rdd_xx_xx)，接着从BlockManager中取出相应的block。
- 如果该block存在，表示此RDD在之前已经被计算过和存储在BlockManager中，因此取出即可，无需再重新计算。
- 如果该block不存在则需要调用RDD的computeOrReadCheckpoint()函数计算出新的block，并将其存储到BlockManager中。需要注意的是block的计算和存储是阻塞的，若另一线程也需要用到此block则需等到该线程block的loading结束。  


[原文链接(摘自知乎）](https://www.zhihu.com/question/37310539/answer/162158686)
