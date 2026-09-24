(ns ct.spools.devflow
  "Clojure-native workflow definitions for the devflow lifecycle.

  Every stage is a static `defworkflow` Var: a definition a worker can read
  through `strand workflow show <name>` before starting a run, with its param
  contract owned by a spec rather than by a constructor's argument list
  (PROP-Wcd-001.S12). The definitions are ordinary workflow data that callers
  can inspect, compose, pour as molecules, or materialize as wisps.

  Authoring knowledge for the artifacts each stage produces (proposal, specs,
  plan, task queue, ...) lives in `ct.spools.devflow.guidance` and is served
  by `guidance` from Clojure and by the `devflow` op (`strand devflow
  guidance`) for CLI workers; artifact-authoring steps advertise the matching
  guide key via the `devflow/guide` attribute."
  (:require [camel-snake-kebab.core :as csk]
            [ct.spools.devflow.guidance :as guidance]
            [ct.spools.devflow.execution :as execution]
            [ct.spools.devflow.cards :as cards]
            [ct.spools.devflow.planning :as planning]
            [ct.spools.devflow.internal.discovery :as discovery]
            [ct.spools.devflow.internal.definition :as definition
             :refer [titled stage-attributes guided-artifact
                     abort-reason-input]]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millhouse.spools.workflow :as workflow]))

(defn dependency-sentinel
  "Return a stable value produced through the Maven dependency declared by this spool.

  This is intentionally operationally harmless; runtime/demo validation calls it
  only to prove `camel-snake-kebab` was resolved through the approved spool's
  top-level `deps.edn :deps`."
  []
  (csk/->kebab-case-string "devflow_spool"))

