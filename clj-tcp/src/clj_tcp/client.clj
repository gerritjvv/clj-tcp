(ns clj-tcp.client
   (:require [clojure.tools.logging :refer [info error]]
             [clj-tcp.codec :refer [byte-decoder default-encoder buffer->bytes]]
             [clojure.core.async.impl.protocols :as asyncp]
             [clojure.core.async :refer [chan >!! go >! <! <!! thread timeout alts!! dropping-buffer]])
   (:import  
            
            [clj_tcp.util PipelineUtil]
            [io.netty.handler.codec ByteToMessageDecoder]
            [java.net InetSocketAddress]
            [java.util List]
            [java.util.concurrent.atomic AtomicInteger AtomicBoolean]
            [io.netty.util CharsetUtil]
            [io.netty.buffer Unpooled ByteBuf ByteBufUtil]
            [io.netty.channel ChannelOption SimpleChannelInboundHandler ChannelPipeline ChannelFuture Channel ChannelHandler ChannelInboundHandlerAdapter ChannelInitializer ChannelInitializer ChannelHandlerContext ChannelFutureListener]
            [io.netty.channel.nio NioEventLoopGroup]
            [io.netty.util.concurrent GenericFutureListener Future EventExecutorGroup]
            [io.netty.bootstrap Bootstrap]
            [io.netty.channel.socket.nio NioSocketChannel]))

(defrecord Client [group channel-f write-ch read-ch internal-error-ch error-ch ^AtomicInteger reconnect-count ^AtomicBoolean closed])


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

(defn close-client [{:keys [group ^ChannelFuture channel-f]}]
  (let [^Channel channel (.channel channel-f)]
    (.await ^ChannelFuture (.disconnect channel))
    (.await ^ChannelFuture (.close channel))))

(defn close-all [{:keys [group closed] :as conf}]
  
  (try 
    (close-client conf)
    (catch Exception e (error e (str "Error while closing " conf))))
  
   (.set ^AtomicBoolean closed true))


(defn client-handler [{:keys [group read-ch internal-error-ch write-ch]}]
  (proxy [SimpleChannelInboundHandler]
    []
    (channelActive [^ChannelHandlerContext ctx]
      ;(prn "channelActive")
      ;(info "channelActive")
      ;(.writeAndFlush ctx (Unpooled/copiedBuffer "Netty Rocks1" CharsetUtil/UTF_8))
      )
    (channelRead0 [^ChannelHandlerContext ctx in]
      ;(prn "channel read 0")
      ;(info "channelRead0")
      (let [d (if (instance? ByteBuf in) (buffer->bytes in) in)]
         (>!! read-ch d)))
    (exceptionCaught [^ChannelHandlerContext ctx cause]
      (error "Client-handler exception caught " cause)
      (error cause (>!! internal-error-ch [cause ctx]) )
      (.close ctx))))

    

