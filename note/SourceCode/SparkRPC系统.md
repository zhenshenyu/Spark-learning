
Spark2.0之后抛弃了AKKA，使用netty作为rpc框架  
![image](http://upload-images.jianshu.io/upload_images/4093261-98258e8657cea9dc.png?imageMogr2/auto-orient/strip%7CimageView2/2)  
RPC通信主要有RpcEnv、RpcEndpoint、RpcEndpointRef这三个核心类。  
- 一个RpcEnv是一个RPC环境对象，它负责管理RpcEndpoint的注册，以及如何从一个RpcEndpoint获取到一个RpcEndpointRef。RpcEndpoint是一个通信端，例如Spark集群中的Master，或Worker，都是一个RpcEndpoint。但是，如果想要与一个RpcEndpoint端进行通信，一定需要获取到该RpcEndpoint一个RpcEndpointRef，而获取该RpcEndpointRef只能通过一个RpcEnv环境对象来获取。
- RpcEndpoint定义了RPC通信过程中的通信端对象，除了具有管理一个RpcEndpoint生命周期的操作（constructor-> onStart -> receive* ->onStop），并给出了通信过程中一个RpcEndpoint所具有的基于事件驱动的行为（连接、断开、网络异常），实际上对于Spark框架来说RpcEndpoint主要是接收消息并处理。
- RpcEndpointRef是一个对RpcEndpoint的远程引用对象，通过它可以向远程的RpcEndpoint端发送消息以进行通信。

---

#### RpcEnv定义
RpcEnv抽象类及其伴生对象  
RpcEnv必须由RpcEnvFactory的create方法创建
```scala
/**
 * A RpcEnv implementation must have a [[RpcEnvFactory]] implementation with an empty constructor
 * so that it can be created via Reflection.
 */
private[spark] object RpcEnv {

  def create(
      name: String,
      host: String,
      port: Int,
      conf: SparkConf,
      securityManager: SecurityManager,
      clientMode: Boolean = false): RpcEnv = {
    create(name, host, host, port, conf, securityManager, clientMode)
  }

  def create(
      name: String,
      bindAddress: String,
      advertiseAddress: String,
      port: Int,
      conf: SparkConf,
      securityManager: SecurityManager,
      clientMode: Boolean): RpcEnv = {
    val config = RpcEnvConfig(conf, name, bindAddress, advertiseAddress, port, securityManager,
      clientMode)
    new NettyRpcEnvFactory().create(config)
  }
}



/**
 * An RPC environment. [[RpcEndpoint]]s need to register itself with a name to [[RpcEnv]] to
 * receives messages. Then [[RpcEnv]] will process messages sent from [[RpcEndpointRef]] or remote
 * nodes, and deliver them to corresponding [[RpcEndpoint]]s. For uncaught exceptions caught by
 * [[RpcEnv]], [[RpcEnv]] will use [[RpcCallContext.sendFailure]] to send exceptions back to the
 * sender, or logging them if no such sender or `NotSerializableException`.
 *
 * [[RpcEnv]] also provides some methods to retrieve [[RpcEndpointRef]]s given name or uri.
 */
private[spark] abstract class RpcEnv(conf: SparkConf) {

  private[spark] val defaultLookupTimeout = RpcUtils.lookupRpcTimeout(conf)

  /**
   * Return RpcEndpointRef of the registered [[RpcEndpoint]]. Will be used to implement
   * [[RpcEndpoint.self]]. Return `null` if the corresponding [[RpcEndpointRef]] does not exist.
   */
  private[rpc] def endpointRef(endpoint: RpcEndpoint): RpcEndpointRef

  /**
   * Return the address that [[RpcEnv]] is listening to.
   */
  def address: RpcAddress

  /**
   * Register a [[RpcEndpoint]] with a name and return its [[RpcEndpointRef]]. [[RpcEnv]] does not
   * guarantee thread-safety.
   */
  // 注册一个RpcEndpoint到RpcEnv环境对象中，由RpcEnv来管理RpcEndpoint到RpcEndpointRef的绑定关系。
  def setupEndpoint(name: String, endpoint: RpcEndpoint): RpcEndpointRef

  /**
   * Retrieve the [[RpcEndpointRef]] represented by `uri` asynchronously.
   */
  def asyncSetupEndpointRefByURI(uri: String): Future[RpcEndpointRef]

  /**
   * Retrieve the [[RpcEndpointRef]] represented by `uri`. This is a blocking action.
   */
  def setupEndpointRefByURI(uri: String): RpcEndpointRef = {
    defaultLookupTimeout.awaitResult(asyncSetupEndpointRefByURI(uri))
  }

  /**
   * Retrieve the [[RpcEndpointRef]] represented by `address` and `endpointName`.
   * This is a blocking action.
   */
  // 获取与RpcEndpoint对应的RpcEndpointRef
  def setupEndpointRef(address: RpcAddress, endpointName: String): RpcEndpointRef = {
    setupEndpointRefByURI(RpcEndpointAddress(address, endpointName).toString)
  }

  /**
   * Stop [[RpcEndpoint]] specified by `endpoint`.
   */
  def stop(endpoint: RpcEndpointRef): Unit

  /**
   * Shutdown this [[RpcEnv]] asynchronously. If need to make sure [[RpcEnv]] exits successfully,
   * call [[awaitTermination()]] straight after [[shutdown()]].
   */
  def shutdown(): Unit

  /**
   * Wait until [[RpcEnv]] exits.
   *
   * TODO do we need a timeout parameter?
   */
  def awaitTermination(): Unit

  /**
   * [[RpcEndpointRef]] cannot be deserialized without [[RpcEnv]]. So when deserializing any object
   * that contains [[RpcEndpointRef]]s, the deserialization codes should be wrapped by this method.
   */
  def deserialize[T](deserializationAction: () => T): T

  /**
   * Return the instance of the file server used to serve files. This may be `null` if the
   * RpcEnv is not operating in server mode.
   */
  def fileServer: RpcEnvFileServer

  /**
   * Open a channel to download a file from the given URI. If the URIs returned by the
   * RpcEnvFileServer use the "spark" scheme, this method will be called by the Utils class to
   * retrieve the files.
   *
   * @param uri URI with location of the file.
   */
  def openChannel(uri: String): ReadableByteChannel
}
```
RpcEnv的实现NettyRpcEnv（部分节选）  
新创建的NettyRpcEnv主要用于Endpoint的注册、启动transportServer、获得RPCEndpointRef、创建客户端等等；其主要成员有dispatcher、transportContext。
```scala
private[netty] class NettyRpcEnv(
    val conf: SparkConf,
    javaSerializerInstance: JavaSerializerInstance,
    host: String,
    securityManager: SecurityManager) extends RpcEnv(conf) with Logging {

  private[netty] val transportConf = SparkTransportConf.fromSparkConf(
    conf.clone.set("spark.rpc.io.numConnectionsPerPeer", "1"),
    "rpc",
    conf.getInt("spark.rpc.io.threads", 0))
  // 创建Dispatcher，主要负责用户消息的分发处理
  private val dispatcher: Dispatcher = new Dispatcher(this)
  // 创建streamManager，用于文件传输
  private val streamManager = new NettyStreamManager(this)
  // 创建一个transportContext，主要用于创建Netty的Server和Client
  private val transportContext = new TransportContext(transportConf,
    new NettyRpcHandler(dispatcher, this, streamManager))
  
  private def createClientBootstraps(): java.util.List[TransportClientBootstrap] = {
    if (securityManager.isAuthenticationEnabled()) {
      java.util.Arrays.asList(new AuthClientBootstrap(transportConf,
        securityManager.getSaslUser(), securityManager))
    } else {
      java.util.Collections.emptyList[TransportClientBootstrap]
    }
  }
  // 声明一个clientFactory，用户创建通信的客户端
  private val clientFactory = transportContext.createClientFactory(createClientBootstraps())

  /**
   * A separate client factory for file downloads. This avoids using the same RPC handler as
   * the main RPC context, so that events caused by these clients are kept isolated from the
   * main RPC traffic.
   *
   * It also allows for different configuration of certain properties, such as the number of
   * connections per peer.
   */
  @volatile private var fileDownloadFactory: TransportClientFactory = _
  // 创建一个netty-rpc-env-timeout的守护线程
  val timeoutScheduler = ThreadUtils.newDaemonSingleThreadScheduledExecutor("netty-rpc-env-timeout")

  // Because TransportClientFactory.createClient is blocking, we need to run it in this thread pool
  // to implement non-blocking send/ask.
  // TODO: a non-blocking TransportClientFactory.createClient in future
  private[netty] val clientConnectionExecutor = ThreadUtils.newDaemonCachedThreadPool(
    "netty-rpc-connection",
    conf.getInt("spark.rpc.connect.threads", 64))

  @volatile private var server: TransportServer = _

  private val stopped = new AtomicBoolean(false)

  /**
   * A map for [[RpcAddress]] and [[Outbox]]. When we are connecting to a remote [[RpcAddress]],
   * we just put messages to its [[Outbox]] to implement a non-blocking `send` method.
   */
  private val outboxes = new ConcurrentHashMap[RpcAddress, Outbox]()

  /**
   * Remove the address's Outbox and stop it.
   */
  private[netty] def removeOutbox(address: RpcAddress): Unit = {
    val outbox = outboxes.remove(address)
    if (outbox != null) {
      outbox.stop()
    }
  }

  // 根据指定端口，启动transportServer
  def startServer(bindAddress: String, port: Int): Unit = {
    val bootstraps: java.util.List[TransportServerBootstrap] =
      if (securityManager.isAuthenticationEnabled()) {
        java.util.Arrays.asList(new AuthServerBootstrap(transportConf, securityManager))
      } else {
        java.util.Collections.emptyList()
      }
    // 通过transportContext启动通信底层的服务端
    server = transportContext.createServer(bindAddress, port, bootstraps)
    // 注册一个RpcEndpointVerifier，对Server进行验证
    dispatcher.registerRpcEndpoint(
      RpcEndpointVerifier.NAME, new RpcEndpointVerifier(this, dispatcher))
  }

  @Nullable
  override lazy val address: RpcAddress = {
    if (server != null) RpcAddress(host, server.getPort()) else null
  }
  
  // 重写抽象类rpcEnv中的setupEndpoint方法，用户rpcEndpoint在rpcEnv上进行注册
  override def setupEndpoint(name: String, endpoint: RpcEndpoint): RpcEndpointRef = {
    dispatcher.registerRpcEndpoint(name, endpoint)
  }

  def asyncSetupEndpointRefByURI(uri: String): Future[RpcEndpointRef] = {
    val addr = RpcEndpointAddress(uri)
    val endpointRef = new NettyRpcEndpointRef(conf, addr, this)
    val verifier = new NettyRpcEndpointRef(
      conf, RpcEndpointAddress(addr.rpcAddress, RpcEndpointVerifier.NAME), this)
    verifier.ask[Boolean](RpcEndpointVerifier.CheckExistence(endpointRef.name)).flatMap { find =>
      if (find) {
        Future.successful(endpointRef)
      } else {
        Future.failed(new RpcEndpointNotFoundException(uri))
      }
    }(ThreadUtils.sameThread)
  }

  override def stop(endpointRef: RpcEndpointRef): Unit = {
    require(endpointRef.isInstanceOf[NettyRpcEndpointRef])
    dispatcher.stop(endpointRef)
  }
  
  // 实现send操作
  private[netty] def send(message: RequestMessage): Unit = {
    val remoteAddr = message.receiver.address
    if (remoteAddr == address) {
      // Message to a local RPC endpoint.
      try {
        dispatcher.postOneWayMessage(message)
      } catch {
        case e: RpcEnvStoppedException => logWarning(e.getMessage)
      }
    } else {
      // Message to a remote RPC endpoint.
      postToOutbox(message.receiver, OneWayOutboxMessage(message.serialize(this)))
    }
  }
  
  // 实现ask操作
  private[netty] def ask[T: ClassTag](message: RequestMessage, timeout: RpcTimeout): Future[T] = {
    val promise = Promise[Any]()
    val remoteAddr = message.receiver.address

    def onFailure(e: Throwable): Unit = {
      if (!promise.tryFailure(e)) {
        logWarning(s"Ignored failure: $e")
      }
    }

    def onSuccess(reply: Any): Unit = reply match {
      case RpcFailure(e) => onFailure(e)
      case rpcReply =>
        if (!promise.trySuccess(rpcReply)) {
          logWarning(s"Ignored message: $reply")
        }
    }

    try {
      if (remoteAddr == address) {
        val p = Promise[Any]()
        p.future.onComplete {
          case Success(response) => onSuccess(response)
          case Failure(e) => onFailure(e)
        }(ThreadUtils.sameThread)
        dispatcher.postLocalMessage(message, p)
      } else {
        val rpcMessage = RpcOutboxMessage(message.serialize(this),
          onFailure,
          (client, response) => onSuccess(deserialize[Any](client, response)))
        postToOutbox(message.receiver, rpcMessage)
        promise.future.onFailure {
          case _: TimeoutException => rpcMessage.onTimeout()
          case _ =>
        }(ThreadUtils.sameThread)
      }

      val timeoutCancelable = timeoutScheduler.schedule(new Runnable {
        override def run(): Unit = {
          onFailure(new TimeoutException(s"Cannot receive any reply from ${remoteAddr} " +
            s"in ${timeout.duration}"))
        }
      }, timeout.duration.toNanos, TimeUnit.NANOSECONDS)
      promise.future.onComplete { v =>
        timeoutCancelable.cancel(true)
      }(ThreadUtils.sameThread)
    } catch {
      case NonFatal(e) =>
        onFailure(e)
    }
    promise.future.mapTo[T].recover(timeout.addMessageIfTimeout)(ThreadUtils.sameThread)
  }

  private[netty] def serialize(content: Any): ByteBuffer = {
    javaSerializerInstance.serialize(content)
  }

  /**
   * Returns [[SerializationStream]] that forwards the serialized bytes to `out`.
   */
  private[netty] def serializeStream(out: OutputStream): SerializationStream = {
    javaSerializerInstance.serializeStream(out)
  }
  
  override def endpointRef(endpoint: RpcEndpoint): RpcEndpointRef = {
    dispatcher.getRpcEndpointRef(endpoint)
  }
}
```
可以看到其中很多操作都依赖于dispatcher成员，现在来细看一下Dispatcher的定义。  
Dispatcher的主要作用是保存注册的RpcEndpoint、分发相应的Message到RpcEndPoint中进行处理。
```scala
/**
 * A message dispatcher, responsible for routing RPC messages to the z endpoint(s).
 */
private[netty] class Dispatcher(nettyEnv: NettyRpcEnv) extends Logging {

  // 内部类，维护一个RpcEndpoint对象与其对应的NettyRpcEndpointRef
  private class EndpointData(
      val name: String,
      val endpoint: RpcEndpoint,
      val ref: NettyRpcEndpointRef) {
    /**
    * An inbox that stores messages for an [[RpcEndpoint]] and posts messages to it thread-safely.
    */  
    val inbox = new Inbox(ref, endpoint)
    
  // 维护一个HaskMap，保存Name与EndpointData的关系
  private val endpoints: ConcurrentMap[String, EndpointData] =
    new ConcurrentHashMap[String, EndpointData]
  // 维护一个HaskMap，保存RpcEndpoint与RpcEndpointRef的关系
  private val endpointRefs: ConcurrentMap[RpcEndpoint, RpcEndpointRef] =
    new ConcurrentHashMap[RpcEndpoint, RpcEndpointRef]
  }
  
  // Track the receivers whose inboxes may contain messages.
  // 维护一个BlockingQueue的队列，用于保存拥有消息的EndpointData，注册Endpoint、
  // 发送消息时、停止RpcEnv时、取消注册的Endpoint时，会在receivers中添加相应的EndpointData
  private val receivers = new LinkedBlockingQueue[EndpointData]
  
  /**
   * True if the dispatcher has been stopped. Once stopped, all messages posted will be bounced
   * immediately.
   */
  // 待理解
  @GuardedBy("this")
  private var stopped = false
  
  // 根据Name和RPCEndpoint，在RpcEnv上进行注册
  // 上文NettyRpcEnv的setupEndpoint方法对其进行了调用
  def registerRpcEndpoint(name: String, endpoint: RpcEndpoint): NettyRpcEndpointRef = {
    // 根据NettyEnv的address和参数Name，创建RpcEndpointAddress
    val addr = RpcEndpointAddress(nettyEnv.address, name)
    // 创建对应的NettyRpcEndpointRef
    val endpointRef = new NettyRpcEndpointRef(nettyEnv.conf, addr, nettyEnv)
    synchronized {
      if (stopped) {
        throw new IllegalStateException("RpcEnv has been stopped")
      }
      // 新建一个EndpointData内部类，里面主要包含一个inbox成员
      // 然后将新创建的EndpointData和对应的Name添加到endpoints中
      if (endpoints.putIfAbsent(name, new EndpointData(name, endpoint, endpointRef)) != null) {
        throw new IllegalArgumentException(s"There is already an RpcEndpoint called $name")
      }
      val data = endpoints.get(name)
      // 将endpoint和对应的endpointRef添加到endpointRefs中
      endpointRefs.put(data.endpoint, data.ref)
      // 在receivers中添加新创建的endpointData
      receivers.offer(data)  // for the OnStart message
    }
    // 返回对应的EndpointRef
    endpointRef
  }
  
  def getRpcEndpointRef(endpoint: RpcEndpoint): RpcEndpointRef = endpointRefs.get(endpoint)

  def removeRpcEndpointRef(endpoint: RpcEndpoint): Unit = endpointRefs.remove(endpoint)
  
  /**
   * Send a message to all registered [[RpcEndpoint]]s in this process.
   *
   * This can be used to make network events known to all end points (e.g. "a new node connected").
   */
  // 向所有已经注册的RpcEndpoint发送消息 
  def postToAll(message: InboxMessage): Unit = {
    val iter = endpoints.keySet().iterator()
    while (iter.hasNext) {
      val name = iter.next
      // PostMessage方法详情见下文
      postMessage(name, message, (e) => logWarning(s"Message $message dropped. ${e.getMessage}"))
    }
  }

  /** Posts a message sent by a remote endpoint. */
  // 发布一个由远端endpoint发送的消息
  def postRemoteMessage(message: RequestMessage, callback: RpcResponseCallback): Unit = {
    val rpcCallContext =
      new RemoteNettyRpcCallContext(nettyEnv, callback, message.senderAddress)
    val rpcMessage = RpcMessage(message.senderAddress, message.content, rpcCallContext)
    postMessage(message.receiver.name, rpcMessage, (e) => callback.onFailure(e))
  }

  /** Posts a message sent by a local endpoint. */
  // 发布一个由本地endpoint发送的消息
  def postLocalMessage(message: RequestMessage, p: Promise[Any]): Unit = {
    val rpcCallContext =
      new LocalNettyRpcCallContext(message.senderAddress, p)
    val rpcMessage = RpcMessage(message.senderAddress, message.content, rpcCallContext)
    postMessage(message.receiver.name, rpcMessage, (e) => p.tryFailure(e))
  }

  /** Posts a one-way message. */
  // 
  def postOneWayMessage(message: RequestMessage): Unit = {
    postMessage(message.receiver.name, OneWayMessage(message.senderAddress, message.content),
      (e) => throw e)
  }

  /**
   * Posts a message to a specific endpoint.
   *
   * @param endpointName name of the endpoint.
   * @param message the message to post
   * @param callbackIfStopped callback function if the endpoint is stopped.
   */
  // 将消息发送给特定的endpoint进行处理
  private def postMessage(
      endpointName: String,
      message: InboxMessage,
      // 当endpoint停止时的回调函数
      callbackIfStopped: (Exception) => Unit): Unit = {
    val error = synchronized {
      val data = endpoints.get(endpointName)
      if (stopped) {
        Some(new RpcEnvStoppedException())
      } else if (data == null) {
        Some(new SparkException(s"Could not find $endpointName."))
      } else {
        // 将Message添加到该endpointData的inbox的message中
        data.inbox.post(message)
        // 将endpointData添加到receivers中
        receivers.offer(data)
        None
      }
    }
    // We don't need to call `onStop` in the `synchronized` block
    error.foreach(callbackIfStopped)
  }
  
  /**
   * Return if the endpoint exists
   */
  // 判断endpoints中是否已经包含对应的endpointName
  def verify(name: String): Boolean = {
    endpoints.containsKey(name)
  }
  
/** Thread pool used for dispatching messages. */
  // 创建一个线程组，用于分发消息
  private val threadpool: ThreadPoolExecutor = {
    // 根据配置项，获得线程组中线程个数
    val numThreads = nettyEnv.conf.getInt("spark.rpc.netty.dispatcher.numThreads",      math.max(2, Runtime.getRuntime.availableProcessors()))
    // 创建线程组
    val pool = ThreadUtils.newDaemonFixedThreadPool(numThreads, "dispatcher-event-loop")
    // 创建多线程，执行相应的MessageLoop
    for (i <- 0 until numThreads) {      
      pool.execute(new MessageLoop)    
    }    
    pool  
  }
  
  /** Message loop used for dispatching messages. */
  private class MessageLoop extends Runnable {
    override def run(): Unit = {
      try {
        while (true) {
          try {
            // 从receivers中获得一个endpointData
            // 由于receivers是LinkBlockingQueue，所以如果receivers中没有元素时，该线程会阻塞
            val data = receivers.take()
            // 获取的元素如果是PoisonPill，将停止该线程，同时 将PoisonPill继续放回receivers中，以便停止所有线程
            if (data == PoisonPill) {
              // Put PoisonPill back so that other MessageLoops can see it.
              receivers.offer(PoisonPill)
              return
            }
            // 调用rpcEndpointData中inbox的process方法，处理响应RpcEndpointData中的Message
            data.inbox.process(Dispatcher.this)
          } catch {
            case NonFatal(e) => logError(e.getMessage, e)
          }
        }
      } catch {
        case ie: InterruptedException => // exit
      }
    }
  }

  /** A poison endpoint that indicates MessageLoop should exit its message loop. */
  private val PoisonPill = new EndpointData(null, null, null)
}

}
```
1. 创建Dispatcher  

    首先声明线程组，并监控receivers是否有新的EndpointData
    - 如果有消息，并且不为PoisonPill，调用相应EndpointData的Inbox的process方法进行消息处理  
  1). 依次从相应的EndpointData的inbox的messages中获取第一个元素  
  2). 匹配消息，并调用对应的endpoint的相应方法进行处理
    - 如果没有消息，则阻塞等待
    - 如果有消息，但是为PoisonPill，则将PoisonPill继续添加到receivers中，然后停止该线程  
