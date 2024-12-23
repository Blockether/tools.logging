;;; logging.clj -- delegated logging for Clojure

;; by Alex Taggart
;; July 27, 2009

;; Copyright (c) Alex Taggart, July 2009. All rights reserved.  The use
;; and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution.  By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license.  You must not
;; remove this notice, or any other, from this software.
(ns ^{:author "Alex Taggart"
      :doc "Logging macros which delegate to a specific logging implementation.

  A logging implementation is selected at runtime when this namespace is first
  loaded. For more details, see the documentation for *logger-factory*.

  If you want to test that your code emits specific log messages, see the
  clojure.tools.logging.test namespace."}
  clojure.tools.logging
  (:use
   [clojure.string :only [trim-newline]]
   [clojure.pprint :only [code-dispatch pprint with-pprint-dispatch]])
  (:require
   [clojure.tools.logging.util :refer [maybe-deref]]
   [clojure.tools.logging.impl :as impl]))

(defn- string->symbol-safe
  [s]
  (try 
    (symbol s)
  (catch Exception _ex
    nil)))

(defn- dynamic-resolve-factory
  [s]
  (when-let [fq-sym (string->symbol-safe (str s))]
	  (let [ns-str (or (namespace fq-sym)
	                   (throw (RuntimeException.
	                            (format "The value of the clojure.tools.logging.factory system property is not fully-qualified: %s"
	                                    (pr-str s)))))
	        ns-sym (symbol ns-str)
	        _      (try
	                 (require ns-sym)
	                 (catch Exception _ex
	                   (throw (RuntimeException.
	                            (format "Could not resolve namespace for %s. Either it does not exist or it has a (circular) dependency on clojure.tools.logging."
	                                    (pr-str s))))))
	        fn-sym (symbol (name fq-sym))
	        fn-var (ns-resolve ns-sym fn-sym)]
	    (if fn-var
	      @fn-var
	      (throw (RuntimeException.
	               (format "Could not resolve var for %s."
	                       (pr-str s))))))))
	
(defmacro at-compile-time-factory-resolve
  []
  (let [property (System/getProperty "clojure.tools.logging.factory")]
    ;; Require during the compilation time to avoid runtime GraalVM exceptions
    ;; Property should be the same both at compile and runtime
    (if (nil? property)
      `(def custom-logging-factory nil)
      (do
        (dynamic-resolve-factory property)
        `(def custom-logging-factory
           (dynamic-resolve-factory (System/getProperty "clojure.tools.logging.factory")))))))

(at-compile-time-factory-resolve)

