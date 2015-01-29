(ns clj-tcp.client
   (:require [clojure.tools.logging :refer [info error]]
             [clj-tcp.codec :refer [byte-decoder default-encoder buffer->bytes]]
             [clojure.core.async.impl.protocols :as asyncp]
             [clojure.core.async :refer [chan >!! go >! <! <!! thread timeout alts!! dropping-buffer sliding-buffer close!]])
   (:import  
            
            [clj_tcp.util PipelineUtil]
            [io.netty.handler.codec ByteToMessageDecoder]
            [java.net InetSocketAddress]
            [java.util List]
            [java.util.concurrent.atomic AtomicInteger AtomicBoolean]
            [io.netty.util CharsetUtil]
            [io.netty.util.concurrent DefaultThreadFactory]
            [io.netty.buffer Unpooled ByteBuf ByteBufUtil]
            [io.netty.channel ChannelOption SimpleChannelInboundHandler ChannelPipeline ChannelFuture Channel ChannelHandler ChannelInboundHandlerAdapter ChannelInitializer ChannelInitializer ChannelHandlerContext ChannelFutureListener]
            [io.netty.channel.nio NioEventLoopGroup]
            [io.netty.util.concurrent GenericFutureListener Future EventExecutorGroup]
            [io.netty.bootstrap Bootstrap]
            [io.netty.channel.socket.nio NioSocketChannel]))

(defn- get-default-threads []
       ;for threads we choose a reasonable default
       (let [n (int (/ (-> (Runtime/getRuntime) .availableProcessors) 2))]
            (if (> n 0) n 1)))

(defrecord Client [group read-group channel-f write-ch read-ch internal-error-ch error-ch ^AtomicInteger reconnect-count ^AtomicBoolean closed])
(defrecord Reconnected [^Client client cause])
(defrecord Pause [time])
(defrecord Stop [])
(defrecord Poison [])
(defrecord FailedWrite [v])

(defonce ALLOCATOR ChannelOption/ALLOCATOR)
(defonce ALLOW-HALF-CLOSURE ChannelOption/ALLOW_HALF_CLOSURE)
(defonce AUTO-READ ChannelOption/AUTO_READ)
(defonce CONNECT-TIMEOUT-MILLIS  ChannelOption/CONNECT_TIMEOUT_MILLIS)
(defonce MAX-MESSAGES-PER-READ ChannelOption/MAX_MESSAGES_PER_READ)
(defonce MESSAGE-SIZE-ESTIMATOR ChannelOption/MESSAGE_SIZE_ESTIMATOR)
(defonce RCVBUF-ALLOCATOR ChannelOption/RCVBUF_ALLOCATOR)
(defonce SO-BACKLOG ChannelOption/SO_BACKLOG)
(defonce SO-KEEPALIVE ChannelOption/SO_KEEPALIVE)
(defonce SO-RCVBUF ChannelOption/SO_RCVBUF)
(defonce SO-REUSEADDR ChannelOption/SO_REUSEADDR)
(defonce SO-SNDBUF ChannelOption/SO_SNDBUF)
(defonce SO-TIMEOUT ChannelOption/SO_TIMEOUT)
(defonce TCP-NODELAY ChannelOption/TCP_NODELAY)

;by default all clients will share this event loop group
(defonce ^NioEventLoopGroup EVENT-LOOP-GROUP (NioEventLoopGroup. (get-default-threads) (DefaultThreadFactory. "global-netty-nio-events" true)))

(defn close-client [{:keys [^NioEventLoopGroup group ^NioEventLoopGroup read-group ^ChannelFuture channel-f internal-error-ch write-ch]}]
      (let [^Channel channel (.channel channel-f)]

           ;only shutdown the event loop groups if they are uniquely created for the connection
           (when-not (identical? group EVENT-LOOP-GROUP)
                     (-> group .shutdownGracefully (.await 2000)))

           (when-not (identical? read-group EVENT-LOOP-GROUP)
                     (-> read-group .shutdownGracefully (.await 2000)))

           (.await ^ChannelFuture (.disconnect channel))
           (.await ^ChannelFuture (.close channel))
           (close! write-ch)
           (close! internal-error-ch)))


