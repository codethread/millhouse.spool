(ns millhouse.workspace.auto-run-workflows
  "Repository-owned delivery contracts for automatically assigned Millhouse features."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.auto-run-land :as autonomous]
            [millhouse.land.support :as land-support]
            [millhouse.workflow :as workflow]
            [millstrand.api.format.alpha :as format]))

(s/def ::text (s/and string? (complement str/blank?)))
(s/def ::card ::text)
(s/def ::feature ::text)
(s/def ::branch ::text)
(s/def ::worktree ::text)
(s/def ::params (s/keys :req-un [::card ::feature ::branch ::worktree]))

(defn- shell-gate [id title dependencies argv timeout failure-instruction]
  (workflow/gate id title :shell
                 :depends-on dependencies
                 :attributes {"shell/argv" argv
                              "shell/cwd" (fn [{:keys [worktree]}] worktree)
                              "shell/timeout-secs" timeout}
                 failure-instruction))

(defn- delivery [autonomous?]
  (let [failure-instruction
        (if autonomous?
          (fn [{:keys [card]}] (autonomous/failure-policy card))
          "Await this executor-owned gate. Inspect failures, repair the cause, then explicitly clear gate/error to retry. Never manually assert a passing result.")]
    (apply
     workflow/workflow
     (if autonomous? "Deliver automatically" "Prepare for human review")
     (concat
      [(workflow/step
        :implement "Implement and verify the assigned feature" :self
        (fn [{:keys [card]}]
          (format/prose
           "
             Read card {card}, its epic and tasks, and AGENTS.md. Claim the card
             with your provided identity, branch, worktree and Harnesses run ID.
             Work in the provided worktree; do not create a second one. Implement
             the scoped outcome yourself and record evidence on the card's tasks.
             Follow the architecture contract and preserve unrelated work.

             Use disposable fixtures for mutations; never launch paid agents or publish
             external reviews as a smoke test. Add focused regression tests when
             behavior or ownership boundaries warrant them.

             Run .millstrand/land-quality.sh while iterating; it owns the shared
             suite lock. Do not wrap it in another flock.

             When implementation and focused verification are complete, commit your
             work and complete this step.
             Do not start land yet; the following steps own the review handoff.

             {failure-policy}
           " {:card card :failure-policy (if autonomous? (autonomous/failure-policy card) "")})))
       (shell-gate :quality "Pass repository quality checks" [:implement]
                   ["bash" ".millstrand/land-quality.sh"] 5400 failure-instruction)
       (workflow/step
        :prepare-pr "Publish the exact change with its review package" :self
        :depends-on [:quality]
        (fn [{:keys [card branch]}]
          (format/prose
           "
             Push {branch} and create or update its PR against main. It must be
             ready for review, not a draft. The PR body must contain these exact
             nonempty Markdown sections:

             - ## Summary: outcome, scope and important decisions.
             - ## Walkthrough: explain the change with a Mermaid diagram at a
               useful C4 context/container/component level. Show the affected
               boundaries and data flow, not an exhaustive class diagram.
             - ## Verification: automated checks, reproduction or manual testing
               instructions, and limitations.

             Put the PR URL and concise handoff on card {card}; retain detailed
             evidence on its verification task. Complete this step only after
             publishing the committed revision and review package. The next gates
             independently wait for CI and verify the exact PR head and package.
           " {:card card :branch branch})))
       (shell-gate :ci "Wait for the PR checks" [:prepare-pr]
                   (fn [{:keys [branch]}]
                     (land-support/pr-checks-argv "required" branch))
                   2100 failure-instruction)]
      (if autonomous?
        [(workflow/call :land #'autonomous/autonomous-land {}
                        :depends-on [:ci]
                        :title "Review and hand off autonomous landing")]
        [(workflow/gate
          :review-card "Request human review of the verified feature" :code
          :depends-on [:ci]
          :attributes {"code/fn" "millhouse.land.card-actions/review-card!"
                       "code/params" (fn [{:keys [card]}] {:card card})}
          "This is an automatic card transition at the human-attention boundary.")
         (workflow/checkpoint
          :human-acceptance "Human review: return the passing PR and stop"
          :depends-on [:review-card]
          :kind :human
          :choices [{:key :reviewed :label "Human review recorded"}]
          :attributes
          {"workflow/instruction"
           (format/prose
            "
              Stop here and return the PR URL, walkthrough, screenshots or their
              applicability explanation, verification evidence and open questions.
              Do not choose this checkpoint, merge, start land, finish the card,
              remove the worktree, or remain running to poll for the user.

              The user will review and decide what happens next. Generic landing
              instructions on the epic do not override this explicit stop boundary.
            " {})})])))))

(workflow/defworkflow! auto-human-review
  "Prepare a passing, documented PR and stop for the user's full review."
  {:entrypoints #{:start} :param-spec ::params}
  (delivery false))

(workflow/defworkflow! auto-full-land
  "Prepare and review the change, then hand shared landing to a canonical-root grunt."
  {:entrypoints #{:start} :param-spec ::params}
  (delivery true))
