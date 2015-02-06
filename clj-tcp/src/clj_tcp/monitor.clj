(ns clj-tcp.monitor
    (:import
      (java.lang.ref WeakReference)
      (java.util UUID)))

;; Usage  (the label usage is meant for organisational support
;;
;;  (join-ctx :my-grouping conn)
;;  on close of connection
;;  (leave-ctx :my-grouping conn)


;global monitoring data for number of open connections.
;; all connections are stored as weak references so as no to cause a memory leak
(defonce monitor-ctx (atom {}))

(defn- weak [o]
       (WeakReference. o))

(defn- ensure-vec [lbl]
       [lbl])

(defn unique-id [] (UUID/randomUUID))

(defn current-stacktrace [] (.getStackTrace (Thread/currentThread)))

(defn join-ctx
      ([lbl conn]
        (join-ctx lbl (unique-id) conn))
      ([lbl id conn]
        (swap! monitor-ctx update-in (ensure-vec lbl) #(assoc % id {:conn conn :stacktrace (current-stacktrace) :ts (System/currentTimeMillis)}))
        id))

(defn leave-ctx [lbl id]
      (swap! monitor-ctx update-in (ensure-vec lbl) #(dissoc % id)))

(defn- safe-in [a b]
       (if a (+ ^long a ^long b) b))

(defn- count-items [m]
       (count (vals m)))

(defn report-counts
      "Report a map with {:lbl cnt}"
      []
      (reduce-kv (fn [m k v] (assoc m k (count-items v))) {} @monitor-ctx))

