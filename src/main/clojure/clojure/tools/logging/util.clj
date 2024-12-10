(ns clojure.tools.logging.util
  (:import
   [clojure.lang IDeref]))

(defn maybe-deref
  [x]
  (if-not (instance? IDeref x)
    x
    (.deref ^IDeref x)))
