(ns millhouse.spools.millstrand-workflows.bump-spool
  "The portable consumer workflow for bumping a pinned Millstrand spool.

  This workflow assumes that its caller has selected the worktree and workspace
  in which the change is allowed. It describes the work; it does not choose a
  branch, land a change, or infer permission to restart a runtime."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as fmt]
            [millhouse.spools.workflow :as workflow]))

(defn- non-blank-string?
  "Return true when `value` is a non-blank string."
  [value]
  (and (string? value)
       (not (str/blank? value))))

(defn- spool-version?
  "Return true for the latest release or an annotated positive vN release."
  [value]
  (and (string? value)
       (or (= "latest" value)
           (boolean (re-matches #"v[1-9][0-9]*" value)))))

(defn- unique-families?
  "Return true when a request names each spool family at most once."
  [bumps]
  (let [families (map :family bumps)]
    (= (count families)
       (count (distinct families)))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::family ::non-blank-string)
(s/def ::version spool-version?)
(s/def ::bump (s/keys :req-un [::family ::version]))
(s/def ::bumps
  (s/and (s/coll-of ::bump :kind vector? :min-count 1)
         unique-families?))
(s/def ::worktree ::non-blank-string)
(s/def ::workspace ::non-blank-string)
(s/def ::direct-user-request boolean?)
(s/def ::quality-argv
  (s/coll-of ::non-blank-string :kind vector? :min-count 1))
(s/def ::spool-bump-params
  (s/keys :req-un [::bumps ::worktree ::workspace ::direct-user-request]
          :opt-un [::quality-argv]))

(def ^:private copy-configs-command
  "The one dependency-config import command used by the workflow.

  `clojure -Spath` is evaluated in the selected worktree, so every resolved
  dependency export is considered in one clj-kondo invocation."
  ["sh" "-c"
   (str "set -eu\n"
        "clj-kondo --lint \"$(clojure -Spath)\" --dependencies --parallel"
        " --copy-configs --skip-lint\n")])

(workflow/defworkflow bump-spool
  "Bump a pinned spool in a selected consumer worktree and refresh its runtime.

  The caller supplies the exact worktree and Millstrand workspace. The workflow
  imports dependency clj-kondo exports once, reviews and commits those copied
  configs, then runs the consumer's ordinary quality boundary. Runtime
  cutover is offered only when the invocation records a direct user request;
  other invocations stop at a pending-generation handover."
  {:entrypoints #{:start :call}
   :param-spec ::spool-bump-params
   :defaults {:quality-argv ["make" "quality"]}
   :example {:bumps [{:family "io.millstrand/millstrand" :version "v12"}]
             :worktree "/abs/path/to/consumer-worktree"
             :workspace "/abs/path/to/consumer-worktree/.millstrand"
             :direct-user-request false
             :quality-argv ["make" "quality"]}
   :param-docs {:bumps
                (fmt/reflow
                 "|One {family, version} record per requested spool family. `version`
                  |is `latest` or an annotated positive `vN` release marker; the
                  |bump operation records the resulting peeled SHA.")
                :worktree
                (fmt/reflow
                 "|Exact consumer worktree in which the bump, config import, review,
                  |commit, and quality checks run. Do not substitute the process's
                  |current directory.")
                :workspace
                (fmt/reflow
                 "|Exact Millstrand workspace selected for the bump. It is passed to
                  |every `strand` command so the consumer's coordination world is
                  |never inferred from ambient state.")
                :direct-user-request
                (fmt/reflow
                 "|True only when the user directly requested runtime cutover. False
                  |ends at an explicit pending-generation handover and never grants
                  |permission to stop or restart a runtime.")
                :quality-argv
                (fmt/reflow
                 "|The consumer's ordinary quality command as an argv vector. It
                  |defaults to `[\"make\" \"quality\"]`; callers for other consumer
                  |projects must supply their own command.")}}
  (workflow/workflow
   (fn [{:keys [bumps]}]
     (str "Bump consumer spools: " (str/join ", " (map :family bumps))))
   {:attributes {"workflow/family" "bump-spool"
                 "bump-spool/requests" (fn [{:keys [bumps]}] bumps)
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
                    (str "Request spool bump for " (:family item)
                         " to " (:version item)))
                  :self
                  :depends-on [:select-world]
                  :loop {:each :bumps :chain true}
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.coordinate.bump"
                   "workflow/instruction"
                   (fn [{:keys [item worktree workspace]}]
                     (let [{:keys [family version]} item]
                       (fmt/reflow
                        (format
                         "|From worktree `%s`, run `strand --workspace %s spool bump
                          |%s%s`. The explicit workspace is mandatory. Record the
                          |old tag/SHA and new tag/SHA in the change notes."
                         worktree workspace family
                         (if (= "latest" version)
                           ""
                           (str " --to " version))))))})
   (workflow/gate :copy-configs
                  "Import all resolved dependency clj-kondo configs"
                  :shell
                  :depends-on [:bump-spool]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.kondo.copy"
                   "shell/argv" copy-configs-command
                   "shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 300
                   "workflow/instruction"
                   (fmt/reflow
                    "|Run the single `clj-kondo --lint \"$(clojure -Spath)\"
                     |--dependencies --parallel --copy-configs --skip-lint`
                     |command in the selected worktree. It must resolve the
                     |complete classpath and copy every dependency export in one
                     |invocation; do not run one import per spool.")})
   (workflow/step :inspect-and-commit
                  "Inspect and commit copied clj-kondo configuration"
                  :self
                  :depends-on [:copy-configs]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.kondo.review"
                   "workflow/instruction"
                   (fn [{:keys [worktree]}]
                     (fmt/reflow
                      (format
                       "|In `%s`, inspect the complete diff under `.clj-kondo`.
                        |Confirm it contains only the dependency configs/hooks
                        |needed by the resolved classpath, remove unrelated or
                        |generated state, and commit the reviewed config change.
                        |Do not commit an uninspected copy."
                       worktree)))})
   (workflow/gate :quality
                  "Run ordinary consumer quality checks"
                  :shell
                  :depends-on [:inspect-and-commit]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.quality"
                   "shell/argv" (fn [{:keys [quality-argv]}] quality-argv)
                   "shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 1800
                   "workflow/instruction"
                   (fn [{:keys [quality-argv]}]
                     (fmt/reflow
                      (format
                       "|In the selected worktree, run the consumer's ordinary
                        |quality boundary from `quality-argv` (`%s`). A failing
                        |check blocks runtime refresh and cutover; fix the change
                        |and retry."
                       (pr-str quality-argv))))})
   (workflow/step :refresh-runtime
                  "Refresh the selected runtime and record generation state"
                  :self
                  :depends-on [:quality]
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
                    "|Do not stop or restart any runtime. Record the refresh result,
                     |the pending generation, and the exact selected workspace, then
                     |hand over that a direct user request is required before
                     |runtime cutover.")})))
