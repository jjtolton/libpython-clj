(ns libpython-clj2.codegen
  "Generate a namespace on disk for a python module or instances"
  (:require [clojure.java.io :as io]
            [libpython-clj2.python :as py]
            [clojure.string :as s]
            [clojure.tools.logging :as log])
  (:import [java.nio.file Paths]
           [java.io Writer]))


(defn- escape-quotes
  [input-str]
  (if input-str
    (-> (.replace (str input-str) "\\" "\\\\")
        (.replace "\"" "\\\""))
    ""))

(defn- get-docs
  [v]
  (escape-quotes (:doc v "No documentation")))


(defn- output-module-list
  [^Writer writer clj-name k v]
  (.write writer
          (format "

(def ^{:doc \"%s\"} %s (as-jvm/generic-python-as-list (py-global-delay (py/get-attr @src-obj* \"%s\")))) "
                  (get-docs v)
                  clj-name
                  k)))


(defn- output-module-tuple
  [^Writer writer clj-name k v]
  (.write writer
          (format "
(def ^{:doc \"%s\"} %s (as-jvm/generic-python-as-list (py-global-delay (py/get-attr @src-obj* \"%s\")))) "
                  (get-docs v)
                  clj-name
                  k)))


(defn- output-module-dict
  [^Writer writer clj-name k v]
  (.write writer
          (format "
(def ^{:doc \"%s\"} %s (as-jvm/generic-python-as-map (py-global-delay (py/get-attr @src-obj* \"%s\")))) "
                  (get-docs v)
                  clj-name
                  k)))


(defn- output-module-callable
  [^Writer writer clj-name k v]
  (.write writer
          (format "

(def %s (as-jvm/generic-callable-pyobject (py-global-delay (py/get-attr @src-obj* \"%s\"))))
(alter-meta! #'%s assoc :doc \"%s\" :arglists '%s)"
                  clj-name
                  k
                  clj-name
                  (get-docs v)
                  (:arglists v))))


(defn- output-module-generic
  [^Writer writer clj-name k v]
  (.write writer
          (format "

(def ^{:doc \"%s\"} %s (as-jvm/generic-pyobject (py-global-delay (py/get-attr @src-obj* \"%s\"))))"
                  (get-docs v)
                  clj-name
                  k)))


(def ^:no-doc default-exclude
  '[+ - * / float double int long mod byte test char short take partition require
    max min identity empty mod repeat str load cast type sort conj
    map range list next hash eval bytes filter compile print set format])


(defn write-namespace!
  "Generate a clojure namespace file from a python module or class.  If python hasn't
  been initialized yet this will call the default python initialization.  Accessing
  the generated namespace without initialization will cause an error.

  Once generated this namespace is safe to be used for AOT,

  Options:

  * `:output-fname` - override the autogenerated file path.
  * `:output-dir` - Defaults \"src\".  Set the output directory.  The final filename,
    if `:output-fname` is not provided, is built up from `:ns-prefix and
    `py-mod-or-cls`.
  * `:ns-symbol` - The fully qualified namespace symbol.  If not provided is built
    from `:ns-prefix` and `py-mod-or-cls`.
  * `:ns-prefix` - The prefix used for all python namespaces.  Defaults to \"python\".
  * `:symbol-name-remaps` - A list of remaps used to avoid name clashes with
     clojure.core or builtin java symbols.
  * `:exclude` - List of symbols used like `(:refer-clojure :exclude %s)`.  You can
    see the default list as `codegen/default-exclude`.

  Example:

```clojure
user> (require '[libpython-clj2.codegen :as codegen])
nil
user> (codegen/write-namespace!
       \"builtins\" {:symbol-name-remaps {\"AssertionError\" \"PyAssertionError\"
                                          \"Exception\" \"PyException\"}})
:ok
user> (require '[python.builtins :as python])
nil
user> (doc python/list)
-------------------------
python.builtins/list
[[self & [args {:as kwargs}]]]
  Built-in mutable sequence.

If no argument is given, the constructor creates a new empty list.
The argument must be an iterable if specified.
nil
user> (doto (python/list)
        (.add 1)
        (.add 2))
[1, 2]
```"
  ([py-mod-or-cls {:keys [output-fname output-dir ns-symbol
                          ns-prefix symbol-name-remaps exclude]
                   :or {output-dir "src"
                        ns-prefix "python"
                        exclude default-exclude}}]
   (let [metadata-fn (requiring-resolve
                      'libpython-clj2.metadata/datafy-module-or-class)
         ns-symbol (or ns-symbol (symbol (str ns-prefix "." py-mod-or-cls)))]
     (py/with-gil-stack-rc-context
       (let [target (py/path->py-obj py-mod-or-cls)
             target-metadata (metadata-fn target)
             output-fname (or output-fname
                              (-> (->> (-> (str ns-symbol)
                                           (.replace "-" "_")
                                           (s/split #"\."))
                                       (into-array String)
                                       (Paths/get output-dir))
                                  (str ".clj")))]
         (log/debugf "Writing python module %s to file %s" target output-fname)
         (io/make-parents output-fname)
         (with-open [writer (io/writer output-fname)]
           (.write writer (format "(ns %s
\"%s\"
(:require [libpython-clj2.python :as py]
          [libpython-clj2.python.jvm-handle :refer [py-global-delay]]
          [libpython-clj2.python.bridge-as-jvm :as as-jvm])
(:refer-clojure :exclude %s))"
                                  (name ns-symbol)
                                  (escape-quotes
                                   (get target "__doc__" "No documentation provided"))
                                  (or exclude [])))
           (.write writer
                   (format "\n\n(defonce ^:private src-obj* (py-global-delay (py/path->py-obj \"%s\")))"
                           py-mod-or-cls))
           (doseq [[k v] target-metadata]
             (when (and (string? k)
                        (map? v)
                        (py/has-attr? target k))
               (let [clj-name (get symbol-name-remaps k k)]
                 ;;If the value is atomic
                 (if (or (string? v)
                         (number? v)
                         (boolean? v))
                   (.write writer (format "\n\n(def ^{:doc %s} %s %s)"
                                          (escape-quotes (:doc v "No documentation"))
                                          clj-name
                                          (if (string? v)
                                            (str "\"" (escape-quotes v) "\"")
                                            v)))
                   (case (:type v)
                     :list (output-module-list writer clj-name k v)
                     :tuple (output-module-tuple writer clj-name k v)
                     :dict (output-module-dict writer clj-name k v)
                     (if (:callable? (:flags v))
                       (output-module-callable writer clj-name k v)
                       (output-module-generic writer clj-name k v))))))))
         :ok))))
  ([py-mod-or-cls]
   (write-namespace! py-mod-or-cls nil)))


(comment
  (do
    (write-namespace! "builtins"
                      {:symbol-name-remaps
                       {"AssertionError" "PyAssertionError"
                        "Exception" "PyException"}})
    (write-namespace! "numpy")
    )
  )