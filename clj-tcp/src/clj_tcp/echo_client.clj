(ns clj-tcp.echo-client
   (:require [clojure.tools.logging :refer [info error]])
   (:import  
            [java.net InetSocketAddress]
            [io.netty.util CharsetUtil]
            [io.netty.buffer Unpooled ByteBuf ByteBufUtil]
            [io.netty.channel SimpleChannelInboundHandler ChannelHandler ChannelInboundHandlerAdapter ChannelInitializer ChannelInitializer ChannelHandlerContext ChannelFutureListener]
            [io.netty.channel.nio NioEventLoopGroup]
            [io.netty.bootstrap Bootstrap]
            [io.netty.channel.socket.nio NioSocketChannel]))

(defrecord Client [group channel-f])

(defn close-client [{:keys [group channel-f]}]
  (-> channel-f .channel .closeFuture .sync)
  (-> group .shutdownGracefully .sync))

(defn client-echo-handler []
  (proxy [SimpleChannelInboundHandler]
    []
    (channelActive [^ChannelHandlerContext ctx]
      (info "active")
      (.writeAndFlush ctx (Unpooled/copiedBuffer "Netty Rocks1" CharsetUtil/UTF_8)))
    (channelRead0 [^ChannelHandlerContext ctx ^ByteBuf in]
      (info "Received")
      (info "Client received : " (ByteBufUtil/hexDump (.readBytes in (.readableBytes in)))))
    (exceptionCaught [^ChannelHandlerContext ctx cause]
      (error cause cause)
      (.close ctx))))
    
(defn ^ChannelInitializer client-channel-initializer []
  (proxy [ChannelInitializer]
    []
    (initChannel [ch]
        (-> ch (.pipeline) (.addLast (into-array ChannelHandler [(client-echo-handler)])))
      
      )))
  

(defn start-client [host port]
  (try
  (let [g (NioEventLoopGroup.)
        b (Bootstrap.)]
    (-> b (.group g)
      (.channel NioSocketChannel)
      (.remoteAddress (InetSocketAddress. host port))
      (.handler (client-channel-initializer)))
    
    (->Client g (-> b .connect .sync)))
  (catch Exception e (error e e))))
    
    
    