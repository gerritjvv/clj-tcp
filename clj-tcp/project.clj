(defproject clj-tcp "1.0.1"
  :description "Fast clojure tcp library based on netty"
  :url "https://github.com/gerritjvv/clj-tcp"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [ 
                  [org.clojure/tools.logging "0.3.1"]
		              [midje "1.8.3" :scope "test"]

                  [io.netty/netty-all "4.0.36.Final"]
                  [com.taoensso/nippy "2.11.1"]
                  [org.clojure/core.async "0.2.374"]
                  [org.clojure/clojure "1.8.0"]]

 :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"] 
 :java-source-paths ["java"]
 
 :plugins [
         [lein-rpm "0.0.5"] [lein-midje "3.0.1"] [lein-marginalia "0.7.1"]
         [lein-kibit "0.0.8"] [no-man-is-an-island/lein-eclipse "2.0.0"]]

  :warn-on-reflection true
  :repositories {"clojars.org" "http://clojars.org/repo" })
