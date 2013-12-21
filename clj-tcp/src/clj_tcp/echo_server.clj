(ns clj-tcp.echo-server
  (:require [clojure.tools.logging :refer [info error]])
  (:import  
            [java.net InetSocketAddress]
            [io.netty.buffer Unpooled]
            [io.netty.channel ChannelHandler ChannelInboundHandlerAdapter ChannelInitializer ChannelInitializer ChannelHandlerContext ChannelFutureListener]
            [io.netty.channel.nio NioEventLoopGroup]
            [io.netty.bootstrap ServerBootstrap]
            [io.netty.channel.socket.nio NioServerSocketChannel])
  )

(defrecord Server [group channel-future])


(defn ^ChannelHandler echo-handler []
  (proxy [ChannelInboundHandlerAdapter]
    []
    (channelRead [^ChannelHandlerContext ctx msg]
      (info "Received : " msg)
      (.write ctx msg))
    (channelReadComplete [^ChannelHandlerContext ctx]
      (-> ctx (.writeAndFlush Unpooled/EMPTY_BUFFER) 
        (.addListener ChannelFutureListener/CLOSE)))
    (exceptionCaught [^ChannelHandlerContext ctx cause]
      (error cause cause)
      (.close ctx))))


(defn close-server [{:keys [group channel-future]}]
  (-> channel-future .channel .closeFuture .sync)
  (.sync (.shutdownGracefully group)))

(defn ^ChannelInitializer channel-initializer []
  (proxy [ChannelInitializer]
    []
    (initChannel [ch]
        (-> ch (.pipeline) (.addLast (into-array ChannelHandler [(echo-handler)])))
      
      )))
  
(defn start-server [port]
  (let [group (NioEventLoopGroup.)
        b (ServerBootstrap.)
        ]
    (-> b (.group group)
      (.channel NioServerSocketChannel)
      (.localAddress (InetSocketAddress. port))
      (.childHandler (channel-initializer))
      )
    (->Server group (-> b .bind .sync))
    ))
