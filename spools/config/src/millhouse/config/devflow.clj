(ns millhouse.config.devflow
  "Workspace composition for the external Devflow and Kanban adapter roots.

  This root owns no Devflow definitions or guidance. It only declares the
  consumer election that routes the external adapter's Kanban-bound decompose
  workflow through the external lifecycle seed. Consumers still activate the
  Devflow and Kanban adapter modules before selecting this election.")

(def devflow-kanban-adapter-binding-options
  "Route the external Devflow decompose stage through its Kanban adapter."
  {:apply 'millhouse.devflow-kanban-adapter/repoint-decompose-seed!})