(defn ^ChannelInitializer client-channel-initializer [{:keys [^EventExecutorGroup group ^EventExecutorGroup read-group read-ch internal-error-ch write-ch handlers] :as conf}]
   (proxy [ChannelInitializer]
	    []
	    (initChannel [^Channel ch]
        (try 
	        ;add the last default read handler that will send all read objects to the read-ch blocking if full
         ;add any extra handlers e.g. for encoding or deconding
         (let [^ChannelPipeline pipeline (.pipeline ch)] 
           ;(info "create pipeline handlers : " handlers)
	         (if handlers
	            (PipelineUtil/addLast pipeline group (map #(%) handlers)))
 	            (PipelineUtil/addLast pipeline read-group [(client-handler conf)]))
         
         (catch Exception e (do 
                              (error (str "channel initializer error " e) e)
                              (go (>! internal-error-ch [e nil]))
                              )))
	      )))

(defn exception-listener [write-lock-ch v {:keys [internal-error-ch]}]
  "Returns a GenericFutureListener instance
   that on completion checks the Future, if any exception
   an error is sent to the error-ch"
  (reify GenericFutureListener
    (operationComplete [this f]
       ;(go (>! write-lock-ch 1)) ;release write lock
       (if (not (.isSuccess ^Future f))
         (do 
             (error "=======>>>>>>>>>>>>>>>>>>>>>>>>>>> Write failed: " f)
		         (if-let [cause (.cause ^Future f)]
		               (do (error "operation complete cause " cause)
		                   (go (>! internal-error-ch [cause (->FailedWrite v)])))
		           ))))))

(defn close-listener [^Client client write-lock-ch {:keys [internal-error-ch]}]
  "Close a client after a write operation has been completed"
  (reify GenericFutureListener
    (operationComplete [this f]
       ;(go (>! write-lock-ch 1))
       (thread 
               (try
                  (close-client client)
                  (catch Exception e (do
                                       (error (str "Close listener error " e)  e)
                                       (>!! internal-error-ch [e nil])
                                       )))))))
           

(defn write! [{:keys [write-ch]} v]
  "Writes and blocks if the write-ch is full"
  (>!! write-ch v))

(defn read! 
  ([{:keys [read-ch]} timeout-ms]
    (first 
      (alts!!
		     [read-ch
		     (timeout timeout-ms)])))
  ([{:keys [read-ch]}]
  "Reads from the read-ch and blocks if no data is available"
  (<!! read-ch)))


(defn read-error 
   ([{:keys [error-ch]} timeout-ms]
     (if error-ch
		    (first 
		      (alts!!
				     [error-ch
				     (timeout timeout-ms)]))))
		    
  ([{:keys [error-ch]}]
  "Reads from the error-ch and blocks if no data is available"
    (if error-ch (<!! error-ch))))



(defn- do-write [^Client client write-lock-ch ^bytes v close-after-write {:keys [internal-error-ch] :as conf}]
  "Writes to the channel, this operation is non blocking and a exception listener is added to the write's ChannelFuture
   to send any errors to the internal-error-ch"
  (try 
    (do 
      ;(info "Write and flush value " v)
     (let [ch-f (-> client ^ChannelFuture (:channel-f) ^Channel (.channel) ^ChannelFuture (.writeAndFlush v) (.addListener ^ChannelFutureListener (exception-listener write-lock-ch v conf)))]
       (if close-after-write
         (.addListener ch-f ^ChannelFutureListener (close-listener client write-lock-ch conf)))))
     (catch Exception e (do 
                          ;(go (>! write-lock-ch 1));if exception release write lock
                          (error e (str "Error in do-write " e))
                          (>!! internal-error-ch [e v])
                          ))))


(defn start-client 
  ([host port {:keys [group read-group 
                      channel-options read-ch internal-error-ch error-ch write-ch handlers reconnect-count closed] :as conf 
                                 :or {group (NioEventLoopGroup.)
                                      read-group (NioEventLoopGroup.)
                                      reconnect-count (AtomicInteger. (int 0))
                                      closed (AtomicBoolean. false)
                                      read-ch (chan 1000) internal-error-ch (chan 100) error-ch (chan 100) write-ch (chan 1000)}}]
  "Start a Client instance with read-ch, write-ch and internal-error-ch"
  (try
  (let [g (if group group (NioEventLoopGroup.))
        b (Bootstrap.)]
    
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
      (->Client g ch-f write-ch read-ch internal-error-ch error-ch reconnect-count closed)))
  (catch Exception e (do
                       (error e e)
                       (>!! internal-error-ch [e 1])
                       )))))
    
(defn read-print-ch [n ch]
  (go 
    (loop [ch1 ch]
      (let [c (<! ch1)]
         (if (instance? Reconnected c)
           (do 
             (recur (-> c :client :read-ch)))
           (do 
             (info n " = " c)
             (recur ch1)))))))

(defn read-print-in [{:keys [read-ch]}]
  (read-print-ch "read" read-ch))


(defn- create-write-lock-ch [max-concurrent-writes]
  (let [write-lock-ch (chan (dropping-buffer max-concurrent-writes))]
    (go 
      (doseq [i (range max-concurrent-writes)]
        (>! write-lock-ch 1)))))

(defn write-poison [{:keys [write-ch read-ch internal-error-ch]}]
  (go (>! write-ch [(->Poison) 1] ))
	(go (>! read-ch (->Poison) ))
  (go (>! internal-error-ch [(->Poison) 1] )))

(defn client [host port {:keys [handlers
                                  channel-options ;io.netty.channel options a sequence of [option val] e.g. [[option val] ... ]
                                  retry-limit
                                  write-buff read-buff error-buff
                                  write-timeout read-timeout
                                  write-group 
                                  read-group
                                 ] 
                           :or {handlers [default-encoder] retry-limit 5
                                write-buff 10 read-buff 5 error-buff 1000 reuse-client false write-timeout 1500 read-timeout 1500
                                } }]
  
  ;(info "Creating read-ch with read-buff " read-buff " write-ch with write-buff " write-buff)
  (let [ write-lock-ch nil ;(create-write-lock-ch max-concurrent-writes)
         write-ch (chan write-buff) 
         read-ch (chan read-buff)
         internal-error-ch (chan error-buff)
         error-ch (chan error-buff)
         g (if write-group write-group (NioEventLoopGroup.))
         n-read-group (if read-group read-group (NioEventLoopGroup.))
         conf {:group g :read-group n-read-group 
               :write-ch write-ch :read-ch read-ch :internal-error-ch internal-error-ch :error-ch error-ch :handlers handlers
               :channel-options channel-options
               :reconnect-count (AtomicInteger.) :closed (AtomicBoolean. false)}
         client (start-client host port conf) ]
    ;(info "Creating client with max concurrent writes " max-concurrent-writes  " client " client)
    (if (not client)
      (do 
        (let [cause (read-error {:internal-error-ch internal-error-ch} 200)]
          (throw (RuntimeException. "Unable to create client" (first cause))))))
    
    ;async read off internal-error-ch
    (go 
      (loop [local-client client]
        ;(info "wait -internal error: " internal-error-ch)
        (let [[v o] (<! internal-error-ch)
              reconnect-count (.get ^AtomicInteger (:reconnect-count local-client))
              ]
          (error "read error from internal-error-ch " v " reconnect count " reconnect-count)
          (error "is closed " (:closed local-client) " hash " (hash (:closed local-client)))
          (if (> reconnect-count retry-limit) ;check reconnect count
             (do ;if the same connection has been reconnected too many times close
               (try
		              (do 
                    (write-poison local-client)
			               ;(try
			                ;   (close-all local-client)
			                 ;  (catch Exception (.printStackTrace e)))
			               
			               (.set ^AtomicBoolean (:closed local-client) true)
			               (>! error-ch [v o]) ;send the error channel
                    )
                (catch Exception e (error e e)))
               )
             (do
		          (if (instance? Poison v)
		            (if local-client (close-client local-client)) ;poinson pill end loop
		          (do
                (error "-------------------------- Exception in client " v)
		            (if (instance? Exception v) (error v v)) 
		              
		            ;on error, pause writing, and close client
			          (>! write-ch (->Pause 1000))
		            
		           (let [c 
		                 (loop [acc 0] ;reconnect in loop
						            (if (>= acc retry-limit)
						              (do
		                        ;if limit reached send poinson to all channels and call close all on client, end loop
						                (error "<<<<<<<<<===============  Retry limit reached, closing all channels and connections ===========>>>>>>>>>>>>>")
						                
                            (write-poison local-client)
						                ;(try 
                             ;    (close-all local-client)
                              ;   (catch Exception e (error e e)))
                            (>! error-ch [v o]) ;write the exception to the error-ch
					                  nil
						              )
							            (let [v1 
		                             (try 
											              (let [c (start-client host port conf)
											                    reconnected (->Reconnected c v)]
						                              ;if connected, send Reconnected instance to all channels and return c, this c is assigned to the loop using recur
                                          (.addAndGet ^AtomicInteger (:reconnect-count c) (int (inc reconnect-count)))
                                    
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
		                      
		                      (if (and (instance? FailedWrite o) c)
		                        (do 
		                            (info "retry failed write: ")
		                            (<! (timeout 500))
		                            (>! write-ch (:v o))))
		                      
		                      (recur c)))))
		           
			          ))))
		                
                
              
     ;async read off write-ch     
     (go
	      (loop [local-client client]
	          (let [ write-ch (:write-ch local-client)
	                 v (<! write-ch)]
	               (cond  (instance? Stop v) nil
                        (instance? Reconnected v) (do
	                                                        (error ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>  Writer received reconnected " (:client v))
	                                                        (recur (:client v))) ;if reconnect recur with the new client
						            (instance? Pause v) (do (<! (timeout (:time v))) (recur local-client)) ;if pause, wait :time millis, then recur loop
						             :else
					                (do 
	                            (try ;else write the value to the client channel
	                            (do-write local-client nil v false conf)
			                         (catch Exception e (do ;send any exception to the internal-error-ch
											                            (error "!!!!! Error while writing " e)  
											                            (thread (>!! internal-error-ch [e 1]))
			                                    )))
                             (recur local-client))
	                      )))
                 
                  )
	    
    
		    client))         


       
		    