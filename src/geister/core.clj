(ns geister.core
  (:import [java.util.concurrent Future]))

(declare task-fn)

(defmacro task [& body]
  `(let [result# (promise)]
     (task-fn result#
              (delay
               (deliver result#
                        (try
                          [(do ~@body) nil]
                          (catch Throwable t#
                            [nil t#])))
               @result#))))

(defprotocol Bindable
  (bind [thing fun]))

(extend-type clojure.lang.IDeref
  Bindable
  (bind [thing fun]
    (delay @(fun @thing))))

(defprotocol TaskProtocol
  (chain [task fun])
  (get-value [task])
  (to-multi [task])
  (join [a-task b-task]))

(extend-type Future
  TaskProtocol
  (chain [fut fun]
    (chain (task @fut) fun))
  (to-multi [fut]
    (to-multi (task @fut)))
  (join [fut a-task]
    (join (to-multi fut) a-task))
  (get-value [fut] @fut))

(defrecord MultiTask [inner-task]
  TaskProtocol
  (chain [a-task fun]
    (MultiTask. (chain inner-task fun)))
  (get-value [a-task]
    (get-value inner-task))
  (to-multi [a-task] a-task)
  (join [a-task b-task]
    (MultiTask.
     (task
      (lazy-cat @a-task @(to-multi b-task)))))
  clojure.lang.IDeref
  (deref [a-task]
    @inner-task)
  clojure.lang.IFn
  (invoke [a-task]
    (inner-task))
  Runnable
  (run [a-task] (a-task)))

(defrecord Task [result work]
  TaskProtocol
  (chain [a-task fun]
    (task ((fun (a-task)))))
  (get-value [a-task]
    (if (second @result)
      (throw (second @result))
      (first @result)))
  (to-multi [a-task]
    (MultiTask.
     (task [@a-task])))
  (join [a-task b-task]
    (join (to-multi a-task) b-task))
  clojure.lang.IDeref
  (deref [a-task]
    (get-value a-task))
  clojure.lang.IFn
  (invoke [a-task]
    @work
    (get-value a-task))
  Runnable
  (run [a-task] (a-task)))

(defn ^{:dynamic true} *task-handler* [task]
  (.submit clojure.lang.Agent/pooledExecutor ^Runnable task))

(defn task-fn [result work]
  (let [t (Task. result work)]
    (*task-handler* t)
    t))

(defmacro async [bindings & body]
  (if (empty? bindings)
    `(task ~@body)
    (let [[n t & r] bindings]
      `(letfn [(fun# [~n]
                 (async ~(vec r)
                        ~@body))]
         (chain ~t fun#)))))

(defmethod print-method Task [o w]
  (.write w (str o)))

(defmethod print-dup Task [o w]
  (.write w (str o)))

(defmethod print-method MultiTask [o w]
  (.write w (str o)))

(defmethod print-dup MultiTask [o w]
  (.write w (str o)))

(defmacro recur′ [& body]
  (let [n (count body)]
      `(do
         (when (not= ~n ~'asloop-count)
           (throw (IllegalStateException. "retry′ argc mismatch.")))
         (chain (task ~(vec body)) ~'asloop-fn))))

(defmacro loop′ [bindings & body]
  (let [names (vec (take-nth 2 bindings))
        inits (vec (take-nth 2 (rest bindings)))
        n (count inits)]
    `(let [~'asloop-count ~n]
       (chain (task ~inits)
              (fn ~'asloop-fn [~names]
                (let [b# (do ~@body)]
                  (if (extends? TaskProtocol (type b#))
                    b#
                    (task b#))))))))

(defmacro async-loop [& body]
  `(loop′ ~@body))

(defmacro async-recur [& body]
  `(recur′ ~@body))

(comment

  (async [n (task (+ 1 2))
          i (task (+ 3 4))
          y (task "foo")
          x (task (+ n i y))]
         x)
  
  ((fn thisfn [n]
     (if (seq n)
       (chain (task
               (println (first n))
               (rest n))
              thisfn)
       (task nil)))
   (range 10))
    
  (reduce join (map #(task %) (range 10)))
  
  (binding [*task-handler* (fn [t] (t))]
    (let [store (atom 10)]
      (loop′ [n 0 y []]
             (println n)
             (if (pos? @store)
               (recur′ (do
                         (swap! store dec)
                         (inc n))
                       (conj y n))
               [n y @store]))))

  )
