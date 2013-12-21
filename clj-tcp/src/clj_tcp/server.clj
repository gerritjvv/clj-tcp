(ns clj-tcp.server
  (:require
           [clj-tcp.codec :refer :all]
           [clojure.tools.logging :refer [info error]])
  (:import [io.netty.bootstrap ServerBootstrap]
           [io.netty.channel ChannelOption ChannelHandler ChannelFuture ChannelInitializer EventLoopGroup ChannelInboundHandlerAdapter] 
           [io.netty.buffer ByteBuf]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.channel.socket SocketChannel]
           [io.netty.channel.socket.nio NioServerSocketChannel]))


(defn event-loop-group1 [] 
  (NioEventLoopGroup.))

(defn echo-handler []
  (proxy 
    [ChannelInboundHandlerAdapter]
    []
    (channelRead [ctx msg]
      (try 
       (do 
           (prn "!!!!!! Echo server writing")
           (doto ctx (.write msg) (.flush)))
       (catch Exception e (.printStackTrace e))))
      
    (exceptionCaught [ctx cause]
      (.printStackTrace cause)
      (error cause cause)
      )))

(defn channel-initializer1 [handlers]
  (proxy [ChannelInitializer]
    []
    (initChannel [ch]
      (let [p (.pipeline ch) ]
         (.addLast p (into-array ChannelHandler (map #(%) handlers)))
         (.addLast p (into-array ChannelHandler [(default-encoder) (default-decoder)]))))))

(defn server [port & handlers]
  (let [boss-group (event-loop-group1)
        worker-group (event-loop-group1)
        b (ServerBootstrap.)]
    (-> b
    (.group  boss-group worker-group)
    (.channel NioServerSocketChannel)
    (.childHandler (channel-initializer1 handlers))
    (.option ChannelOption/SO_BACKLOG (int 128))
    (.childOption ChannelOption/SO_KEEPALIVE true))
   
    (future 
			  (-> (.bind b (int port)) (.sync) 
		      (.channel) (.closeFuture) (.sync)
		      (.shutdownGracefully worker-group)
		      (.shutdownGracefully boss-group)))))

(defn test-server []
  (server 8686 echo-handler))
