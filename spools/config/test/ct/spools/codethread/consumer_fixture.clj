(ns ct.spools.codethread.consumer-fixture
  "Consumer-owned alias module used to prove deferred executor activation."
  (:require [ct.spools.harnesses :as harnesses]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

(defn open-consumer-alias!
  "Register an alias that exists only in the consumer module."
  [{:keys [runtime]}]
  (harnesses/register-alias!
   runtime :consumer-luna
   {:doc "Consumer-specific low-effort Luna seat."
    :parent :luna
    :effort :low
    :attributes {}})
  {:alias :consumer-luna})

(defn close-consumer-alias!
  "Remove the alias owned by the consumer fixture resource."
  [{:keys [runtime resource]}]
  (harnesses/unregister-alias! runtime (:alias resource))
  {:closed (:alias resource)})

(lifecycle/defresource! consumer-alias
  "Own the consumer-only alias for the module lifetime."
  {:open 'ct.spools.codethread.consumer-fixture/open-consumer-alias!
   :close 'ct.spools.codethread.consumer-fixture/close-consumer-alias!})
