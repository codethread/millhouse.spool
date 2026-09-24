(ns ct.spools.codethread.help
  "Batteries help-transform capability for the Codethread config layer.

  Inert on its own: `ct.spools.codethread.config` elects it with `use-resource!`."
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
  {:open 'ct.spools.codethread.help/reconcile-help-transform
   :close 'ct.spools.codethread.help/close-help-transform!})
