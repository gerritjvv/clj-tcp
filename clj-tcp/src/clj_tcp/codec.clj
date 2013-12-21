(ns clj-tcp.codec
  (:require [taoensso.nippy :as nippy]
            [clojure.tools.logging :refer [info error]])
  (:import
          [java.nio ByteBuffer]
          [io.netty.buffer ByteBufAllocator]
          [io.netty.buffer ByteBuf]
          [io.netty.channel ChannelHandlerContext]
          [io.netty.handler.codec MessageToByteEncoder MessageToMessageDecoder]
          [java.util List]
  ))


(defn ^bytes buffer->bytes [^ByteBuf buff]
  (let [read-len (.readableBytes buff)
        ^bytes arr (byte-array read-len)]
    (.readBytes buff arr (int 0) (int read-len))
    arr))

(defn ^ByteBuf bytes->buffer [^ByteBuf buff ^bytes bts]
  (let [cnt (count bts)
        writer-i (.writerIndex buff)]
    (.capacity buff cnt)
    (.setBytes buff (int writer-i) bts)
    (.writerIndex buff (int (+ writer-i cnt)))
    buff))


(defn byte-decoder []
  (proxy [MessageToMessageDecoder]
    []
    (decode [ctx ^ByteBuf buff ^List out] ;ChannelHandlerContext ctx, ByteBuf in, List<Object> out
      (.add out (buffer->bytes buff))
      )))

(defn byte-encoder []
  (proxy [MessageToByteEncoder]
    []
    (encode [ctx ^bytes bts ^ByteBuf buff]
      (bytes->buffer buff bts)
      )))



