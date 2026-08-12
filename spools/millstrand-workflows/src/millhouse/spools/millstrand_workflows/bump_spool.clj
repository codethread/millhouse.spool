(ns millhouse.spools.millstrand-workflows.bump-spool
  "The portable consumer workflow for bumping pinned Millstrand spool families.

  This workflow assumes that its caller has selected the worktree and workspace
  in which the change is allowed. It requests the remote default-branch HEAD SHA for each
  family, tolerates an already-current coordinate, then reuses the shared Kondo
  bootstrap before handing over the refreshed runtime."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as fmt]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo :as bootstrap]
            [millhouse.spools.workflow :as workflow]))

(defn- non-blank-string?
  "Return true when `value` is a non-blank string."
  [value]
  (and (string? value)
       (not (str/blank? value))))

(defn- unique-families?
  "Return true when a request names each spool family at most once."
  [families]
  (= (count families)
     (count (distinct families))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::family ::non-blank-string)
(s/def ::families
  (s/and (s/coll-of ::family :kind vector? :min-count 1)
         unique-families?))
(s/def ::worktree ::non-blank-string)
(s/def ::workspace ::non-blank-string)
(s/def ::direct-user-request boolean?)
(s/def ::spool-bump-params
  (s/keys :req-un [::families ::worktree ::workspace ::direct-user-request]))

(workflow/defworkflow bump-spool
  "Bump selected spool families to their remote default-branch HEAD SHA and bootstrap Kondo.

  Start it with family names and the exact consumer paths:

  ```clojure
  (require '[millhouse.spools.workflow :as workflow])

  (workflow/start! \"consumer-bump\" :bump-spool
    {:families [\"io.millstrand/millstrand\" \"millhouse/spools\"]
     :worktree \"/abs/path/to/consumer-worktree\"
     :workspace \"/abs/path/to/consumer-worktree/.millstrand\"
     :direct-user-request false})
  ```

  The caller supplies exact consumer paths and family names. Each bump uses
  `spool bump FAMILY --latest sha`; an already-current coordinate is recorded
  and accepted. The shared bootstrap workflow then handles greenfield or
  brownfield Kondo adoption, local quality discovery, and handover. Runtime
  cutover is offered only for a direct user request."
  {:entrypoints #{:start :call}
   :param-spec ::spool-bump-params
   :defaults {}
   :example {:families ["io.millstrand/millstrand"]
             :worktree "/abs/path/to/consumer-worktree"
             :workspace "/abs/path/to/consumer-worktree/.millstrand"
             :direct-user-request false}
   :param-docs {:families
                (fmt/reflow
                 "|One family name per requested spool. Every family is bumped to
                  |the remote default-branch HEAD Git SHA; the CLI's already-current result is
                  |accepted and recorded.")
                :worktree
                "Exact consumer worktree in which the bump and bootstrap run."
                :workspace
                "Exact Millstrand workspace passed to every strand command."
                :direct-user-request
                (fmt/reflow
                 "|True only when the direct user requested runtime cutover. It
                  |never follows from an agent, scheduled, or nested call.")}}
  (workflow/workflow
   (fn [{:keys [families]}]
     (str "Bump consumer spools: " (str/join ", " families)))
   {:attributes {"workflow/family" "bump-spool"
                 "bump-spool/families" (fn [{:keys [families]}] families)
                 "bump-spool/worktree" (fn [{:keys [worktree]}] worktree)
                 "bump-spool/workspace" (fn [{:keys [workspace]}] workspace)
                 "bump-spool/direct-user-request"
                 (fn [{:keys [direct-user-request]}] direct-user-request)}}
   (workflow/step :select-world
                  "Confirm the selected consumer worktree and workspace"
                  :self
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.world.select"
                   "workflow/instruction"
                   (fn [{:keys [worktree workspace]}]
                     (fmt/reflow
                      (format
                       "|Use worktree `%s` and workspace `%s` exactly. Confirm the
                        |worktree is the intended consumer checkout and that the
                        |workspace is its selected `.millstrand` world. Refuse to
                        |fall back to the process current directory or a canonical
                        |workspace."
                       worktree workspace)))})
   (workflow/step :bump-spool
                  (fn [{:keys [item]}]
                    (str "Request remote default-branch HEAD SHA for " item))
                  :self
                  :depends-on [:select-world]
                  :loop {:each :families :chain true}
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.coordinate.bump"
                   "workflow/instruction"
                   (fn [{:keys [item worktree workspace]}]
                     (fmt/reflow
                      (format
                       "|From worktree `%s`, run exactly `strand --workspace %s
                        |spool bump %s --latest sha`. The explicit workspace is
                        |mandatory. Record the previous coordinate and resulting
                        |remote default-branch HEAD SHA. If the CLI reports that the coordinate is
                        |already current, record that outcome and continue."
                       worktree workspace item)))})
   (workflow/call :bootstrap-kondo
                  #'bootstrap/bootstrap-kondo
                  {:worktree (fn [{:keys [worktree]}] worktree)
                   :workspace (fn [{:keys [workspace]}] workspace)}
                  :depends-on [:bump-spool])
   (workflow/step :refresh-runtime
                  "Refresh the selected runtime and record generation state"
                  :self
                  :depends-on [:bootstrap-kondo]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.runtime.refresh"
                   "workflow/instruction"
                   (fn [{:keys [workspace]}]
                     (fmt/reflow
                      (format
                       "|In the runtime for selected workspace `%s`, run
                        |`(runtime/refresh! (current/runtime))`. Record the full
                        |result and whether the bumped coordinate is adopted or
                        |pending. Refresh does not itself authorize a stop or
                        |restart."
                       workspace)))})
   (workflow/step :cutover
                  "Cut over the selected runtime after direct user authorization"
                  :self
                  :depends-on [:refresh-runtime]
                  :condition [:= :direct-user-request true]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.runtime.cutover"
                   "workflow/instruction"
                   (fmt/reflow
                    "|This step is present only for a direct user request. Ask the
                     |direct user to confirm the recorded pending generation, then
                     |stop and start only the selected runtime by its exact
                     |workspace/PID. Reconnect and verify the bumped coordinate is
                     |adopted with no pending generation. Never infer this authority
                     |from an agent, scheduled, or nested workflow call.")})
   (workflow/step :handover-pending-generation
                  "Hand over pending runtime generation"
                  :self
                  :depends-on [:refresh-runtime]
                  :condition [:= :direct-user-request false]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.runtime.handover"
                   "workflow/instruction"
                   (fmt/reflow
                    "|Do not stop or restart any runtime. Record the bump results,
                     |bootstrap handover, refresh result, pending generation, and
                     |exact selected workspace, then hand over that a direct user
                     |request is required before runtime cutover.")})))
