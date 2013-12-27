(ns clj-tcp.codec
  (:require [taoensso.nippy :as nippy]
            [clojure.tools.logging :refer [info error]])
  (:import
          [clojure.lang IFn]
          [java.nio ByteBuffer]
          [io.netty.buffer ByteBufAllocator]
          [io.netty.buffer ByteBuf]
          [io.netty.channel ChannelHandlerContext]
          [io.netty.handler.codec MessageToByteEncoder MessageToMessageDecoder ByteToMessageDecoder]
          [java.util List]
          [java.util.concurrent Callable]
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

(defn default-encoder []
  "Acceps 1: a callable and calls it with the ByteBuf as argument
          2: a string 
          3: a byte array
          4: or a ByteBuff
  "
  (proxy [MessageToByteEncoder]
    []
    (encode[ctx msg ^ByteBuf buff]
      (cond
          (instance? IFn msg) (msg buff)
          (instance? String) (.writeBytes buff (.getBytes ^String msg "UTF-8"))
          (instance? ByteBuf) (.writeBytes buff ^ByteBuf msg)
          :else (.writeBytes buff ^bytes msg)))))
          
  

(defn byte-decoder []
  (proxy [ByteToMessageDecoder]
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



