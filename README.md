# clj-tcp

A fast async tcp client library built on netty.

There are many good async http libraries out there, but no lowlevel tcp library that allows you to build your own protocols on top of it.
This library aims to provide this, you send, bytes and receives bytes, what the bytes mean is up to the user.

The code uses ```clojure.core.async``` heavily to transmit messages for read, write and exceptions.


Note: This library is still under construction, but you are welcom to look at the code and try it out.

Contact: feel free to contact me at gerritjvv@gmail.com

## Usage

The binaries are published to https://clojars.org/clj-tcp

```[clj-tcp "0.1.0-SNAPSHOT"]```


```clojure


(use 'clj-tcp.echo-server :reload)
(def s (start-server 2324))
; this starts a test server, that reads echo and closes the channel

(use 'clj-tcp.client :reload)

(def c (client "localhost" 2324 {:reuse-client false}))
(read-print-in c)
(write! c (.getBytes "hi"))
;; read ...
;; any subsequent write! calls will find a closed channel, the connection will reconnect, and retry the send.

```

## Contact

Email: gerritjvv@gmail.com
Twitter: @gerrit_jvv

## License

Copyright © 2013 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
