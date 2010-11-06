(ns die-geister.test.core
  (:use [geister.core] :reload)
  (:use [clojure.test]))

(deftest test-async
  (let [Q (reduce #(doto % (.put %2)) (java.util.concurrent.LinkedBlockingQueue.)
                  (range 10))
        T (loop′ [items []]
                 (Thread/sleep 100)
                 (if-not (empty? Q)
                   (recur′ (conj items (.take Q)))
                   items))
        T′ (async [w T
                   x (future 2)]
                  (+ (first w) x))]
    (is (not (empty? Q)))
    (is (= (range 10) @T))
    (is (= 2 @T′))
    (is (empty? Q))))
