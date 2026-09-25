(ns millhouse.devflow.execution
  "Reviewed planning, task authoring and manual or delegated implementation stages."
  (:require [millhouse.devflow.internal.definition :as definition
             :refer [titled stage-attributes guided-artifact afk-task-gate
                     abort-reason-input]]
            [millstrand.api.format.alpha :as format-alpha]
            [millhouse.workflow :as workflow]))

(workflow/defworkflow route-after-plan
  "The post-plan route-choice stage."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/route-after-plan-params
   :defaults {}}
  (workflow/workflow
    (titled "Devflow route after plan: ")
    {:attributes (stage-attributes "route-after-plan")}
    (workflow/checkpoint :route-after-plan
                         (titled "Recommend next workflow: tasks or direct implementation for ")
                         :kind :agent
                         :choices [{:key :task-breakdown
                                    :label "Task breakdown"
                                    :description "Create an AFK/HITL task queue before implementation."
                                    :next :tasks}
                                   {:key :direct-implementation
                                    :label "Direct implementation"
                                    :description "Proceed directly to implementation because the reviewed plan is small and settled."
                                    :next :direct-implementation}]
                         :attributes {"workflow/decision-point" "choose-tasks-or-implementation"})))

(workflow/defworkflow spec-plan
  "The spec-delta and plan gate stage.

  After review and human sign-off, approval routes to the task/direct
  implementation decision workflow. A revision round (`:revision true`) re-runs
  the whole spec/plan stage."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/spec-plan-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow spec and plan: ")
    {:attributes (stage-attributes "spec-plan")}
    (workflow/step :write-spec-deltas
                   (titled "Write needed spec deltas for ")
                   :self
                   :attributes (guided-artifact "specs/*.delta.md"))
    (workflow/step :write-plan
                   (titled "Write implementation plan for ")
                   :self
                   :depends-on [:write-spec-deltas]
                   :attributes (guided-artifact "<feature>.plan.md"))
    (workflow/call :agent-review-spec-plan
                   :agent-review
                   {:artifact "spec deltas and plan"}
                   :title (titled "Complete agent review for " " spec deltas and plan")
                   :depends-on [:write-plan])
    (workflow/checkpoint :human-signoff-spec-plan
                         (titled "Human sign-off for " " spec deltas and plan")
                         :depends-on [:agent-review-spec-plan]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Spec deltas and plan are accepted; choose tasks or direct implementation next."
                                    :next :route-after-plan}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Spec deltas or plan need changes; revise the spec/plan stage and re-review before proceeding."
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature intentionally before implementation."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "plan-signed-off"})))

