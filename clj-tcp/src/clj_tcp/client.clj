(ns clj-tcp.client
   (:require [clojure.tools.logging :refer [info error]]
             [clj-tcp.codec :refer [byte-decoder byte-encoder buffer->bytes]]
             [clojure.core.async :refer [chan >!! go >! <! <!! thread timeout]])
   (:import  
            [java.net InetSocketAddress]
            [java.util.concurrent.atomic AtomicInteger]
            [io.netty.util CharsetUtil]
            [io.netty.buffer Unpooled ByteBuf ByteBufUtil]
            [io.netty.channel SimpleChannelInboundHandler ChannelPipeline ChannelFuture Channel ChannelHandler ChannelInboundHandlerAdapter ChannelInitializer ChannelInitializer ChannelHandlerContext ChannelFutureListener]
            [io.netty.channel.nio NioEventLoopGroup]
            [io.netty.util.concurrent GenericFutureListener Future EventExecutorGroup]
            [io.netty.bootstrap Bootstrap]
            [io.netty.channel.socket.nio NioSocketChannel]))

(defrecord Client [group channel-f write-ch read-ch error-ch ^AtomicInteger reconnect-count])


(defrecord Reconnected [^Client client cause])
(defrecord Pause [time])
(defrecord Stop [])
(defrecord Poison [])
(defrecord FailedWrite [v])


(defn close-client [{:keys [group channel-f]}]
  (-> ^ChannelFuture channel-f ^Channel .channel .closeFuture))

(defn close-all [{:keys [group] :as conf}]
  (close-client conf)
  (-> group .shutdownGracefully .sync))


(defn client-handler [{:keys [group read-ch error-ch write-ch]}]
  (proxy [SimpleChannelInboundHandler]
    []
    (channelActive [^ChannelHandlerContext ctx]
      ;(.writeAndFlush ctx (Unpooled/copiedBuffer "Netty Rocks1" CharsetUtil/UTF_8))
      )
    (channelRead0 [^ChannelHandlerContext ctx ^ByteBuf in]
      ;(info "Received")
      ;(info "Client received : " (ByteBufUtil/hexDump (.readBytes in (.readableBytes in))))
      (>!! read-ch (buffer->bytes in))
      )
    (exceptionCaught [^ChannelHandlerContext ctx cause]
      (error "Client-handler exception caught " cause)
      (error cause (>!! [cause ctx]) )
      (.close ctx))))
    
