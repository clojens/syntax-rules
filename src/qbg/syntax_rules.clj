(ns qbg.syntax-rules
  (:require
   [qbg.syntax-rules.core :as core]
   [qbg.syntax-rules.pattern :as pattern]
   [qbg.syntax-rules.template :as temp]
   [clojure.template :as t]))

(defn- throw-match-error
  [name results line file]
  (let [res (first (sort-by :progress (comparator core/order-progress) results))
	mesg (let [d (:describe res)]
	       (if (vector? d)
		 (let [[mesg cause] (:describe res)] 
		   (format "%s in: %s (%s:%d)" mesg cause file line))
		 (format "%s (%s:%d)" d file line)))]
    (throw (Exception. (format "%s: %s" name mesg)))))

(defn- perform-match
  [form rt]
  (let [[rule template] rt]
    (assoc (pattern/match rule form)
      :template template)))

(defn syntax-to-form-evaluating
  "Functional version of syntax for advanced users"
  [template fns]
  (temp/fill-template template fns))

(defn syntax-to-form
  "Simplified functional version of syntax"
  ([template]
     (syntax-to-form template []))
  ([template literals]
     (let [[parsed fns] (temp/parse template literals)
	   fns (zipmap (keys fns) (map eval (vals fns)))]
       (syntax-to-form-evaluating parsed fns))))

(defmacro syntax
  "Fill in the template and return it. Must be called in context of a pattern match."
  ([template]
     `(syntax ~template []))
  ([template literals]
     (assert (vector? literals))
     (let [[template fns] (temp/parse template literals)]
       `(syntax-to-form-evaluating '~template ~fns))))

(defn absent?
  "Return true if variable was not bound in the enclosing match."
  [variable]
  (not (temp/contains-var? variable)))

(defn make-apply-cases
  "Return a fn that will try each rule in turn on its argument. Upon the first
success, call the corresponding thunk with the match result implicitly. If none
match, throw an appropriate exception, using name to describe the source of the
error."
  [name rules thunks]
  (let [file *file*]
    (fn [form]
      (let [rt (map vector rules thunks)
	    results (map (partial perform-match form) rt)
	    line (:line (meta form))]
	(if-let [m (first (filter :good results))]
	  (binding [core/*current-match* m]
	    ((:template m)))
	  (throw-match-error name results line file))))))

(defmacro defsyntax-case
  "syntax-case version of defsyntax-rules"
  [name docstring literals & rt-pairs]
  (assert (vector? literals))
  (let [rules (take-nth 2 rt-pairs)
	patternate (fn [r] `(pattern/pattern ~literals ~r))
	good-rules (vec (map patternate rules))
	thunks (take-nth 2 (rest rt-pairs))
	thunkify (fn [c] `(fn [] ~c))
	thunks (vec (map thunkify thunks))]
    `(let [ac# (make-apply-cases '~name ~good-rules ~thunks)]
       (defmacro ~name
	 ~docstring
	 {:arglists '~rules}
	 [& ~'forms]
	 (ac# ~'&form)))))

(defmacro defsyntax-rules
  "Define a macro that uses the rule-template pairs to expand all invokations"
  [name docstring literals & rt-pairs]
  (assert (vector? literals))
  (let [rules (take-nth 2 rt-pairs)
        templates (take-nth 2 (rest rt-pairs))
	cases (map (fn [t] `(syntax ~t ~literals)) templates)
	rt-pairs (interleave rules cases)]
    `(defsyntax-case ~name
       ~docstring
       ~literals
       ~@rt-pairs)))

(defmacro defsyntax-class
  "Define a new syntax class"
  [name args description literals & body]
  (let [temp (fn [form] `(syntax ~form ~literals))
	pat (pattern/parse-syntax-class description temp literals body)]
    `(defn ~name
       ~(format "The %s syntax class" name)
       ~args
       ~pat)))

(defn check-duplicate
  "Return the duplicate item in coll if there is one, or false"
  [coll]
  (loop [seen #{}, coll coll]
    (if (seq coll)
      (if (contains? seen (first coll))
	(first coll)
	(recur (conj seen (first coll)) (next coll)))
      false)))

(defn pred-check
  "Return false if (pred coll) is true, coll otherwise"
  [pred coll]
  (if (pred coll)
    false
    coll))

(t/do-template
 [name main-descript descript pred]
 (defsyntax-class name []
   main-descript
   []
   form
   :fail-when (pred-check pred (syntax form)) descript)

 c-symbol "symbol" "expected symbol" symbol?
 c-number "number" "expected number" number?
 c-keyword "keyword" "expected keyword" keyword?
 c-map "map" "expected map" map?
 c-set "set" "expected set" set?
 c-string "string" "expected string" string?)

(defsyntax-class c-pred [pred mesg]
  "form satsifying predicate"
  []
  form
  :fail-when
  (pred-check pred (syntax form))
  (format "expected %s" mesg))