(workflow/defworkflow run-afk-loop
  "The post-task-signoff AFK execution stage: choose how the queue runs.

  The old constructor decided this invisibly, by whether a `:tasks` opt was
  supplied. A checkpoint names the decision instead, so each way of running the
  queue is a continuation a worker can discover and read before choosing it
  (PROP-Wcd-001.EX6). Delegation carries the queue forward in `:tasks`, so the
  delegated route is only honest when one is present."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/run-afk-loop-params
   :defaults {}}
  (workflow/workflow
    (titled "Devflow AFK execution: ")
    {:attributes (stage-attributes "afk")}
    (workflow/checkpoint :choose-afk-execution
                         (titled "Choose how the AFK task queue runs for ")
                         :kind :human
                         :choices [{:key :manual
                                    :label "Run manually"
                                    :description "Run or hand off the AFK task loop in this worker."
                                    :next :run-afk-manual}
                                   {:key :delegate
                                    :label "Delegate"
                                    :description "Supply the complete approved queue and harness assignments; run sequential subagent gates."
                                    :next :run-afk-delegated
                                    :input {:spec ::definition/delegation-input
                                            :doc (format-alpha/prose
                                                   "
                                                   Supply the complete approved tasks vector, with
                                                   distinct token-safe ids and titles, in execution
                                                   order. Every task must name a harness or inherit
                                                   delegate-harness. Optional delegate-cwd and
                                                   delegate-preamble apply to this execution.
                                                   " {})}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop or abandon this feature before AFK execution."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "afk-execution-mode"
                                      "workflow/instruction"
                                      (format-alpha/prose
                                        "
                                        Ask the user whether to run manually or delegate. Manual
                                        needs no inline queue. For Delegate, read the approved task
                                        graph and supply its complete ordered queue and resolved
                                        harness assignments in the choice input, even if supplied
                                        earlier. Do not invent missing tasks or approval. Inspect
                                        `strand workflow choices <run-id>` for the input contract.
                                        " {})})))

(workflow/defworkflow run-afk-manual
  "Run or hand off the AFK task loop in the current worker."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/run-afk-manual-params
   :defaults {}}
  (workflow/workflow
    (titled "Devflow AFK manual execution: ")
    {:attributes (stage-attributes "afk")}
    (workflow/step :run-afk-loop
                   (titled "Run or hand off AFK task loop for ")
                   :self
                   :attributes {"workflow/action-ref" "devflow.tasks.run-afk-loop"
                                "devflow/guide" "afk"
                                "workflow/instruction"
                                (format-alpha/prose
                                  "
                                  Run or hand off the approved task graph. Read
                                  `strand devflow guidance afk`; that graph owns per-task progress.

                                  Complete with a devflow/afk-outcome attribute containing the queue
                                  reference, outcome (exhausted, blocked, failed or handed-off),
                                  completed and remaining task ids, and validation evidence. For a
                                  handoff include the accepting owner and exact run/target receipt;
                                  for blockage/failure include the reason and next owner. Verify the
                                  graph and external acceptance, not merely an empty ready frontier.
                                  Closing this workflow records the outcome, not feature delivery.
                                  " {})})))

(workflow/defworkflow run-afk-delegated
  "Run the approved AFK task queue as sequential subagent gates.

  One gate per task, chained, then a `:human` acceptance checkpoint. Task maps
  use keyword keys; the CLI converts JSON object keys recursively. The param
  spec checks the whole queue, including harness resolution, before pouring."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/run-afk-delegated-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow AFK delegated execution: ")
    {:attributes (stage-attributes "afk")}
    (afk-task-gate)
    (workflow/checkpoint :human-acceptance-afk
                         (titled "Human acceptance for " " AFK task execution")
                         :depends-on [:task]
                         :kind :human
                         :choices [{:key :accepted
                                    :label "Accept"
                                    :description "AFK task execution is accepted; the run is done."}
                                   {:key :revise
                                    :label "Revise"
                                    :description "AFK task execution needs changes; re-run the delegated AFK stage."
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop or abandon this feature after AFK execution."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "afk-accepted"})))

(def tasks-open
  "The task-breakdown stage as an unbound template.

  The `:author-tasks` defer is the pluggable seam: it names where a workspace
  chooses its task-authoring workflow — the shipped strand-native target, an
  issue tracker, any other task system — without naming anyone's implementation.
  Consumer code binds it with `workflow/bind-defers` and registers the result
  under its own name, or re-points `:tasks` at it."
  (workflow/workflow
    (titled "Devflow task breakdown: ")
    {:attributes (stage-attributes "tasks")}
    (workflow/defer :author-tasks
                    (titled "Choose the task-authoring workflow for ")
                    :attributes {"devflow/guide" "tasks"
                                 "workflow/instruction" (format-alpha/prose
                                   "
                                   Fill this defer with one of the workflows listed in workflow/defer-workflows:
                                   `strand workflow defer <feature> --workflow <target> --params
                                   '<explicit-target-params-json>'`. Targets receive only the params passed at the
                                   fill, so pass the feature explicitly. Run `strand devflow guidance tasks` for
                                   the queue contract before filling.
                                   " {})})
    (workflow/call :agent-review-tasks
                   :agent-review
                   {:artifact "task queue"}
                   :title (titled "Complete agent review for " " task queue")
                   :depends-on [:author-tasks])
    (workflow/checkpoint :human-signoff-tasks
                         (titled "Human sign-off for " " task queue")
                         :depends-on [:agent-review-tasks]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Task queue is accepted; choose how the AFK loop runs next."
                                    :next :run-afk-loop}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Task queue needs changes; revise the task-breakdown stage and re-review before execution."
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature before task execution."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "tasks-signed-off"})))

(workflow/defworkflow tasks
  "The reviewed task queue stage.

  `:author-tasks` is a defer bound to the shipped strand-native target;
  workspaces bind their own queue systems through `tasks-open`. A revision
  round (`:revision true`) re-runs the whole task-breakdown stage, including
  the defer."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/tasks-params
   :defaults {:revision false}}
  (workflow/bind-defers tasks-open {:author-tasks #{:author-task-strands}}))

(workflow/defworkflow direct-implementation
  "The post-plan direct implementation stage for small, settled changes.

  A revision round (`:revision true`) re-runs the whole implementation stage."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/direct-implementation-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow direct implementation: ")
    {:attributes (stage-attributes "implementation")}
    (workflow/step :implement
                   (titled "Implement reviewed plan for ")
                   :self
                   :attributes {"workflow/action-ref" "devflow.implementation.direct"
                                "workflow/instruction" "Implement the reviewed plan directly because the signed-off scope does not need a separate task breakdown."})
    (workflow/step :validate
                   (titled "Validate implementation for ")
                   :self
                   :depends-on [:implement]
                   :attributes {"workflow/action-ref" "devflow.implementation.validate"
                                "workflow/instruction" "Run validation relevant to the touched implementation and report failures before review."})
    (workflow/call :review-implementation
                   :agent-review
                   {:artifact "implementation"}
                   :title (titled "Complete implementation review for ")
                   :depends-on [:validate])
    (workflow/checkpoint :human-acceptance
                         (titled "Human acceptance for " " implementation")
                         :depends-on [:review-implementation]
                         :kind :human
                         :choices [{:key :accepted
                                    :label "Accept"
                                    :description "Implementation is accepted; continue to finish/archive work."}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Implementation needs changes; revise the implementation stage and re-review before acceptance."
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop or abandon this feature after implementation review."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "implementation-accepted"})))
