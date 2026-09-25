(ns millhouse.test-modules.identity-attribution
  "Explicit custom attribution contribution for disposable module tests."
  (:require [millhouse.identity :as identity]))

(identity/contribute-attribution!
 :support/caller :support/caller-identity "called")
