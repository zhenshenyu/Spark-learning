Spark的EventLoop底层使用单独的一个线程作为事件理线程，使用一个LinkedBlockingDeque作为事件缓冲区，暂存储接收到的事件待事件处理线程处理。线程对事件的处理是通过一个while死循环，一直监视消息队列，每当消息队里有消息时候便获取并处理。如果消息队列为空的话，则阻塞。
```scala
/**
 * An event loop to receive events from the caller and process all events in the event thread. It
 * will start an exclusive event thread to process all events.
 *
 * Note: The event queue will grow indefinitely. So subclasses should make sure `onReceive` can
 * handle events in time to avoid the potential OOM.
 */
private[spark] abstract class EventLoop[E](name: String) extends Logging {
  
  // 事件缓冲区
  private val eventQueue: BlockingQueue[E] = new LinkedBlockingDeque[E]()
  
  // 标记时间循环处理器是否已启动
  private val stopped = new AtomicBoolean(false)

  // 新建守护线程
  private val eventThread = new Thread(name) {
    setDaemon(true)

    override def run(): Unit = {
      try {
        while (!stopped.get) {
          // 缓冲区没有事件时，阻塞
          val event = eventQueue.take()
          try {
            // 一旦有事件，调用onReceive进行处理
            onReceive(event)
          } catch {
            case NonFatal(e) =>
              try {
                onError(e)
              } catch {
                case NonFatal(e) => logError("Unexpected error in " + name, e)
              }
          }
        }
      } catch {
        // stop时缓冲区还有事件
        case ie: InterruptedException => // exit even if eventQueue is not empty
        case NonFatal(e) => logError("Unexpected error in " + name, e)
      }
    }

  }

  def start(): Unit = {
    if (stopped.get) {
      throw new IllegalStateException(name + " has already been stopped")
    }
    // Call onStart before starting the event thread to make sure it happens before onReceive
    // 在线程启动前调用onStart()
    onStart()
    eventThread.start()
  }

  def stop(): Unit = {
    if (stopped.compareAndSet(false, true)) {
      eventThread.interrupt()
      var onStopCalled = false
      try {
        eventThread.join()
        // Call onStop after the event thread exits to make sure onReceive happens before onStop
        // 确保调用onStop后不再能调用onReceive
        onStopCalled = true
        onStop()
      } catch {
        case ie: InterruptedException =>
          Thread.currentThread().interrupt()
          if (!onStopCalled) {
            // ie is thrown from `eventThread.join()`. Otherwise, we should not call `onStop` since
            // it's already called.
            onStop()
          }
      }
    } else {
      // Keep quiet to allow calling `stop` multiple times.
    }
  }

  /**
   * Put the event into the event queue. The event thread will process it later.
   */
  def post(event: E): Unit = {
    eventQueue.put(event)
  }

  /**
   * Return if the event thread has already been started but not yet stopped.
   */
  def isActive: Boolean = eventThread.isAlive

  /**
   * Invoked when `start()` is called but before the event thread starts.
   */
  protected def onStart(): Unit = {}

  /**
   * Invoked when `stop()` is called and the event thread exits.
   */
  protected def onStop(): Unit = {}

  /**
   * Invoked in the event thread when polling events from the event queue.
   *
   * Note: Should avoid calling blocking actions in `onReceive`, or the event thread will be blocked
   * and cannot process events in time. If you want to call some blocking actions, run them in
   * another thread.
   */
  protected def onReceive(event: E): Unit

  /**
   * Invoked if `onReceive` throws any non fatal error. Any non fatal error thrown from `onError`
   * will be ignored.
   */
  protected def onError(e: Throwable): Unit

}
```
DAGSchedulerEventProcessLoop类继承了EventLoop类，对信号进行监听  
该模块对将来写事件循环，编写socket程序有一定启发。