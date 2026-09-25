(ns millhouse.config
  "Millhouse shared workspace config.

  The Harnesses catalog is activated through
  `millhouse.config.bootstrap/register!`. This optional module retains the
  Batteries and Devflow Kanban elections used by this repository's workspace."
  (:require [millhouse.config.devflow :as devflow]
            [millhouse.config.help :as help]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

(lifecycle/defresource batteries-help-transform
  "Own this world's Batteries help-transform election for the module lifetime."
  help/batteries-help-transform-options)
(lifecycle/defseed devflow-kanban-adapter-binding
  "Route the external Devflow decompose stage through its Kanban adapter."
  devflow/devflow-kanban-adapter-binding-options)

(lifecycle/use-resource! batteries-help-transform)
(lifecycle/use-seed! devflow-kanban-adapter-binding)