(def ^{:doc
  "The default agent used for performing logging when direct logging is
  disabled. See log* for details." :dynamic true}
  *logging-agent* (agent nil :error-mode :continue))

(def ^{:doc
  "The set of levels that will require using an agent when logging from within a
  running transaction. Defaults to #{:info :warn}. See log* for details." :dynamic true}
  *tx-agent-levels* #{:info :warn})

(def ^{:doc
  "Overrides the default rules for choosing between logging directly or via an
  agent. Defaults to nil. See log* for details." :dynamic true}
  *force* nil)

(defn log*
  "Attempts to log a message, either directly or via an agent; does not check if
  the level is enabled.

  For performance reasons, an agent will only be used when invoked within a
  running transaction, and only for logging levels specified by
  *tx-agent-levels*. This allows those entries to only be written once the
  transaction commits, and are discarded if it is retried or aborted.  As
  corollary, other levels (e.g., :debug, :error) will be written even from
  failed transactions though at the cost of repeat messages during retries.

  One can override the above by setting *force* to :direct or :agent; all
  subsequent writes will be direct or via an agent, respectively."
  [logger level throwable message]
  (if (case *force*
        :agent  true
        :direct false
        (and (clojure.lang.LockingTransaction/isRunning)
             (*tx-agent-levels* level)))
    (send-off *logging-agent*
      (fn [_#] (impl/write! logger level throwable message)))
    (impl/write! logger level throwable message))
  nil)

(declare ^{:dynamic true} *logger-factory*) ; default LoggerFactory instance for calling impl/get-logger

(defmacro log
  "Evaluates and logs a message only if the specified level is enabled. See log*
  for more details."
  ([level message]
    `(log ~level nil ~message))
  ([level throwable message]
    `(log ~*ns* ~level ~throwable ~message))
  ([logger-ns level throwable message]
    `(log (maybe-deref *logger-factory*) ~logger-ns ~level ~throwable ~message))
  ([logger-factory logger-ns level throwable message]
    `(let [logger# (impl/get-logger ~logger-factory ~logger-ns)]
       (if (impl/enabled? logger# ~level)
         (log* logger# ~level ~throwable ~message)))))

(defmacro logp
  "Logs a message using print style args. Can optionally take a throwable as its
  second arg. See level-specific macros, e.g., debug.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([level message & more] [level throwable message & more])}
  [level x & more]
  (if (or (instance? String x) (nil? more)) ; optimize for common case
    `(log ~level (print-str ~x ~@more))
    `(let [logger# (impl/get-logger (maybe-deref *logger-factory*) ~*ns*)]
       (if (impl/enabled? logger# ~level)
         (let [x# ~x]
           (if (instance? Throwable x#) ; type check only when enabled
             (log* logger# ~level x# (print-str ~@more))
             (log* logger# ~level nil (print-str x# ~@more))))))))

(defmacro logf
  "Logs a message using a format string and args. Can optionally take a
  throwable as its second arg. See level-specific macros, e.g., debugf.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([level fmt & fmt-args] [level throwable fmt & fmt-args])}
  [level x & more]
  (if (or (instance? String x) (nil? more)) ; optimize for common case
    `(log ~level (format ~x ~@more))
    `(let [logger# (impl/get-logger (maybe-deref *logger-factory*) ~*ns*)]
       (if (impl/enabled? logger# ~level)
         (let [x# ~x]
           (if (instance? Throwable x#) ; type check only when enabled
             (log* logger# ~level x# (format ~@more))
             (log* logger# ~level nil (format x# ~@more))))))))

(defmacro enabled?
  "Returns true if the specific logging level is enabled.  Use of this macro
  should only be necessary if one needs to execute alternate code paths beyond
  whether the log should be written to."
  ([level]
    `(enabled? ~level ~*ns*))
  ([level logger-ns]
    `(impl/enabled? (impl/get-logger (maybe-deref *logger-factory*) ~logger-ns) ~level)))

(defmacro spy
  "Evaluates expr and may write the form and its result to the log. Returns the
  result of expr. Defaults to :debug log level."
  ([expr]
    `(spy :debug ~expr))
  ([level expr]
    `(let [a# ~expr]
       (log ~level
         (let [s# (with-out-str
                    (with-pprint-dispatch code-dispatch ; need a better way
                      (pprint '~expr)
                      (print "=> ")
                      (pprint a#)))]
           (trim-newline s#)))
       a#)))

(defmacro spyf
  "Evaluates expr and may write (format fmt result) to the log. Returns the
  result of expr. Defaults to :debug log level.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  ([fmt expr]
    `(spyf :debug ~fmt ~expr))
  ([level fmt expr]
    `(let [a# ~expr]
       (log ~level (format ~fmt a#))
       a#)))

(defn log-stream
  "Creates a PrintStream that will output to the log at the specified level."
  [level logger-ns]
  (let [logger (impl/get-logger (maybe-deref *logger-factory*) logger-ns)]
    (java.io.PrintStream.
      (proxy [java.io.ByteArrayOutputStream] []
        (flush []
          ; deal with reflection in proxy-super
          (let [^java.io.ByteArrayOutputStream this this]
            (proxy-super flush)
            (let [message (.trim (.toString this))]
              (proxy-super reset)
              (if (> (.length message) 0)
                (log* logger level nil message))))))
      true)))

(let [orig (atom nil)    ; holds original System.out and System.err
      monitor (Object.)] ; sync monitor for calling setOut/setErr
  (defn log-capture!
    "Captures System.out and System.err, piping all writes of those streams to
    the log. If unspecified, levels default to :info and :error, respectively.
    The specified logger-ns value will be used to namespace all log entries.

    Note: use with-logs to redirect output of *out* or *err*.

    Warning: if the logging implementation is configured to output to System.out
    (as is the default with java.util.logging) then using this function will
    result in StackOverflowException when writing to the log."
    ; Implementation Notes:
    ; - only set orig when nil to preserve original out/err
    ; - no enabled? check before making streams since that may change later
    ([logger-ns]
      (log-capture! logger-ns :info :error))
    ([logger-ns out-level err-level]
      (locking monitor
        (compare-and-set! orig nil [System/out System/err])
        (System/setOut  (log-stream out-level logger-ns))
        (System/setErr (log-stream err-level logger-ns)))))
  (defn log-uncapture!
    "Restores System.out and System.err to their original values."
    []
    (locking monitor
      (when-let [[out err :as v] @orig]
        (swap! orig (constantly nil))
        (System/setOut out)
        (System/setErr err)))))

(defmacro with-logs
  "Evaluates exprs in a context in which *out* and *err* write to the log. The
  specified logger-ns value will be used to namespace all log entries.

  By default *out* and *err* write to :info and :error, respectively."
  {:arglists '([logger-ns & body]
               [[logger-ns out-level err-level] & body])}
  [arg & body]
  ; Implementation Notes:
  ; - no enabled? check before making writers since that may change later
  (let [[logger-ns out-level err-level] (if (vector? arg)
                                          arg
                                          [arg :info :error])]
    `(binding [*out* (java.io.OutputStreamWriter.
                       (log-stream ~out-level ~logger-ns))
               *err* (java.io.OutputStreamWriter.
                       (log-stream ~err-level ~logger-ns))]
       ~@body)))

;; level-specific macros

(defmacro trace
  "Trace level logging using print-style args.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(logp :trace ~@args))

(defmacro debug
  "Debug level logging using print-style args.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(logp :debug ~@args))

(defmacro info
  "Info level logging using print-style args.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(logp :info ~@args))

(defmacro warn
  "Warn level logging using print-style args.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(logp :warn ~@args))

(defmacro error
  "Error level logging using print-style args.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(logp :error ~@args))

(defmacro fatal
  "Fatal level logging using print-style args.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(logp :fatal ~@args))

(defmacro tracef
  "Trace level logging using format.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([fmt & fmt-args] [throwable fmt & fmt-args])}
  [& args]
  `(logf :trace ~@args))

(defmacro debugf
  "Debug level logging using format.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([fmt & fmt-args] [throwable fmt & fmt-args])}
  [& args]
  `(logf :debug ~@args))

(defmacro infof
  "Info level logging using format.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([fmt & fmt-args] [throwable fmt & fmt-args])}
  [& args]
  `(logf :info ~@args))

(defmacro warnf
  "Warn level logging using format.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([fmt & fmt-args] [throwable fmt & fmt-args])}
  [& args]
  `(logf :warn ~@args))

(defmacro errorf
  "Error level logging using format.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([fmt & fmt-args] [throwable fmt & fmt-args])}
  [& args]
  `(logf :error ~@args))

(defmacro fatalf
  "Fatal level logging using format.
  Use the 'logging.readable' namespace to avoid wrapping args in pr-str."
  {:arglists '([fmt & fmt-args] [throwable fmt & fmt-args])}
  [& args]
  `(logf :fatal ~@args))

(defn- find-factory []
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (or (and custom-logging-factory (custom-logging-factory))
      (impl/find-factory)))

(def ^:dynamic *logger-factory*
  "An instance satisfying the clojure.tools.logging.impl/LoggerFactory protocol,
  which allows uniform access to an underlying logging implementation.

  The default value will be obtained by invoking a no-arg function named by the
  \"clojure.tools.logging.factory\" system property, or if unset, by invoking
  clojure.tools.logging.impl/find-factory.

  After loading, this var can be programmatically changed to a different
  LoggerFactory implementation via binding or alter-var-root.

  See the various factory functions in clojure.tools.logger.impl."
  (delay (find-factory)))
