(ns ct.spools.devflow.cards
  "Focused card reviews, their set-level join and explicit reconciliation."
  (:require [millstrand.api.format.alpha :as format-alpha]
            [ct.spools.devflow.internal.definition :as definition
             :refer [titled stage-attributes param-value card-review-prompt
                     card-set-review-prompt abort-reason-input]]
            [millhouse.spools.workflow :as workflow]))

(workflow/defworkflow review-cards
  "Review authored implementation cards at focused and set-level scopes.

  The card gate expands without a chain, so every focused review is ready
  together and the agent executor may run them up to its fan-out ceiling.
  The set gate depends on the loop's base id, which fans in over all focused
  reviews, and its prompt deliberately judges only cross-card cohesion. How
  cards are grouped (a parent card, a milestone, nothing) is the caller's own
  convention: the run reviews exactly the refs supplied. The driving agent
  then reconciles both result classes. Material changes may choose
  `:review-again`, re-pouring this stage with the current card refs."
  {:entrypoints #{:continue :call}
   :param-spec ::definition/review-cards-params
   :defaults {:revision false}}
  (workflow/workflow
    (titled "Devflow card review: ")
    {:attributes (stage-attributes "card-review")}
    (workflow/gate :card-review
                   (fn [{:keys [item]}]
                     (str "Focused review of card " (:id item) ": "
                          (:title item)))
                   :agent
                   :loop {:each :cards}
                   :attributes {"devflow/review" "agent"
                                "devflow/review-scope" "card"
                                "devflow/card" (fn [{:keys [item]}] (:id item))
                                "harness/alias" (param-value :card-reviewer)
                                "harness/cwd" (param-value :review-cwd)
                                "harness/prompt" card-review-prompt
                                "workflow/instruction" (format-alpha/prose
                                  "
                                  Executor-owned focused card review. The configured card reviewer must inspect
                                  exactly this card and return its verdict; parallel sibling gates review the
                                  other cards.
                                  " {})})
    (workflow/gate :card-set-review
                   (titled "Cohesion review of the card set for ")
                   :agent
                   :depends-on [:card-review]
                   :attributes {"devflow/review" "agent"
                                "devflow/review-scope" "card-set"
                                "harness/alias" (param-value :card-set-reviewer)
                                "harness/cwd" (param-value :review-cwd)
                                "harness/prompt" card-set-review-prompt
                                "workflow/instruction" (format-alpha/prose
                                  "
                                  Executor-owned set-level cohesion review. It starts only after every focused
                                  card review closes and must not repeat those per-card checks.
                                  " {})})
    (workflow/step :reconcile-card-reviews
                   (titled "Reconcile implementation-card reviews for ")
                   :self
                   :depends-on [:card-set-review]
                   :attributes {"workflow/action-ref" "devflow.decompose.reconcile-reviews"
                                "devflow/guide" "decompose"
                                "workflow/instruction" (format-alpha/prose
                                  "
                                  Read harness/result from every closed card-review-* gate and from the
                                  card-set-review gate. Apply valid focused findings to their cards and valid
                                  cohesion findings to card slicing or dependency edges. Do not collapse the two
                                  review scopes. If any material card changed, choose review-again and supply
                                  the current full card set.
                                  " {})})
    (workflow/checkpoint :card-review-verdict
                         (titled "Decide whether implementation cards are reviewed for ")
                         :depends-on [:reconcile-card-reviews]
                         :kind :agent
                         :choices [{:key :accepted
                                    :label "Accept reviewed cards"
                                    :description "The focused and set-level findings are resolved; end devflow and leave implementation to the card loop."}
                                   {:key :review-again
                                    :label "Review again"
                                    :description "Cards changed materially while reconciling findings; fan out a fresh review round over the current set."
                                    :input {:spec ::definition/card-set-input
                                            :doc "Resupply the current complete card refs for the next review round."}
                                    :revise {:params {:revision true}}}
                                   {:key :abort
                                    :label "Abort"
                                    :description "Stop this feature because the implementation-card decomposition cannot be made reviewable."
                                    :next :abort
                                    :input abort-reason-input}]
                         :attributes {"workflow/decision-point" "implementation-cards-reviewed"})))
