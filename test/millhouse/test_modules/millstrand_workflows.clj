(ns millhouse.test-modules.millstrand-workflows
  "Test-only selective activation module."
  (:require [millhouse.spools.millstrand-workflows :as workflows]
            [millhouse.spools.workflow :as workflow]))

(workflow/use-workflow! workflows/publish-spool-kondo)