(defn close-all [{:keys [group closed] :as conf}]
  (future
	  (try
	    (close-client conf)
	    (catch Exception e (error e (str "Error while closing " conf))))
	   (.set ^AtomicBoolean closed true)
     true))

(defn close-and-wait [conf]
  (deref (close-all conf)))


(defn client-handler [{:keys [group read-ch internal-error-ch write-ch]} decoder]
  (proxy [SimpleChannelInboundHandler]
    []
    (channelActive [^ChannelHandlerContext ctx]
      )
    (channelRead0 [^ChannelHandlerContext ctx in]
      ;(prn "channel read 0")
      ;(info "channelRead0")
      (decoder in))
    (exceptionCaught [^ChannelHandlerContext ctx cause]
      (error "Client-handler exception caught " cause)
      (error cause (>!! internal-error-ch [cause ctx]) )
      (.close ctx))))

(defn- default-decoder [read-ch in]
       (let [d (if (instance? ByteBuf in) (buffer->bytes in) in)]
            (>!! read-ch d)))

(defn ^ChannelInitializer client-channel-initializer [{:keys [^EventExecutorGroup group ^EventExecutorGroup read-group read-ch internal-error-ch write-ch handlers decoder] :as conf}]
   (proxy [ChannelInitializer]
	    []
	    (initChannel [^Channel ch]
        (try 
	        ;add the last default read handler that will send all read objects to the read-ch blocking if full
         ;add any extra handlers e.g. for encoding or deconding
         (let [^ChannelPipeline pipeline (.pipeline ch)] 
           ;(info "create pipeline handlers : " handlers)
	         (if handlers
	            (PipelineUtil/addLast pipeline group  (map #(%) handlers))) ;try adding group

           (if decoder
             (PipelineUtil/addLast pipeline read-group [(client-handler conf (partial decoder read-ch))])
             (PipelineUtil/addLast pipeline read-group [(client-handler conf (partial default-decoder read-ch))]))) ;try adding read-group
         
         (catch Exception e (do 
                              (error (str "channel initializer error " e) e)
                              (go (>! internal-error-ch [e nil]))
                              ))))))

(defn exception-listener
  "Returns a GenericFutureListener instance
   that on completion checks the Future, if any exception
   an error is sent to the error-ch"
  [write-lock-ch v {:keys [internal-error-ch]}]
  (reify GenericFutureListener
    (operationComplete [this f]
       (if (not (.isSuccess ^Future f))
         (do 
             (error "=======>>>>>>>>>>>>>>>>>>>>>>>>>>> Write failed: " f)
		         (if-let [cause (.cause ^Future f)]
		               (do (error "operation complete cause " cause)
		                   (go (>! internal-error-ch [cause (->FailedWrite v)])))))))))

(defn close-listener
  "Close a client after a write operation has been completed"
  [^Client client write-lock-ch {:keys [internal-error-ch]}]
  (reify GenericFutureListener
    (operationComplete [this f]
       ;(go (>! write-lock-ch 1))
       (thread 
               (try
                  (close-client client)
                  (catch Exception e (do
                                       (error (str "Close listener error " e)  e)
                                       (>!! internal-error-ch [e nil]))))))))
           

(defn closed? [client]
      (-> client ^ChannelFuture (:channel-f) ^Channel (.channel) (.isOpen)))

(defn write!
  "Writes and blocks if the write-ch is full"
  [client v]
  (if (closed? client)
    (>!! (:write-ch client) v)
    (throw (RuntimeException. "Client closed"))))
  

(defn read!
  "Reads from the read-ch and blocks if no data is available"
  ([{:keys [read-ch]} timeout-ms]
    (first 
      (alts!!
		     [read-ch
		     (timeout timeout-ms)])))
  ([{:keys [read-ch]}]
  (<!! read-ch)))


(defn read-error 
   "Reads from the error-ch and blocks if no data is available"
   ([{:keys [error-ch]} timeout-ms]
     (if error-ch
		    (first 
		      (alts!!
				     [error-ch
				     (timeout timeout-ms)]))))
		    
  ([{:keys [error-ch]}]
    (when error-ch (<!! error-ch))))



(defn- do-write
  "Writes to the channel, this operation is non blocking and a exception listener is added to the write's ChannelFuture
   to send any errors to the internal-error-ch"
   [^Client client write-lock-ch ^bytes v close-after-write {:keys [internal-error-ch] :as conf}]
   (let [^Channel channel (-> client ^ChannelFuture (:channel-f) ^Channel (.channel))]
     (if (.isOpen channel)
	     (let [ch-f (-> channel ^ChannelFuture (.writeAndFlush v) (.addListener ^ChannelFutureListener (exception-listener write-lock-ch v conf)))]
	       (if close-after-write
	         (.addListener ch-f ^ChannelFutureListener (close-listener client write-lock-ch conf))))
       (throw (RuntimeException. "Channel closed")))))
       

(defn start-client 
  "Start a Client instance with read-ch, write-ch and internal-error-ch"
  ([host port {:keys [group read-group
                      channel-options read-ch internal-error-ch error-ch write-ch handlers reconnect-count closed] :as conf 
                                 :or {
                                      reconnect-count (AtomicInteger. (int 0))
                                      closed (AtomicBoolean. false)
                                      read-ch (chan 10) internal-error-ch (chan 100) error-ch (chan 100) write-ch (chan 100)}}]
  (try
  (let [g (if group group EVENT-LOOP-GROUP)
        b (doto (Bootstrap.) (.option CONNECT-TIMEOUT-MILLIS (int 10000)))]
    
    ;add channel options if specified
    (if channel-options
      (doseq [[channel-option val] channel-options]
        (.option b channel-option val)))
    
    (-> b (.group g)
      ^Bootstrap (.channel NioSocketChannel)
      ^Bootstrap (.remoteAddress (InetSocketAddress. (str host) (int port)))
      ^Bootstrap (.handler ^ChannelInitializer (client-channel-initializer conf)))
    (let [ch-f (.connect b)]
      (.sync ch-f)
      (->Client g read-group ch-f write-ch read-ch internal-error-ch error-ch reconnect-count closed)))
  (catch Exception e (do
                       (error e e)
                       (>!! internal-error-ch [e 1])
                       nil
                       )))))
    
(defn read-print-ch [n ch]
  (go 
    (loop [ch1 ch]
      (if-let [c (<! ch1)]
         (if (instance? Reconnected c)
           (do 
             (recur (-> c :client :read-ch)))
           (do 
             (info n " = " c)
             (recur ch1)))))))

(defn read-print-in [{:keys [read-ch]}]
  (read-print-ch "read" read-ch))


(defn write-poison [{:keys [write-ch read-ch internal-error-ch]}]
  (go (>! write-ch [(->Poison) 1] ))
	(go (>! read-ch (->Poison) ))
  (go (>! internal-error-ch [(->Poison) 1] )))

(defn client [host port {:keys [handlers
                                  channel-options ;io.netty.channel options a sequence of [option val] e.g. [[option val] ... ]
                                  retry-limit
                                  write-buff read-buff error-buff
                                  write-timeout read-timeout
                                  write-group-threads 
                                  read-group-threads
                                  decoder
                                 ] 
                           :or {handlers [default-encoder] retry-limit 5
                                decoder nil
                                write-buff 10 read-buff 5 error-buff 1000 reuse-client false write-timeout 1500 read-timeout 1500
                                }}]
  
  ;(info "Creating read-ch with read-buff " read-buff " write-ch with write-buff " write-buff)
  (let [ 
         write-ch (chan write-buff) 
         read-ch (chan read-buff)
         internal-error-ch (chan error-buff)
         error-ch (chan (sliding-buffer error-buff))
         g (if write-group-threads (NioEventLoopGroup. (int write-group-threads)) EVENT-LOOP-GROUP)
         n-read-group (if read-group-threads (NioEventLoopGroup. (int read-group-threads)) EVENT-LOOP-GROUP)
         conf {:group g :read-group n-read-group 
               :write-ch write-ch :read-ch read-ch :internal-error-ch internal-error-ch :error-ch error-ch :handlers handlers
               :channel-options channel-options
               :reconnect-count (AtomicInteger.) :closed (AtomicBoolean. false)
               :decoder decoder}
         client (start-client host port conf) ]
    ;(info "Creating client with max concurrent writes " max-concurrent-writes  " client " client)
    (if (not client)
      (do 
        (let [cause (read-error {:internal-error-ch internal-error-ch} 200)]
          (throw (RuntimeException. "Unable to create client" (first cause))))))
    
    ;async read off internal-error-ch
    (thread
      (loop [local-client client]
        ;(info "wait -internal error: " internal-error-ch)
       (if-let [x (<!! internal-error-ch)]
        (let [[v o] x
              reconnect-count (.get ^AtomicInteger (:reconnect-count local-client))
              ]
          (error "read error from internal-error-ch " v " reconnect count " reconnect-count)
          (error "is closed " (:closed local-client) " hash " (hash (:closed local-client)))
          (if (> reconnect-count retry-limit) ;check reconnect count
             (do ;if the same connection has been reconnected too many times close
               (try
		              (do 
                           (write-poison local-client)
			               (.set ^AtomicBoolean (:closed local-client) true)
			               (>!! error-ch [v o])) ;send the error channel)
                (catch Exception e (error e e))))
             (do
		          (if (instance? Poison v)
		            (if local-client (close-client local-client)) ;poison pill end loop
		          (do
		            (error "-------------------------- Exception in client " v)
		            (if (instance? Exception v) (error v v)) 
		              
		            ;on error, pause writing, and close client
			        (>!! write-ch (->Pause 1000))
		            
		            (let [c
		                 (loop [acc 0] ;reconnect in loop
						            (if (>= acc retry-limit)
						              (do
		                                ;if limit reached send poinson to all channels and call close all on client, end loop
						                (error "<<<<<<<<<===============  Retry limit reached, closing all channels and connections ===========>>>>>>>>>>>>>")
						                
                                        (write-poison local-client)
						                (try 
                                          (close-all local-client);we do not wait for closing, this consumes extra resources but reconnects are faster
                                          (catch Exception e (error e e)))
                                        (>!! error-ch [v o]) ;write the exception to the error-ch
					                     nil)
							            (let [v1 
		                                  (try
		                                    (let [c (start-client host port conf)
											                    reconnected (->Reconnected c v)]
						                              ;if connected, send Reconnected instance to all channels and return c, this c is assigned to the loop using recur
                                          (.addAndGet ^AtomicInteger (:reconnect-count c) (int (inc reconnect-count)))
                                    
													                (>!! read-ch reconnected)
													                (>!! write-ch reconnected)
											                    c)
											              (catch Exception e (do
											                                   (error (str "Error while doing retry " e) e)
		                                                     e ;return exception to v, due to a bug in core async http://dev.clojure.org/jira/browse/ASYNC-48, we cannot recur here
						                                             )))]
		                            (if (instance? Exception v1) ;if v is an exception recur
		                              (recur (inc acc))
		                              v1) ;else return the value (this is c, the connection)
		                            )))]
		                      
		                      (if (and (instance? FailedWrite o) c)
		                        (do 
		                            (info "retry failed write: ")
		                            (<!! (timeout 500))
		                            (>!! write-ch (:v o))))
		                      
		                      (recur c)))))
		           
			          )))))
		                
                
              
     ;async read off write-ch     
     (thread
	      (loop [local-client client]
	          (let [ write-ch (:write-ch local-client)
	                 v (<!! write-ch)]
             (if v
	               (cond  (instance? Stop v) nil
                        (instance? Reconnected v) (do
	                                                        (error ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>  Writer received reconnected " (:client v))
	                                                        (recur (:client v))) ;if reconnect recur with the new client
						            (instance? Pause v) (do (<!! (timeout (:time v))) (recur local-client)) ;if pause, wait :time millis, then recur loop
						             :else
					                (do 
	                            (try ;else write the value to the client channel
	                             (do-write local-client nil v false conf)
			                         (catch Exception e (do ;send any exception to the internal-error-ch
											                            (error "!!!!! Error while writing " e)
                                                  (>!! internal-error-ch [e v])
			                                    )))
                             (recur local-client))
	                      )))))
	    
    
		    client))


       
		    
