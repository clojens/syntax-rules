(ns qbg.syntax-rules.template-fill)

(declare fill-form fill-amp)

(defn- fill-literal
  [form state mappings]
  (second form))

(defn- fill-simple-variable
  [variable state]
  (let [v (get (:vars state) variable)]
    (if (= (:amp-depth v) 0)
      (:val v)
      (throw (IllegalStateException. "Inconsistent ampersand depth")))))

(defn- fill-symbol
  [sym mappings]
  (if (contains? mappings sym)
    (get mappings sym)
    ;; Hack
    (symbol (subs (str (resolve sym)) 2))))

(defn- fill-variable
  [form state mappings]
  (cond
   (> (count form) 2)
   (recur (next form) (get (:varm state) (second form)) mappings)

   (not (contains? (:vars state) (second form)))
   (fill-symbol (second form) mappings)
   
   :else (fill-simple-variable (second form) state)))

(defn- fill-seq
  [form state mappings]
  (loop [res [], form form]
    (if (seq form)
      (if (= (first (first form)) :amp)
        (recur (into res (fill-amp (first form) state mappings)) (next form))
        (recur (conj res (fill-form (first form) state mappings)) (next form)))
      res)))

(defn- fill-list
  [form state mappings]
  (seq (fill-seq (rest form) state mappings)))

(defn- fill-vector
  [form state mappings]
  (fill-seq (rest form) state mappings))

(defn- get-var-length
  [vars state]
  (count (:val (get (:vars state) (first vars)))))

(defn- assert-vars
  [vars state]
  (let [lengths (map #(count (:val (get (:vars state) %))) vars)]
    (if (apply = lengths)
      true
      (throw (IllegalStateException. "Variables under ampersand do not have equal lengths")))))

(defn- demote-vars
  [vars state]
  (let [vstate (:vars state)
	vstate (reduce (fn [state v]
			 (let [sym (get state v)
			       sym (assoc sym
				     :amp-depth (dec (:amp-depth sym))
				     :val (first (:val sym)))]
			   (assoc state v sym)))
		       vstate vars)]
    (assoc state :vars vstate)))

(defn- drop-vars
  [vars state]
  (let [vstate (:vars state)
	vstate (reduce (fn [state v]
			 (let [sym (get state v)
			       sym (assoc sym :val (rest (:val sym)))]
			   (assoc state v sym)))
		       vstate vars)]
    (assoc state :vars vstate)))

(defn- fill-amp
  [form state mappings]
  (let [[_ vars & forms] form
        length (get-var-length vars state)]
    (assert-vars vars state)
    (loop [res [], n 0, state state]
      (if (< n length)
        (recur
          (conj res (fill-seq forms (demote-vars vars state) mappings))
          (inc n)
          (drop-vars vars state))
        (apply concat res)))))

(defn- fill-form
  [form state mappings]
  ((condp = (first form)
    :variable fill-variable
    :list fill-list
    :vector fill-vector
    :amp fill-amp
    :literal fill-literal)
    form state mappings))

(defn- find-symbols
  ([state]
    #{})
  ([state form]
    (condp = (first form)
	:variable (if (contains? (:vars state) (second form))
		    #{(second form)}
		    #{})
	:list (apply find-symbols (rest form))
	:vector (apply find-symbols (rest form))
	:amp (apply find-symbols (rest (rest form)))
	:literal #{}))
  ([state form & forms]
     (reduce into (find-symbols state form)
	     (map #(find-symbols state %) forms))))

(defn- make-mappings
  [syms]
  (let [needed (filter #(or (nil? %) (not (resolve %))) syms)
        mappings (zipmap needed (map gensym needed))]
    (reduce #(assoc %1 %2 %2)
	    mappings '[quote def var recur do if throw try monitor-enter monitor-exit
		       . new set!])))

(defn fill-template
  [form state]
  (fill-form form state (make-mappings (find-symbols state form))))
