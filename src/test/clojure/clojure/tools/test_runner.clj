(ns clojure.tools.test-runner
  (:gen-class)
  (:require
   [clojure.tools.logging.test-impl]
   [clojure.tools.logging.test-test]
   [clojure.tools.logging.readable]
   [clojure.tools.test-logging]
   [clojure.test :as t]))


(defn -main
  [& _args]
  (let [details (t/run-all-tests)]
    (println details)
    (if (or (> (:fail details) 0) (> (:error details) 0))
      (System/exit 1)
      (System/exit 0))))
