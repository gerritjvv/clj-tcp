(ns clj-tcp.client
   (:require [clojure.tools.logging :refer [info error]]
             [clj-tcp.codec :refer [byte-decoder byte-encoder buffer->bytes]]
             [clojure.core.async :refer [chan >!! go >! <! <!! thread]])
   (:import  
            [java.net InetSocketAddress]
            [java.util.concurrent Future]
            [io.netty.util CharsetUtil]
            [io.netty.buffer Unpooled ByteBuf ByteBufUtil]
            [io.netty.channel SimpleChannelInboundHandler ChannelHandler ChannelInboundHandlerAdapter ChannelInitializer ChannelInitializer ChannelHandlerContext ChannelFutureListener]
            [io.netty.channel.nio NioEventLoopGroup]
            [io.netty.util.concurrent GenericFutureListener]
            [io.netty.bootstrap Bootstrap]
            [io.netty.channel.socket.nio NioSocketChannel]))

(defrecord Client [group channel-f write-ch read-ch error-ch])

(defn close-client [{:keys [group channel-f]}]
  (-> channel-f .channel .closeFuture .sync)
  (-> group .shutdownGracefully .sync))


(defn client-handler [{:keys [group read-ch error-ch write-ch]}]
  (proxy [SimpleChannelInboundHandler]
    []
    (channelActive [^ChannelHandlerContext ctx]
      (info "active")
      ;(.writeAndFlush ctx (Unpooled/copiedBuffer "Netty Rocks1" CharsetUtil/UTF_8))
      )
    (channelRead0 [^ChannelHandlerContext ctx ^ByteBuf in]
      ;(info "Received")
      ;(info "Client received : " (ByteBufUtil/hexDump (.readBytes in (.readableBytes in))))
      (>!! read-ch (buffer->bytes in))
      )
    (exceptionCaught [^ChannelHandlerContext ctx cause]
      (error cause (>!! [cause ctx]) )
      (.close ctx))))
    
(defn ^ChannelInitializer client-channel-initializer [{:keys [group read-ch error-ch write-ch handlers] :as conf}]
  (let [group (NioEventLoopGroup.)]
	  (proxy [ChannelInitializer]
	    []
	    (initChannel [ch]
        (try 
	        ;add the last default read handler that will send all read objects to the read-ch blocking if full
          (-> ch (.pipeline) (.addLast group (into-array ChannelHandler [(client-handler conf)])))
         ;add any extra handlers e.g. for encoding or deconding
          (if handlers
            (-> ch (.pipeline) (.addLast group (into-array ChannelHandler (map #(%) handlers)))))
         (catch Exception e (do 
                              (error e e)
                              (go (>! error-ch [e nil])))))
	      ))))

(defn exception-listener [v {:keys [error-ch]}]
  (reify GenericFutureListener
    (operationComplete [this f]
       (if-let [o (.get ^Future f)]
         (if (instance? Throwable o)
           (go (>! error-ch [o v])))))))

(defn close-listener [^Client client {:keys [error-ch]}]
  "Close a client after a write operation has been completed"
  (reify GenericFutureListener
    (operationComplete [this f]
       (thread 
               (try
                  (close-client client)
                  (catch Exception e (do
                                       (error e e)
                                       (>!! error-ch [e nil]))))))))
           

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
  (info "client " client)
  (try 
     (let [ch-f (-> client :channel-f (.channel) (.writeAndFlush v) (.addListener (exception-listener v conf)))]
       (if close-after-write
         (.addListener ch-f (close-listener client conf))))
     
     (catch Exception e (do 
                          (error e e)
                          (>!! error-ch [e v])))))


(defn start-client [host port {:keys [group read-ch error-ch write-ch handlers] :as conf 
                                 :or {group (NioEventLoopGroup.) read-ch (chan 100) error-ch (chan 100) write-ch (chan 100)}}]
  (try
  (let [g (if group group (NioEventLoopGroup.))
        b (Bootstrap.)]
    (-> b (.group g)
      (.channel NioSocketChannel)
      (.remoteAddress (InetSocketAddress. host port))
      (.handler (client-channel-initializer conf)))
    (let [ch-f (.connect b)]
      (.sync ch-f)
      (->Client g ch-f write-ch read-ch error-ch)))
  (catch Exception e (do
                       (error e e)
                       (>!! error-ch [e nil])))))
    
(defn read-print-ch [n ch]
  (go 
    (while true
      (let [c (<! ch)]
         (info n " = " (String. c))))))

(defn read-print-error [{:keys [error-ch]}]
  (read-print-ch "error" error-ch))

(defn read-print-in [{:keys [read-ch]}]
  (read-print-ch "read" read-ch))


(defn client [host port {:keys [handlers
                                  write-buff read-buff error-buff
                                  write-timeout read-timeout] 
                           :or {handlers [byte-encoder]
                                write-buff 100 read-buff 100 error-buff 1000 reuse-client false write-timeout 1500 read-timeout 1500} }]
  (let [ write-ch (chan write-buff) 
         read-ch (chan read-buff)
         error-ch (chan error-buff)
         g (NioEventLoopGroup.)
         conf {:group g :write-ch write-ch :read-ch read-ch :error-ch error-ch :handlers handlers}
         client (start-client host port conf) ]
    (go 
      (try 
	      (while true
	        (if-let [v (<! write-ch)]
            (do 
	            (do-write client v false conf))))
       (catch Exception e (do 
                            (error e e)
                            (go (>! error-ch [e nil]))))))
    client))         


       
		    