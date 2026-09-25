(ns millhouse.config.help
  "Batteries help-transform capability for the Millhouse config layer.

  Inert on its own: `millhouse.config` elects it with `use-resource!`."
  (:require [millstrand.api.runtime.help-transform.alpha :as help-transform]))

(defn reconcile-help-transform
  "Register Batteries' default help transform in the module runtime."
  [{:keys [runtime]}]
  (help-transform/register-builtin! runtime)
  {:registered :help-transform})

(defn close-help-transform!
  "Remove the Batteries default help transform from the module runtime."
  [{:keys [runtime]}]
  (help-transform/unregister-default-help-transform! runtime 'millstrand.spools.batteries)
  {:unregistered :help-transform})

(def batteries-help-transform-options
  "Own this world's batteries help-transform election for the module lifetime."
  {:open 'millhouse.config.help/reconcile-help-transform
   :close 'millhouse.config.help/close-help-transform!})
