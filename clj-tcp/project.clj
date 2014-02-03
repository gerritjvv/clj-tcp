(defproject clj-tcp "0.2.19-SNAPSHOT"
  :description "Fast clojure tcp library based on netty"
  :url "https://github.com/gerritjvv/clj-tcp"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [ 
                  [org.clojure/tools.logging "0.2.3"]
		  [midje "1.6-alpha2" :scope "test"]
      
		  [io.netty/netty-all "4.0.13.Final"]
      [com.taoensso/nippy "2.5.2"]
      [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                  [org.clojure/clojure "1.5.1"]]

 :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"] 
 :java-source-paths ["java"]
 
 :plugins [
         [lein-rpm "0.0.5"] [lein-midje "3.0.1"] [lein-marginalia "0.7.1"]
         [lein-kibit "0.0.8"] [no-man-is-an-island/lein-eclipse "2.0.0"]]

  :warn-on-reflection true
  :repositories {"clojars.org" "http://clojars.org/repo" }
  )