(workflow/defworkflow agent-review
  "A reusable one-step agent review procedure, spliced into a stage by `call`."
  {:entrypoints #{:call}
   :param-spec ::definition/agent-review-params
   :defaults {}}
  (workflow/workflow
    (fn [{:keys [feature artifact]}]
      (str "Agent review: " feature " " artifact))
    (workflow/step :review
                   (fn [{:keys [feature artifact]}]
                     (str "Run agent review for " feature " " artifact))
                   :self
                   :attributes {"devflow/review" "agent"}
                   (format-alpha/prose
                     "
                     Review the artifact named in this step against the approved scope
                     and its Devflow guide. Record concrete findings and their resolution
                     as devflow/artifact-review on completion. Do not treat reviewer
                     process success as a pass, or replace the following human sign-off.
                     " {}))))

(workflow/defworkflow author-task-strands
  "The shipped strand-native task-authoring target for the tasks stage's defer.

  Tasks are ordinary strands, not files: `devflow/task-type` is `afk` or
  `hitl`, `devflow/feature` names the feature, dependencies are `depends-on`
  edges, and the runnable queue is the ready frontier
  (`strand ready --query devflow-tasks`). HITL tasks also carry `hitl=true`
  so the batteries convention (stop and ask the user) applies unchanged. The
  authoring rules and body headings live in `strand devflow guidance tasks`."
  {:entrypoints #{:call}
   :param-spec ::definition/author-strands-params
   :defaults {}}
  (workflow/workflow
    (titled "Author task strands for ")
    (workflow/step :author-task-strands
                   (titled "Author strand-native task queue for ")
                   :self
                   :attributes (guided-artifact "task strands"))))

(workflow/defworkflow author-card-strands
  "The shipped strand-native card-authoring target for the decompose stage's defer.

  Cards use the same strand vocabulary as tasks (`devflow/task-type`,
  `devflow/feature`, `depends-on` edges); the difference is body density — a
  card body carries the full cold-work contract from
  `strand devflow guidance decompose`. Strand ids are token-safe, so they are
  the card ids the review handoff expects."
  {:entrypoints #{:call}
   :param-spec ::definition/author-cards-params
   :defaults {}}
  (workflow/workflow
    (titled "Author card strands for ")
    (workflow/step :author-card-strands
                   (titled "Author strand-native implementation cards for ")
                   :self
                   :attributes (assoc (guided-artifact "implementation cards")
                                      "workflow/instruction"
                                      (fn [{:keys [repository proposal-path merged-revision]}]
                                        (format-alpha/prose
                                        "
                                        Read `strand devflow guidance decompose`. Read the approved
                                        proposal {path} in {repository} at revision {revision}. Author the cold-card graph, reusing
                                        verified existing outputs after interruption. Record ids as
                                        they are created, and verify the full dependency graph.

                                        Complete with devflow/review-set containing the exact
                                        nonempty vector of authored card refs (id and title). The
                                        parent handoff reads this receipt before starting reviews.
                                        "
                                        {:repository repository :path proposal-path
                                         :revision merged-revision}))))))

(def decompose-open
  "The decompose stage as an unbound template.

  The `:author-cards` defer is the pluggable seam: the template names where a
  workspace chooses its card-authoring workflow without naming anyone's
  implementation. Consumer code that can see both spools binds it with
  `workflow/bind-defers` — the shipped strand-native target, an issue-tracker
  target, any other card system's target — and registers the result under its
  own name, or re-points `:decompose` at it."
  (workflow/workflow
    (titled "Devflow decompose: ")
    {:attributes (stage-attributes "decompose")}
    (workflow/defer :author-cards
                    (titled "Choose the card-authoring workflow for ")
                    :attributes {"workflow/action-ref" "devflow.decompose.cards"
                                 "devflow/guide" "decompose"
                                 "workflow/instruction" (format-alpha/prose
                                   "
                                   Fill this defer with one of the workflows listed in workflow/defer-workflows:
                                   `strand workflow defer <feature> --workflow <target> --params
                                   '<explicit-target-params-json>'`. Targets receive only the params passed at the
                                   fill, so pass the feature explicitly. Run `strand devflow guidance decompose`
                                   for the cold-card and review handoff contracts. Read the verified
                                   repository/mainline/merged-revision/proposal-path receipt from this run's root
                                   workflow/context and pass it explicitly to the target; defer does not inherit
                                   it.
                                   " {})})
    (workflow/checkpoint :handoff-card-review
                         (titled "Hand authored cards to review for ")
                         :depends-on [:author-cards]
                         :kind :agent
                         :choices [{:key :review
                                    :label "Review cards"
                                    :description "Supply the authored card refs; fan focused reviews out before the set-level cohesion review."
                                    :next 'ct.spools.devflow.cards/review-cards
                                    :input {:spec ::definition/card-set-input
                                            :doc (format-alpha/prose
                                              "
                                              The non-empty vector of authored card refs; each requires token-safe id and
                                              title. Include every card the review should judge — grouping cards are the
                                              workspace's own convention, not devflow's.
                                              " {})}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature because a reviewable implementation-card set could not be authored."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "implementation-cards-authored"
                                      "workflow/instruction" (format-alpha/prose
                                        "
                                        Read the completed authoring procedure's devflow/review-set receipt via the
                                        run subgraph and strand show. Choose review with that exact complete card set,
                                        not a reconstructed list. The review stage uses the configured card-reviewer
                                        and card-set-reviewer seats.
                                        " {})})))

(workflow/defworkflow decompose
  "Author implementation cards through a pluggable target, then hand their
  refs to the review stage.

  `:author-cards` is a defer bound to the shipped strand-native target;
  workspaces bind their own card systems through `decompose-open`. Workflow
  loops expand when a stage pours, before any card exists, so the agent
  checkpoint after authoring remains the explicit data boundary: its `:review`
  choice supplies the card refs that the continuation fans out over. Reviewer
  seats are caller-selected params."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/decompose-params
   :defaults {}}
  (workflow/bind-defers decompose-open {:author-cards #{:author-card-strands}}))

(workflow/defworkflow abort
  "A tiny stage that records intentional feature abortion."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/abort-params
   :defaults {}}
  (workflow/workflow
    (titled "Abort devflow feature: ")
    {:attributes (stage-attributes "abort")}
    (workflow/step :record-abort
                   (fn [{:keys [feature reason]}]
                     (str "Record abort for " feature ": " reason))
                   :self
                   :attributes {"workflow/action-ref" "devflow.abort.record"
                                "workflow/instruction" "Record the abort reason in the feature plan or conversation summary, then stop the active workflow."})))

;; Devflow defines reusable workflow, query, and op declarations, then selects
;; the complete catalogue for this root's publishing module. A consumer that
;; requires this namespace outside module collection can select only the Vars
;; it wants with the matching typed use form.
;;
;; The generic `millhouse.spools.workflow` API owns starting, inspecting,
;; advancing, archiving, and querying workflow runs. Devflow adds no parallel
;; run-driving facade; the `devflow` op serves authoring knowledge only.

(workflow/use-workflow!
 planning/intake
 agent-review
 author-task-strands
 author-card-strands
 planning/proposal
 planning/land-proposal
 decompose
 cards/review-cards
 execution/route-after-plan
 execution/spec-plan
 execution/run-afk-loop
 execution/run-afk-manual
 execution/run-afk-delegated
 execution/tasks
 execution/direct-implementation
 abort)

(millstrand/defquery devflow-runs
  "Return active Devflow workflow roots that can be resumed."
  {:usage "strand list --query devflow-runs"}
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "root"]
   [:= [:attr "workflow/family"] "devflow"]])

(millstrand/defquery devflow-ready
  "Return ready work belonging to an active Devflow workflow run."
  {:usage "strand ready --query devflow-ready"}
  [:edge/in "parent-of"
   [:and
    [:= :state "active"]
    [:= [:attr "workflow/role"] "root"]
    [:= [:attr "workflow/family"] "devflow"]]])

(millstrand/defquery devflow-tasks
  "Return active strand-native devflow tasks and cards (devflow/task-type).

  With `strand list` this is the whole open queue; with `strand ready` it is
  the runnable frontier — active tasks whose depends-on prerequisites are all
  closed. HITL tasks additionally carry hitl=true, which the batteries agent
  convention treats as stop-and-ask."
  {:usage "strand ready --query devflow-tasks"}
  [:and
   [:= :state "active"]
   [:exists [:attr "devflow/task-type"]]])

(millstrand/use-query! devflow-runs
                       devflow-ready
                       devflow-tasks)

(defn guidance
  "Return Devflow's static authoring knowledge as markdown.

  With no argument, return the workspace overview. With a keyword or string
  guide key, return that artifact's authoring guide: purpose, prerequisites,
  procedures, constraints, validation checklist, and templates."
  ([] (guidance/overview))
  ([guide] (guidance/guide (if (string? guide) (keyword guide) guide))))

(millstrand/defop devflow
  "Serve Devflow's static authoring knowledge: the workspace overview or one artifact's authoring guide."
  (merge {:arg-spec discovery/devflow-arg-spec
          :returns discovery/devflow-returns
          :stream? false}
         discovery/devflow-meta)
  [{:op/keys [args]}]
  (case (first (:subcommand args))
    "guidance" (if-let [guide (:guide args)]
                 {:operation "devflow guidance"
                  :guide guide
                  :guidance (guidance guide)}
                 {:operation "devflow guidance"
                  :guidance (guidance)})
    (throw (ex-info "Unsupported devflow subcommand"
                    {:subcommand (:subcommand args) :allowed ["guidance"]}))))

(millstrand/use-op! devflow)

;; The unbanged forms above define declarations without publishing them. The
;; explicit use forms are the root module's owner-complete contribution. There
;; is no spool entry point and no run-driving Devflow facade.