1. 根据name和endpoint，在NettyRpcEnv进行注册
    - 根据nettyEnv.conf、RpcEndpointAddress和nettyEnv创建对应的NettyRpcEndpointRef
    - 根据name、endpoint、endpointRef创建新的EndpointData
    - 将name -> EndpointData添加到endpoints中
    - 将endpoint -> endpointRef添加到endpointRefs中
    - 将新建的EndpointData添加到receivers中  
    - 
1. 将InboxMessage消息分发到相应的EndpointData中进行处理
    - 根据Name获取EndpointData
    - 将Message添加到EndpointData的Inbox的messages中
    - 将EndpointData添加到receivers中

[原文链接](http://www.jianshu.com/p/7b32d8c5a1b3)

---

#### RpcEndpoint的定义  
其中receive方法用来处理RpcEndpointRef.send方法发送的消息（接收后不需要响应）  
receiveAndReply用来处理RpcEndpointRef.ask方法发送的消息，而且做出响应
```scala
/**
 * An end point for the RPC that defines what functions to trigger given a message.
 *
 * It is guaranteed that `onStart`, `receive` and `onStop` will be called in sequence.
 *
 * The life-cycle of an endpoint is:
 *
 * {@code constructor -> onStart -> receive* -> onStop}
 *
 * Note: `receive` can be called concurrently. If you want `receive` to be thread-safe, please use
 * [[ThreadSafeRpcEndpoint]]
 *
 * If any error is thrown from one of [[RpcEndpoint]] methods except `onError`, `onError` will be
 * invoked with the cause. If `onError` throws an error, [[RpcEnv]] will ignore it.
 */
private[spark] trait RpcEndpoint {

  /**
   * The [[RpcEnv]] that this [[RpcEndpoint]] is registered to.
   */
  val rpcEnv: RpcEnv

  /**
   * The [[RpcEndpointRef]] of this [[RpcEndpoint]]. `self` will become valid when `onStart` is
   * called. And `self` will become `null` when `onStop` is called.
   *
   * Note: Because before `onStart`, [[RpcEndpoint]] has not yet been registered and there is not
   * valid [[RpcEndpointRef]] for it. So don't call `self` before `onStart` is called.
   */
  final def self: RpcEndpointRef = {
    require(rpcEnv != null, "rpcEnv has not been initialized")
    // rpcEnvd方法endpointRef，返回该RpcEndpoint对应的RpcEndpointRef
    rpcEnv.endpointRef(this)
  }

  /**
   * Process messages from `RpcEndpointRef.send` or `RpcCallContext.reply`. If receiving a
   * unmatched message, `SparkException` will be thrown and sent to `onError`.
   */
  def receive: PartialFunction[Any, Unit] = {
    case _ => throw new SparkException(self + " does not implement 'receive'")
  }

  /**
   * Process messages from `RpcEndpointRef.ask`. If receiving a unmatched message,
   * `SparkException` will be thrown and sent to `onError`.
   */
  def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case _ => context.sendFailure(new SparkException(self + " won't reply anything"))
  }

  /**
   * Invoked when any exception is thrown during handling messages.
   */
  def onError(cause: Throwable): Unit = {
    // By default, throw e and let RpcEnv handle it
    throw cause
  }

  /**
   * Invoked when `remoteAddress` is connected to the current node.
   */
  def onConnected(remoteAddress: RpcAddress): Unit = {
    // By default, do nothing.
  }

  /**
   * Invoked when `remoteAddress` is lost.
   */
  def onDisconnected(remoteAddress: RpcAddress): Unit = {
    // By default, do nothing.
  }

  /**
   * Invoked when some network error happens in the connection between the current node and
   * `remoteAddress`.
   */
  def onNetworkError(cause: Throwable, remoteAddress: RpcAddress): Unit = {
    // By default, do nothing.
  }

  /**
   * Invoked before [[RpcEndpoint]] starts to handle any message.
   */
  def onStart(): Unit = {
    // By default, do nothing.
  }

  /**
   * Invoked when [[RpcEndpoint]] is stopping. `self` will be `null` in this method and you cannot
   * use it to send or ask messages.
   */
  def onStop(): Unit = {
    // By default, do nothing.
  }

  /**
   * A convenient method to stop [[RpcEndpoint]].
   */
  final def stop(): Unit = {
    val _self = self
    if (_self != null) {
      rpcEnv.stop(_self)
    }
  }
}
```

---

#### RpcEndpointRef的定义  
部分节选
```scala
private[spark] abstract class RpcEndpointRef(conf: SparkConf)
  extends Serializable with Logging {
  
  // 最大retry次数
  private[this] val maxRetries = RpcUtils.numRetries(conf)
  // retry等待时长
  private[this] val retryWaitMs = RpcUtils.retryWaitMs(conf)
  // 默认Ask超时时间
  private[this] val defaultAskTimeout = RpcUtils.askRpcTimeout(conf)
  
  /**
   * return the address for the [[RpcEndpointRef]]
   */
  // 地址，一个RpcAddress对象
  def address: RpcAddress

  // RpcEndpointRef的唯一名字
  def name: String
  
  /**
   * Sends a one-way asynchronous message. Fire-and-forget semantics.
   */
  def send(message: Any): Unit
  
  /**
   * Send a message to the corresponding [[RpcEndpoint.receiveAndReply)]] and return a [[Future]] to
   * receive the reply within a default timeout.
   *
   * This method only sends the message once and never retries.
   */
  // ask方法
  def ask[T: ClassTag](message: Any): Future[T] = ask(message, defaultAskTimeout)
  
  /**
   * Send a message to the corresponding [[RpcEndpoint.receiveAndReply]] and get its result within a
   * specified timeout, throw an exception if this fails.
   *
   * Note: this is a blocking action which may cost a lot of time, so don't call it in a message
   * loop of [[RpcEndpoint]].
   *
   * @param message the message to send
   * @param timeout the timeout duration
   * @tparam T type of the reply message
   * @return the reply message from the corresponding [[RpcEndpoint]]
   */
  // askSync方法
  def askSync[T: ClassTag](message: Any, timeout: RpcTimeout): T = {
    val future = ask[T](message, timeout)
    timeout.awaitResult(future)
  }
```
RpcEndpointRef的实现NettyRpcEndpointRef(节选)
```scala
private[netty] class NettyRpcEndpointRef(
    @transient private val conf: SparkConf,
    private val endpointAddress: RpcEndpointAddress,
    @transient @volatile private var nettyEnv: NettyRpcEnv) extends RpcEndpointRef(conf) {
  
  // 声明一个transportClient
  @transient @volatile var client: TransportClient = _
  
  override def address: RpcAddress =
    if (endpointAddress.rpcAddress != null) endpointAddress.rpcAddress else null

  override def name: String = endpointAddress.name

  // 实现ask方法
  override def ask[T: ClassTag](message: Any, timeout: RpcTimeout): Future[T] = {
    nettyEnv.ask(new RequestMessage(nettyEnv.address, this, message), timeout)
  }

  // 实现ask操作
  override def send(message: Any): Unit = {
    require(message != null, "Message is null")
    nettyEnv.send(new RequestMessage(nettyEnv.address, this, message))
  }

```
