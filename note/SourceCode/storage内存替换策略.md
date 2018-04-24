storage内存替换  MemoryStore类的evictBlocksToFreeSpace方法  

换出策略为遍历entries（LinkedHashMap结构，存储blockid与blockid的大小、存储方式），按顺序选出block直到遍历完成或已满足内存需要
```scala
  /**
   * Return the RDD ID that a given block ID is from, or None if it is not an RDD block.
   */
  private def getRddId(blockId: BlockId): Option[Int] = {
    blockId.asRDDId.map(_.rddId)
  }

  /**
   * Try to evict blocks to free up a given amount of space to store a particular block.
   * Can fail if either the block is bigger than our memory or it would require replacing
   * another block from the same RDD (which leads to a wasteful cyclic replacement pattern for
   * RDDs that don't fit into memory that we want to avoid).
   *
   * @param blockId the ID of the block we are freeing space for, if any
   * @param space the size of this block
   * @param memoryMode the type of memory to free (on- or off-heap)
   * @return the amount of memory (in bytes) freed by eviction
   */
   
   
  private[spark] def evictBlocksToFreeSpace(
      blockId: Option[BlockId],
      space: Long,
      memoryMode: MemoryMode): Long = {
    assert(space > 0)
    memoryManager.synchronized {
      var freedMemory = 0L
      // getRddId见上文，这里不理解flatMap，一个block应该只对应一个rddf(待理解)
      val rddToAdd = blockId.flatMap(getRddId)
      // 被选定淘汰的blocks
      val selectedBlocks = new ArrayBuffer[BlockId]
      // memoryMode为onheap或offheap
      def blockIsEvictable(blockId: BlockId, entry: MemoryEntry[_]): Boolean = {
        entry.memoryMode == memoryMode && (rddToAdd.isEmpty || rddToAdd != getRddId(blockId))
      }
      // This is synchronized to ensure that the set of entries is not changed
      // (because of getValue or getBytes) while traversing the iterator, as that
      // can lead to exceptions.
      // synchronized保证同一时刻只有一个线程能执行该代码
      entries.synchronized {
        val iterator = entries.entrySet().iterator()
        while (freedMemory < space && iterator.hasNext) {
          val pair = iterator.next()
          val blockId = pair.getKey
          val entry = pair.getValue
          if (blockIsEvictable(blockId, entry)) {
            // We don't want to evict blocks which are currently being read, so we need to obtain
            // an exclusive write lock on blocks which are candidates for eviction. We perform a
            // non-blocking "tryLock" here in order to ignore blocks which are locked for reading:
            // blocking=false 代表如果获取锁失败将直接返回None
            if (blockInfoManager.lockForWriting(blockId, blocking = false).isDefined) {
              selectedBlocks += blockId
              freedMemory += pair.getValue.size
            }
          }
        }
      }
      
      // drop block，如果被drop的block还有存储（如在硬盘中），将保留其对应的block info，
      // 否则删除block info，以便下次还能缓存在内存中
      def dropBlock[T](blockId: BlockId, entry: MemoryEntry[T]): Unit = {
        val data = entry match {
          // 判断是否序列化存储
          case DeserializedMemoryEntry(values, _, _) => Left(values)
          case SerializedMemoryEntry(buffer, _, _) => Right(buffer)
        }
        val newEffectiveStorageLevel =
          blockEvictionHandler.dropFromMemory(blockId, () => data)(entry.classTag)
        if (newEffectiveStorageLevel.isValid) {
          // The block is still present in at least one store, so release the lock
          // but don't delete the block info
          blockInfoManager.unlock(blockId)
        } else {
          // The block isn't present in any store, so delete the block info so that the
          // block can be stored again
          blockInfoManager.removeBlock(blockId)
        }
      }
      
      // 判断准备释放的总内存是否满足需求
      if (freedMemory >= space) {
        logInfo(s"${selectedBlocks.size} blocks selected for dropping " +
          s"(${Utils.bytesToString(freedMemory)} bytes)")
        for (blockId <- selectedBlocks) {
          val entry = entries.synchronized { entries.get(blockId) }
          // This should never be null as only one task should be dropping
          // blocks and removing entries. However the check is still here for
          // future safety.
          if (entry != null) {
            dropBlock(blockId, entry)
          }
        }
        logInfo(s"After dropping ${selectedBlocks.size} blocks, " +
          s"free memory is ${Utils.bytesToString(maxMemory - blocksMemoryUsed)}")
        freedMemory
      } else {
        // 可释放的内存不够
        blockId.foreach { id =>
          logInfo(s"Will not store $id")
        }
        // 释放之前获取的selectedBlocks中所有block的锁
        selectedBlocks.foreach { id =>
          blockInfoManager.unlock(id)
        }
        0L
      }
    }
  }
```
已知换出方式是遍历entries，按顺序选择可换出的block，那么entry中block的顺序实际上就代表了其block的优先级，排在前端的block会更早地被换出去。  
再看一下entries的定义
```scala
private val entries = new LinkedHashMap[BlockId, MemoryEntry[_]](32, 0.75f, true)
```
LinkedHashMap的构造参数为LinkedHashMap(int initialCapacity, float loadFactor, boolean accessOrder)  
其中最后一个参数为true时，其中的key将按最近访问次序排序  
也就是说排在的第一位的block是最久未访问的block  

这样就实现了storage内存的evict策略——LRU策略