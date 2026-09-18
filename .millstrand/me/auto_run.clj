(ns millhouse.auto-run
  "Activate bounded automatic pickup using this repository's delivery workflows."
  (:require [clojure.java.io :as io]
            [ct.spools.codethread.auto-run :as auto-run]
            [ct.spools.codethread.auto-run-worktree]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/use-op! auto-run/auto-run)

(defn open!
  "Configure two Millhouse worker slots, defaulting to human-reviewed delivery."
  [{:keys [runtime]}]
  (auto-run/configure!
   runtime
   {:repo (.getCanonicalPath (.getParentFile (io/file (get-in runtime [:metadata :config-dir]))))
    :seat "sol"
    :effort "low"
    :workflow "auto-human-review"
    :workflows #{"auto-human-review" "auto-full-land"}
    :prepare 'ct.spools.codethread.auto-run-worktree/prepare!
    :enabled? true
    :max-running 2
    :interval-ms 15000}))

(defn close!
  "Stop new admissions without stopping any accepted worker."
  [{:keys [runtime]}]
  (auto-run/stop! runtime))

(lifecycle/defresource! auto-run-dispatcher
  "Own repository automatic pickup for the module lifetime."
  {:open 'millhouse.auto-run/open!
   :close 'millhouse.auto-run/close!})