(defn ^ChannelInitializer client-channel-initializer [{:keys [group read-ch error-ch write-ch handlers] :as conf}]
  (let [group (NioEventLoopGroup.)]
	  (proxy [ChannelInitializer]
	    []
	    (initChannel [^Channel ch]
        (try 
	        ;add the last default read handler that will send all read objects to the read-ch blocking if full
          (-> ch  ^ChannelPipeline (.pipeline) (.addLast ^EventExecutorGroup group (into-array ChannelHandler [(client-handler conf)])))
         ;add any extra handlers e.g. for encoding or deconding
          (if handlers
            (-> ch  ^ChannelPipeline (.pipeline) (.addLast group (into-array ChannelHandler (map #(%) handlers)))))
         (catch Exception e (do 
                              (error (str "channel initializer error " e) e)
                              (go (>! error-ch [e nil]))
                              )))
	      ))))

(defn exception-listener [v {:keys [error-ch]}]
  "Returns a GenericFutureListener instance
   that on completion checks the Future, if any exception
   an error is sent to the error-ch"
  (reify GenericFutureListener
    (operationComplete [this f]
       (if (not (.isSuccess ^Future f))
         (if-let [cause (.cause ^Future f)]
               (do (error "operation complete cause " cause)
                   (go (>! error-ch [cause (->FailedWrite v)])))
           )))))

(defn close-listener [^Client client {:keys [error-ch]}]
  "Close a client after a write operation has been completed"
  (reify GenericFutureListener
    (operationComplete [this f]
       (thread 
               (try
                  (close-client client)
                  (catch Exception e (do
                                       (error (str "Close listener error " e)  e)
                                       (>!! error-ch [e nil])
                                       )))))))
           

(defn write! [{:keys [write-ch]} v]
  "Writes and blocks if the write-ch is full"
  (>!! write-ch v))

(defn read! [{:keys [read-ch]}]
  "Reads from the read-ch and blocks if no data is available"
  (<!! read-ch))

(defn read-error [{:keys [error-ch]}]
  "Reads from the error-ch and blocks if no data is available"
  (<!! error-ch))



(defn- do-write [^Client client ^bytes v close-after-write {:keys [error-ch] :as conf}]
  "Writes to the channel, this operation is non blocking and a exception listener is added to the write's ChannelFuture
   to send any errors to the error-ch"
  (try 
    (do 
     (let [ch-f (-> client ^ChannelFuture (:channel-f) ^Channel (.channel) ^ChannelFuture (.writeAndFlush v) (.addListener ^ChannelFutureListener (exception-listener v conf)))]
       (if close-after-write
         (.addListener ch-f ^ChannelFutureListener (close-listener client conf)))))
     (catch Exception e (do 
                          (error (str "Error in do-write " e) e)
                          (>!! error-ch [e v])
                          ))))


(defn start-client [host port {:keys [group read-ch error-ch write-ch handlers] :as conf 
                                 :or {group (NioEventLoopGroup.) read-ch (chan 100) error-ch (chan 100) write-ch (chan 100)}}]
  "Start a Client instance with read-ch, write-ch and error-ch"
  (try
  (let [g (if group group (NioEventLoopGroup.))
        b (Bootstrap.)]
    (-> b (.group g)
      ^Bootstrap (.channel NioSocketChannel)
      ^Bootstrap (.remoteAddress (InetSocketAddress. host port))
      ^Bootstrap (.handler ^ChannelInitializer (client-channel-initializer conf)))
    (let [ch-f (.connect b)]
      (.sync ch-f)
      (->Client g ch-f write-ch read-ch error-ch (AtomicInteger.))))
  (catch Exception e (do
                       (error (str "Error starting client " e) e)
                       (>!! error-ch [e nil])
                       ))))
    
(defn read-print-ch [n ch]
  (go 
    (loop [ch1 ch]
      (let [c (<! ch1)]
         (if (instance? Reconnected c)
           (do 
             ;(info "Reconnected " (:cause c) " ch1 " ch1 " new ch " (-> c :client :read-ch))
             (recur (-> c :client :read-ch)))
           (do 
             (info n " = " c)
             (recur ch1)))))))

(defn read-print-in [{:keys [read-ch]}]
  (read-print-ch "read" read-ch))


(defn client [host port {:keys [handlers
                                  retry-limit
                                  write-buff read-buff error-buff
                                  write-timeout read-timeout] 
                           :or {handlers [byte-encoder] retry-limit 10
                                write-buff 100 read-buff 100 error-buff 1000 reuse-client false write-timeout 1500 read-timeout 1500} }]
  (let [ write-ch (chan write-buff) 
         read-ch (chan read-buff)
         error-ch (chan error-buff)
         g (NioEventLoopGroup.)
         conf {:group g :write-ch write-ch :read-ch read-ch :error-ch error-ch :handlers handlers}
         client (start-client host port conf) ]

    ;async read off error-ch
    (go 
      (loop [local-client client]
        (let [[v o] (<! error-ch)]
          (error "read error from error-ch " v)
          
          (if (instance? Poison v)
            (if local-client (close-client local-client)) ;poinson pill end loop
          (do
            ;on error, pause writing, and close client
	          (>! write-ch (->Pause 1000))
            (close-client local-client)
          
           (let [c 
                 (loop [acc 0] ;reconnect in loop
				            (if (>= acc retry-limit)
				              (do
                        ;if limit reached send poinson to all channels and call close all on client, end loop
				                (error "Retry limit reached, closing all channels and connections")
				                (go (>! write-ch (->Poison) ))
				                (go (>! read-ch (->Poison) ))
				                (go (>! error-ch (->Poison) ))
				                (close-all local-client)
			                  nil
				              )
					            (let [v1 
                             (try 
									              (let [c (start-client host port conf)
									                    reconnected (->Reconnected c v)]
				                          ;if connected, send Reconnected instance to all channels and return c, this c is assigned to the loop using recur
											                (.getAndIncrement ^AtomicInteger (:reconnect-count c))
											                (>! read-ch reconnected)
											                (>! write-ch reconnected)
									                    c)
									              (catch Exception e (do
									                                   (error (str "Error while doing retry " e) e)
                                                     e ;return exception to v, due to a bug in core async http://dev.clojure.org/jira/browse/ASYNC-48, we cannot recur here
				                                             )))]
                            (if (instance? Exception v1) ;if v is an exception recur
                              (recur (inc acc))
                              v1) ;else return the value (this is c, the connection)
                            )))]
                      
                      (if (instance? FailedWrite o) 
                        (do 
                            (info "retry failed write: ")
                            (<! (timeout 500))
                            (>! write-ch (:v o))))
                      
                      (recur c))
           
	          ))))) 
		                
                
              
     ;async read off write-ch     
     (go  
	      (loop [local-client client]
           (let [v (<! write-ch)]
		          (if (instance? Stop v) nil ;if stop exit loop
		             (do 
                   (try 
                      (cond (instance? Reconnected v) (do (recur (:client v))) ;if reconnect recur with the new client
					             (instance? Pause v) (do (<! (timeout (:time v))) (recur local-client)) ;if pause, wait :time millis, then recur loop
					             :else
				                (do ;else write the value to the client channel
                           (if (> (.get ^AtomicInteger (:reconnect-count local-client)) 0) (.set ^AtomicInteger (:reconnect-count local-client) 0))
						               (do-write local-client v false conf)))
							        (catch Exception e (do ;send any exception to the error-ch
								                            (error "!!!!! Error while writing " e)  
								                            (go (>! error-ch [e nil]))
                                    )))
                      (if (not (instance? Stop v))
                         (recur local-client)) ;if not stop recur loop
                      )))))
    
    
		    client))         


       
		    