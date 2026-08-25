(ns millhouse.test-modules.millstrand-workflows
  "Test-only selective activation module."
  (:require [millhouse.spools.millstrand-workflows :as workflows]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo :as bootstrap]
            [millhouse.spools.millstrand-workflows.bump-millstrand :as millstrand]
            [millhouse.spools.millstrand-workflows.bump-spool :as bump]
            [millhouse.spools.millstrand-workflows.consumer-tooling :as tooling]
            [millhouse.spools.workflow :as workflow]))

(workflow/use-workflow!
 workflows/publish-spool-kondo
 bootstrap/bootstrap-kondo
 bootstrap/bootstrap-kondo-greenfield
 bootstrap/bootstrap-kondo-brownfield
 bump/bump-spool
 millstrand/bump-millstrand
 millstrand/bump-millstrand-local
 millstrand/bump-millstrand-local-validate
 millstrand/bump-millstrand-pinned
 tooling/configure-consumer-tooling
 tooling/configure-consumer-tooling-app
 tooling/configure-consumer-tooling-spool
 tooling/configure-consumer-tooling-clojure-app)
