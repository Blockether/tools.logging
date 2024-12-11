(defproject io.blockether/tools.logging "1.3.5"
  :description "Clojure logging API - blockether.io modification for reflection free resolve of custom logger - GraalVM compatible"
  :url "https://github.com/io.blockether/tools.logging"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src/main/clojure"]
  :test-paths ["src/test/clojure"]
  :profiles {:test {:jvm-opts ["-Dclojure.tools.logging.factory=external.ns/factory"]
                    :source-paths ["src/test/clojure"
                                   "src/main/clojure"]
                    :main clojure.tools.test-runner}
             :clojure-1.12.0 {:dependencies [[org.clojure/clojure "1.12.0"]]}
             :clojure-1.11.3 {:dependencies [[org.clojure/clojure "1.11.3"]]}
             :clojure-1.9.0 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :dev       {:dependencies [[org.clojure/clojure "1.10.3"]
                                        [org.clojure/test.check "1.1.1"]
                                        [org.slf4j/slf4j-api "1.7.32"]
                                        [org.slf4j/slf4j-log4j12 "1.7.32"]
                                        [org.apache.logging.log4j/log4j-api "2.17.1"]
                                        [org.apache.logging.log4j/log4j-core "2.17.1"]
                                        [commons-logging "1.2"]
                                        [criterium "0.4.6"]]}})
