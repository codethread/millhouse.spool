(ns millhouse.harnesses.spool
  "Convenience entry point that activates the complete Harnesses spool.

  Provider namespaces expose inert authoring declarations. Consumers that want
  the complete tracked-agent surface can activate this namespace; consumers
  that need a smaller surface can select declarations in their own module."
  (:require [millhouse.harnesses :as harnesses]
            [millhouse.harnesses.agent-bin :as agent-bin]
            [millhouse.harnesses.agent-cli :as agent-cli]
            [millhouse.harnesses.assignment :as assignment]
            [millhouse.harnesses.execution :as execution]
            [millhouse.harnesses.process-custody :as process-custody]
            [millhouse.harnesses.queries :as queries]
            [millhouse.harnesses.reconciliation :as reconciliation]
            [millhouse.harnesses.reviewers :as reviewers]
            [millhouse.harnesses.providers.claude :as claude]
            [millhouse.harnesses.providers.codex :as codex]
            [millhouse.harnesses.providers.cursor :as cursor]
            [millhouse.harnesses.providers.pi :as pi]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(reviewers/use-reviewer-kind!)

(millstrand/use-op! agent-cli/agent)
(millstrand/use-handler! execution/on-event)
(millstrand/use-bin! agent-bin/agent)
(millstrand/use-query!
 queries/agent-run-terminal
 queries/agent-run-settled
 queries/agent-run-active
 queries/agent-runs-active
 queries/agent-runs-for-target
 queries/agent-work-complete
 queries/agent-work-complete-or-intervention
 queries/agent-work-root-complete
 queries/agent-work-root-complete-or-intervention)

(lifecycle/use-resource!
 harnesses/harness-core-runtime
 assignment/assignment-runtime
 claude/claude-harness-runtime
 codex/codex-harness-runtime
 cursor/cursor-harness-runtime
 pi/pi-harness-runtime
 execution/harness-execution-runtime)

(lifecycle/use-reconcile!
 process-custody/harness-process-custody
 reconciliation/interactive-reconciliation-sweep)
