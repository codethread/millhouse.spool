(ns millhouse.test-modules.millstrand-workflows
  "Test-only selective activation module."
  (:require [millhouse.millstrand-workflows :as workflows]
            [millhouse.workflow :as workflow]))

(workflow/use-workflow! workflows/publish-spool-kondo)
