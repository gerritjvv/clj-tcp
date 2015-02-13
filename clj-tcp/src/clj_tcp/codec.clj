(ns clj-tcp.codec
  (:require [taoensso.nippy :as nippy]
            [clojure.tools.logging :refer [info error]])
  (:import
          [clojure.lang IFn]
          [clj_tcp.util DefaultMessageToByteEncoder]
          [java.nio ByteBuffer]
          [io.netty.buffer ByteBuf]
          [io.netty.channel ChannelHandlerContext]
          [io.netty.handler.codec MessageToByteEncoder MessageToMessageDecoder ByteToMessageDecoder]
          [java.util List]
          [java.util.concurrent RejectedExecutionException]
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

(defn default-encoder 
  ([]
    (default-encoder true))
  ([^Boolean prever-direct]
  "Acceps 1: a callable and calls it with the ByteBuf as argument
          2: a string 
          3: a byte array
          4: or a ByteBuff
  "
  (DefaultMessageToByteEncoder. #(error % %) prever-direct)))
          
  

(defn byte-decoder []
  (proxy [ByteToMessageDecoder]
    []
    (decode [ctx ^ByteBuf buff ^List out] ;ChannelHandlerContext ctx, ByteBuf in, List<Object> out
            (.println System/out (str "Decoder buff " buff))
      (.add out (buffer->bytes buff))
      )))

(defn byte-encoder []
  (proxy [MessageToByteEncoder]
    []
    (encode [ctx ^bytes bts ^ByteBuf buff]
            (bytes->buffer buff bts))))



