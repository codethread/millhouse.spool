(ns millhouse.spools.millstrand-workflows.bump-millstrand
  "The local-aware consumer workflow for updating Millstrand.

  The workflow asks the caller to inspect the selected coordinate. A local
  checkout stays local and uses the shared Kondo bootstrap; a pinned checkout
  delegates to bump-spool, whose family-only contract requests the remote
  default-branch HEAD SHA automatically."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as fmt]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo :as bootstrap]
            [millhouse.spools.millstrand-workflows.bump-spool :as bump]
            [millhouse.spools.workflow :as workflow]))

(defn- non-blank-string?
  "Return true when `value` is a non-blank string."
  [value]
  (and (string? value)
       (not (str/blank? value))))

(defn- millstrand-request?
  "Return true when `families` contains exactly the Millstrand family."
  [families]
  (and (= 1 (count families))
       (= "io.millstrand/millstrand" (first families))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::family ::non-blank-string)
(s/def ::families
  (s/and (s/coll-of ::family :kind vector? :count 1)
         millstrand-request?))
(s/def ::worktree ::non-blank-string)
(s/def ::workspace ::non-blank-string)
(s/def ::deps-file ::non-blank-string)
(s/def ::direct-user-request boolean?)
(s/def ::millstrand-bump-params
  (s/keys :req-un [::families ::worktree ::workspace ::direct-user-request]
          :opt-un [::deps-file]))

(defn- inspect-deps-instruction
  [{:keys [worktree deps-file]}]
  (fmt/reflow
   (format
    "|In `%s/%s`, inspect the `io.millstrand/millstrand` coordinate exactly as
     |authored. Classify it as a local checkout (for example a sibling
     |`../millstrand` or `../skein-src`) or as a Git coordinate pinned by
     |`git/url` and `git/sha`. Do not infer a SHA for a local checkout and do
     |not mutate the dependency while classifying it."
    worktree deps-file)))

(defn- local-validation-instruction
  [{:keys [worktree]}]
  (fmt/reflow
   (format
    "|The consumer intentionally uses the local Millstrand checkout. In `%s`,
     |record that decision and continue through `bootstrap-kondo`, choosing
     |greenfield or brownfield there. Preserve the local coordinate; never
     |replace it with a fabricated SHA."
    worktree)))

(defn- refresh-instruction
  [{:keys [workspace]}]
  (fmt/reflow
   (format
    "|In the runtime for selected workspace `%s`, run
     |`(runtime/refresh! (current/runtime))`. Record the full result and
     |whether the Millstrand checkout is adopted or pending. Refresh does not
     |itself authorize a stop or restart."
    workspace)))

(defn- cutover-instruction
  []
  (fmt/reflow
   "|This step is present only for a direct user request. Ask the direct user
    |to confirm the recorded pending generation, then stop and start only the
    |selected runtime by its exact workspace/PID. Never infer this authority
    |from an agent, scheduled, or nested workflow call."))

(defn- handover-instruction
  []
  (fmt/reflow
   "|Do not stop or restart any runtime. Record the bootstrap and refresh
    |results, pending generation, and exact selected workspace; hand over that
    |direct-user authorization is required before runtime cutover."))

(workflow/defworkflow bump-millstrand
  "Inspect a consumer Millstrand coordinate and choose its honest update path.

  Start it with exactly one Millstrand family and the dependency file to
  inspect:

  ```clojure
  (require '[millhouse.spools.workflow :as workflow])

  (workflow/start! \"millstrand-bump\" :bump-millstrand
    {:families [\"io.millstrand/millstrand\"]
     :worktree \"/abs/path/to/consumer-worktree\"
     :workspace \"/abs/path/to/consumer-worktree/.millstrand\"
     :direct-user-request false
     :deps-file \"deps.edn\"})
  ```

  A local sibling coordinate is never converted into a guessed SHA. A pinned
  coordinate delegates to bump-spool, which requests the remote default-branch HEAD SHA and
  then calls the shared Kondo bootstrap."
  {:entrypoints #{:start}
   :param-spec ::millstrand-bump-params
   :defaults {:deps-file "deps.edn"}
   :example {:families ["io.millstrand/millstrand"]
             :worktree "/abs/path/to/consumer-worktree"
             :workspace "/abs/path/to/consumer-worktree/.millstrand"
             :direct-user-request false
             :deps-file "deps.edn"}
   :param-docs {:families
                (fmt/reflow
                 "|Exactly `[\"io.millstrand/millstrand\"]`; pinned coordinates use the
                  |automatic remote default-branch HEAD SHA bump.")
                :worktree "Exact consumer worktree in which inspection and validation run."
                :workspace "Exact Millstrand workspace selected for this consumer."
                :direct-user-request
                (fmt/reflow
                 "|True only when the direct user requested runtime cutover.")
                :deps-file "The deps.edn path relative to worktree; defaults to deps.edn."}}
  (workflow/workflow
   "Bump Millstrand"
   (workflow/step :inspect-deps
                  "Inspect the consumer Millstrand coordinate"
                  :self
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-millstrand.deps.inspect"
                   "workflow/instruction" inspect-deps-instruction})
   (workflow/checkpoint :coordinate-classification
                        "Record the inspected Millstrand coordinate type"
                        :kind :agent
                        :depends-on [:inspect-deps]
                        :choices [{:key :local-checkout
                                   :label "Use local checkout"
                                   :description (fmt/reflow
                                                 "|The coordinate resolves to a local sibling; preserve it
                                                  |and decide explicitly whether to continue.")
                                   :next :bump-millstrand-local}
                                  {:key :git-sha-pinned
                                   :label "Use Git/SHA pin"
                                   :description (fmt/reflow
                                                 "|The coordinate is Git/SHA-pinned; delegate the family-only
                                                  |remote default-branch HEAD SHA bump.")
                                   :next :bump-millstrand-pinned}
                                  {:key :unsupported
                                   :label "Stop"
                                   :description (fmt/reflow
                                                 "|The coordinate is neither an accepted local checkout nor a
                                                  |Git/SHA pin; stop for repair.")}])))

(workflow/defworkflow bump-millstrand-local
  "Require an explicit decision before validating a local Millstrand checkout.

  This is the local continuation selected after `bump-millstrand` classifies
  the exact dependency coordinate."
  {:entrypoints #{:continue}
   :param-spec ::millstrand-bump-params}
  (workflow/workflow
   "Use local Millstrand checkout"
   (workflow/checkpoint :local-checkout-decision
                        "Decide whether to continue with the local Millstrand checkout"
                        :kind :agent
                        :choices [{:key :move-forward
                                   :label "Move forward"
                                   :description (fmt/reflow
                                                 "|Keep the local coordinate and use the shared Kondo
                                                  |bootstrap.")
                                   :next :bump-millstrand-local-validate}
                                  {:key :stop
                                   :label "Stop"
                                   :description (fmt/reflow
                                                 "|Do not validate or alter the consumer until a local-checkout
                                                  |decision is supplied.")}])))

(workflow/defworkflow bump-millstrand-local-validate
  "Bootstrap Kondo for an explicitly approved local Millstrand checkout.

  This continuation preserves the local coordinate, runs shared bootstrap, and
  refreshes the selected runtime before handing over or cutting over."
  {:entrypoints #{:continue}
   :param-spec ::millstrand-bump-params}
  (workflow/workflow
   "Validate local Millstrand"
   (workflow/step :record-local-choice
                  "Record the approved local-checkout path"
                  :self
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-millstrand.local.approve"
                   "workflow/instruction" local-validation-instruction})
   (workflow/call :bootstrap-kondo
                  #'bootstrap/bootstrap-kondo
                  {:worktree (fn [{:keys [worktree]}] worktree)
                   :workspace (fn [{:keys [workspace]}] workspace)}
                  :depends-on [:record-local-choice])
   (workflow/step :refresh-runtime
                  "Refresh the selected runtime after local validation"
                  :self
                  :depends-on [:bootstrap-kondo]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-millstrand.local.runtime.refresh"
                   "workflow/instruction" refresh-instruction})
   (workflow/step :cutover
                  "Cut over the selected runtime after direct user authorization"
                  :self
                  :depends-on [:refresh-runtime]
                  :condition [:= :direct-user-request true]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-millstrand.local.runtime.cutover"
                   "workflow/instruction" (fn [_] (cutover-instruction))})
   (workflow/step :handover-pending-generation
                  "Hand over pending runtime generation"
                  :self
                  :depends-on [:refresh-runtime]
                  :condition [:= :direct-user-request false]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-millstrand.local.runtime.handover"
                   "workflow/instruction" (fn [_] (handover-instruction))})))

(workflow/defworkflow bump-millstrand-pinned
  "Delegate a Git/SHA-pinned Millstrand update to registered bump-spool.

  This continuation is selected after coordinate classification and keeps the
  family-only remote default-branch SHA contract."
  {:entrypoints #{:continue}
   :param-spec ::millstrand-bump-params}
  (workflow/workflow
   "Bump pinned Millstrand"
   (workflow/call :bump-spool
                  #'bump/bump-spool
                  {:families (fn [{:keys [families]}] families)
                   :worktree (fn [{:keys [worktree]}] worktree)
                   :workspace (fn [{:keys [workspace]}] workspace)
                   :direct-user-request (fn [{:keys [direct-user-request]}]
                                          direct-user-request)})))
