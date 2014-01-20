(ns clj-tcp.codec-tests
  (require [clj-tcp.codec :refer :all])
  (import [io.netty.buffer Unpooled])
  (use midje.sweet))


(facts "Test codecs"
       
       (fact "Test default handlers"
             
             (let [encoder (default-encoder)]
               (let [buff (Unpooled/buffer 1)]
                ;test send function
                 (.encode encoder nil #(.writeInt % 1) buff)
                 (.readInt buff) => 1)
               (let [buff (Unpooled/buffer 1)
                     arr (byte-array 1)]
                     ;test write string
                     (.encode encoder nil "H" buff)
                     (.readBytes buff arr)
                     (String. arr) => "H")
               (let [buff (Unpooled/buffer 1)
                     buff2 (Unpooled/buffer 4)]
                   (.writeInt buff2 1)
                   (.encode encoder nil buff2 buff)
                   (.readInt buff) => 1)
               (let [buff (Unpooled/buffer 4)
                     arr (byte-array 4)
                     buff2 (Unpooled/buffer 4)]
                 
                   (.writeInt buff2 3)
                   (.readBytes buff2 arr)
                   (.encode encoder nil arr buff)
                   (.readInt buff) => 3))))
