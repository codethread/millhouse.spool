(ns millhouse.devflow.planning
  "Worktree intake, proposal approval and the external proposal landing boundary."
  (:require [millhouse.devflow.internal.definition :as definition
             :refer [titled stage-attributes guided-artifact param-value abort-reason-input]]
            [millstrand.api.format.alpha :as format-alpha]
            [millhouse.workflow :as workflow]))

(workflow/defworkflow intake
  "The mandatory brief intake stage.

  The first strand is a `:human` checkpoint that requires worktree creation
  before substantive discovery. `:worktree-check` may be `\"required\"` for a
  fresh brief or `\"already-in-worktree-ok\"` for agents launched directly inside
  the feature worktree. On a revision round (`:revision true`), the worktree
  checkpoint is skipped because it was already satisfied on the first pass;
  F4's splice reattaches `:capture-brief` as the entry step."
  {:entrypoints #{:start}
   :param-spec ::definition/intake-params
   :defaults {:worktree-check "required" :revision false}}
  (workflow/workflow
    (titled "Devflow intake: ")
    {:attributes (assoc (stage-attributes "intake")
                        "devflow/worktree-check" (param-value :worktree-check))}
    (workflow/checkpoint :create-or-confirm-worktree
                         (titled "Create or confirm feature worktree for ")
                         :kind :human
                         :condition [:!= :revision true]
                         :choices [{:key :created-worktree
                                    :label "Created worktree"
                                    :description "A new feature worktree was created; continue intake there."
                                    :input {:spec ::definition/worktree-input
                                            :doc "Verified repository, absolute worktree path and branch."}}
                                   {:key :already-in-worktree
                                    :label "Already in worktree"
                                    :description "This agent is already running in the correct feature worktree; continue intake."
                                    :input {:spec ::definition/worktree-input
                                            :doc "Verified repository, absolute worktree path and branch."}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop the feature before any substantive work begins."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "worktree-ready"
                                      "workflow/action-ref" "devflow.worktree.ensure"
                                      "workflow/instruction"
                                      (format-alpha/prose
                                        "
                                        Create or verify the feature worktree before discovery.
                                        Inspect its repository, absolute path and checked-out branch;
                                        supply those exact values with either worktree choice.

                                        Follow the user's worktree policy. This human checkpoint is
                                        a real approval boundary; an actor label is not authorization.
                                        " {})})
    (workflow/step :capture-brief
                   (titled "Capture user brief for ")
                   :self
                   :depends-on [:create-or-confirm-worktree]
                   :attributes {"workflow/artifact" "brief"}
                   (format-alpha/prose
                     "
                     Read workflow/outcome-input on the closed create-or-confirm-worktree
                     checkpoint via `strand subgraph <root-id>` and `strand show <id>`.
                     Verify its repository, worktree and branch against the current
                     directory before capturing the user's brief. Complete with that
                     exact receipt in --context so routed stages retain it.

                     On revision read the saved receipt from the root workflow/context;
                     do not create a second worktree. Context does not rewrite this prompt.
                     " {}))
    (workflow/checkpoint :discuss-scope
                         (titled "Discuss scope and open questions for ")
                         :depends-on [:capture-brief]
                         :kind :agent
                         :choices [{:key :proposal-ready
                                    :label "Proposal ready"
                                    :description "Scope is clear enough; create the proposal workflow next."
                                    :next :proposal}
                                   {:key :needs-more-brief
                                    :label "Needs more brief"
                                    :description "Scope is incomplete; revise intake to gather more brief before proposing."
                                    :revise {:params {:revision true}}}]
                         :attributes {"workflow/decision-point" "scope-ready"})))

(workflow/defworkflow proposal
  "The proposal gate stage.

  This encodes: inspect RFCs/spikes/specs first, write proposal, run agent
  review, then stop for human sign-off. On a revision round (`:revision true`),
  `:inspect-context` is skipped because orientation was done on the first pass;
  F4's splice reattaches `:write-proposal` as the entry step.

  Revision rounds are the proposal's whole editing window. Sign-off freezes the
  document as the intent that was agreed; later divergence is recorded in the
  spec deltas and plan, so no downstream stage edits it back into agreement
  with what was built."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/proposal-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow proposal: ")
    {:attributes (stage-attributes "proposal")}
    (workflow/step :inspect-context
                   (titled "Inspect relevant RFCs, spikes, root specs, and active feature context for ")
                   :self
                   :condition [:!= :revision true]
                   :attributes {"workflow/action-ref" "devflow.proposal.orient"
                                "workflow/instruction" "Inspect relevant active RFCs, spikes, root specs, active feature folders, and affected code before writing the proposal."})
    (workflow/step :write-proposal
                   (titled "Write devflow proposal for ")
                   :self
                   :depends-on [:inspect-context]
                   :attributes (guided-artifact "proposal.md"))
    (workflow/call :agent-review-proposal
                   :agent-review
                   {:artifact "proposal"}
                   :title (titled "Complete agent review for " " proposal")
                   :depends-on [:write-proposal])
    (workflow/checkpoint :human-signoff-proposal
                         (titled "Human sign-off for " " proposal")
                         :depends-on [:agent-review-proposal]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Proposal is accepted and frozen as the agreed intent; mark it Approved and continue to spec and plan work."
                                    :next :spec-plan}
                                   {:key :approved-to-cards
                                    :label "Approve to cards"
                                    :description (format-alpha/prose
                                      "
                                      Proposal is accepted and frozen as the agreed intent; land it on mainline,
                                      decompose it into implementation cards, and end the run there. Implementation
                                      belongs to the card loop, not to this run.
                                      " {})
                                    :next 'millhouse.devflow.planning/land-proposal}
                                   {:key :revise
                                    :label "Revise"
                                    :description (format-alpha/prose
                                                   "
                                                   Revise the proposal and re-review before proceeding.
                                                   Revision is the only window for rewriting it.
                                                   " {})
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature intentionally. Do not proceed to spec or plan work."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "proposal-signed-off"
                                      "workflow/instruction" (format-alpha/prose
                                        "
                                        Approval freezes the proposal: set its Status to Approved with the sign-off
                                        date and make no further content edits. Later divergence belongs in the spec
                                        deltas and plan, not in a rewritten proposal. Choose revise while the document
                                        still needs to change.
                                        " {})})))

(workflow/defworkflow land-proposal
  "The proposal landing stage on the cards route.

  Reached by the sign-off's `:approved-to-cards` choice. Devflow's job on this
  route ends at \"approved proposal on mainline plus implementation cards
  authored\", so the frozen proposal must land before decomposition reads it.
  The merge is an external wait-point rather than driving-agent work: the gate
  stays repo-agnostic — any mainline merge process counts — and `complete!`
  records who landed it through `:by-identity`. The follow-up `:agent` checkpoint then
  routes to the decompose stage, or aborts a feature whose proposal will not
  land."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/land-proposal-params
   :defaults {}}
  (workflow/workflow
    (titled "Devflow land proposal: ")
    {:attributes (stage-attributes "land-proposal")}
    (workflow/gate :merge-proposal
                   (titled "Land approved proposal for " " on mainline")
                   :human
                   :attributes {"workflow/action-ref" "devflow.proposal.land"
                                "workflow/instruction"
                                (format-alpha/prose
                                  "
                                  Wait for the approved proposal to land through the workspace's
                                  authorized landing process. Do not merge merely to clear this gate.
                                  A human gate label or actor identity is not user authorization.

                                  The landing actor records a devflow/merge-receipt attribute on
                                  this gate when completing it, with repository, mainline,
                                  merged-revision, proposal-path and merge-evidence (the external
                                  merge record). Use the exact landed revision, not a moving branch.
                                  If interrupted, inspect the external merge before retrying; never
                                  repeat a merge whose outcome is uncertain. Include --by-identity.
                                  " {})})
    (workflow/checkpoint :confirm-proposal-landed
                         (titled "Confirm the proposal landed for ")
                         :depends-on [:merge-proposal]
                         :kind :agent
                         :choices [{:key :landed
                                    :label "Landed"
                                    :description "The approved proposal is merged on mainline; decompose it into implementation cards next."
                                    :next :decompose
                                    :input {:spec ::definition/landed-input
                                            :doc "Exact verified merge receipt read from the completed merge gate."}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature; its approved proposal will not land on mainline."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "proposal-landed"
                                      "workflow/instruction"
                                      (format-alpha/prose
                                        "
                                        Inspect the run subgraph and `strand show <merge-gate-id>`.
                                        Read devflow/merge-receipt, verify the recorded proposal at
                                        the exact revision on mainline, then supply that receipt to
                                        landed. A closed gate alone is not merge evidence. If the
                                        receipt is absent, obtain the external result before routing.
                                        " {})})))
