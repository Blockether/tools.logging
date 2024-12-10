#!/bin/bash

set -e

lein with-profile clojure-1.12.0,dev,test test
lein with-profile clojure-1.9.0,dev,test test
lein with-profile clojure-1.11.3,dev,test test
