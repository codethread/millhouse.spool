(ns millhouse.spools.millstrand-workflows.bump-spool
  "The portable consumer workflow for bumping pinned Millstrand spool families.

  This workflow assumes that its caller has selected the worktree and workspace
  in which the change is allowed. It requests the remote default-branch HEAD SHA
  for each family, tolerates an already-current coordinate, then reuses the shared
  Kondo bootstrap and repository-style tooling setup before handing over the
  refreshed runtime."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as fmt]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo :as bootstrap]
            [millhouse.spools.millstrand-workflows.consumer-tooling :as tooling]
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
(s/def ::invocation-producer ::tooling/invocation-producer)
(s/def ::direct-user-request boolean?)
(s/def ::spool-bump-params
  (s/keys :req-un [::families ::worktree ::workspace ::invocation-producer
                   ::direct-user-request]))

(defn- capture-pre-refresh-evidence-instruction
  [{:keys [worktree workspace]}]
  (fmt/reflow
   (format
    "|In worktree `%s`, before any `spool bump`, coordinate edit, or runtime
     |refresh, run exactly `strand --workspace %s spool status` once. Record one
     |complete `:bump-pre-refresh-evidence` map in the workflow context containing
     |the exact command, complete structured result, worktree, selected workspace,
     |Weaver identity, exact intended family/root set, and exact
     |`[family root] -> sync.root` map. Derive the intended set from the selected
     |activation and relevant producer metadata. Require `:families` and every
     |nested `:roots` map to cover exactly that set, with no missing, extra, or
     |mismatched family/root. Every intended root must have `:status :synced`, a
     |`:sync` map, and a nonempty `:sync.root`; the recorded current-root map must
     |equal that exact projection. Failed, conflicted, source-reload, partial,
     |missing, extra, mismatched, blank, or malformed evidence fails loudly before
     |any coordinate mutation. Every later bootstrap, consumer-tooling, and
     |refresh proof must reuse this exact context value without replacing,
     |weakening, or recapturing it. Do not run, retry, or otherwise re-enter
     |`spool status` later in this bump workflow."
    worktree workspace)))

(workflow/defworkflow! bump-spool
  "Bump selected spool families and configure consumer tooling.

  Start it with family names and the exact consumer paths:

  ```clojure
  (require '[millhouse.spools.workflow :as workflow])

  (workflow/start! \"consumer-bump\" :bump-spool
    {:families [\"io.millstrand/millstrand\" \"millhouse/spools\"]
     :worktree \"/abs/path/to/consumer-worktree\"
     :workspace \"/abs/path/to/consumer-worktree/.millstrand\"
     :invocation-producer {:kind \"pinned-remote-family\"
                           :family \"millhouse/spools\"
                           :coordinate {:git/url \"https://github.com/codethread/millhouse.spool.git\"
                                        :git/sha \"0123456789012345678901234567890123456789\"}}
     :direct-user-request false})
  ```

  The caller supplies exact consumer paths and family names. Before the first
  coordinate mutation, the workflow verifies and records one complete status
  result, exact intended family/root set, and `[family root] -> sync.root` map.
  Each bump then uses `spool bump FAMILY --latest sha`; an already-current
  coordinate is recorded and accepted. The shared bootstrap and consumer
  tooling workflows inherit the exact pre-bump evidence without another status
  command. Before the final refresh, an agent chooses the repository style and
  manually aligns LSP, lint, tests, and Weaver proof without new executor gates.
  Runtime cutover is offered only for a direct user request."
  {:entrypoints #{:start :call}
   :param-spec ::spool-bump-params
   :defaults {}
   :example {:families ["io.millstrand/millstrand"]
             :worktree "/abs/path/to/consumer-worktree"
             :workspace "/abs/path/to/consumer-worktree/.millstrand"
             :invocation-producer {:kind "pinned-remote-family"
                                   :family "millhouse/spools"
                                   :coordinate {:git/url "https://github.com/codethread/millhouse.spool.git"
                                                :git/sha "0123456789012345678901234567890123456789"}}
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
                :invocation-producer
                "Exact Millhouse producer coordinate forwarded to consumer tooling."
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
   (workflow/step :capture-pre-refresh-evidence
                  "Capture exact spool roots before coordinate mutation"
                  :self
                  :depends-on [:select-world]
                  :attributes
                  {"workflow/action-ref"
                   "millstrand-workflows.bump-spool.spool-status.capture"
                   "workflow/instruction" capture-pre-refresh-evidence-instruction})
   (workflow/step :bump-spool
                  (fn [{:keys [item]}]
                    (str "Request remote default-branch HEAD SHA for " item))
                  :self
                  :depends-on [:capture-pre-refresh-evidence]
                  :loop {:each :families :chain true}
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.coordinate.bump"
                   "workflow/instruction"
                   (fn [{:keys [item worktree workspace]}]
                     (fmt/reflow
                      (format
                       "|Require the exact `:bump-pre-refresh-evidence` recorded by
                        |`capture-pre-refresh-evidence`; refuse to mutate a coordinate
                        |when it is absent or malformed. From worktree `%s`, run exactly `strand --workspace %s
                        |spool bump %s --latest sha`. The explicit workspace is
                        |mandatory. Record the previous coordinate and resulting
                        |remote default-branch HEAD SHA. If the CLI reports that the coordinate is
                        |already current, record that outcome and continue."
                       worktree workspace item)))})
   (workflow/call :bootstrap-kondo
                  #'bootstrap/bootstrap-kondo
                  {:worktree (fn [{:keys [worktree]}] worktree)
                   :workspace (fn [{:keys [workspace]}] workspace)
                   :inherited-pre-refresh-evidence (constantly true)}
                  :depends-on [:bump-spool])
   (workflow/call :configure-consumer-tooling
                  #'tooling/configure-consumer-tooling
                  {:worktree (fn [{:keys [worktree]}] worktree)
                   :workspace (fn [{:keys [worktree]}]
                                (str (java.io.File. ^String worktree ".millstrand")))
                   :invocation-producer (fn [{:keys [invocation-producer]}]
                                          invocation-producer)
                   :inherited-pre-refresh-evidence (constantly true)}
                  :depends-on [:bootstrap-kondo])
   (workflow/step :refresh-runtime
                  "Refresh the selected runtime and record generation state"
                  :self
                  :depends-on [:configure-consumer-tooling]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.runtime.refresh"
                   "workflow/instruction"
                   (fn [{:keys [workspace]}]
                     (fmt/reflow
                      (format
                       "|Require and carry the unchanged exact
                        |`:bump-pre-refresh-evidence` through the preceding bootstrap
                        |and consumer-tooling proof. In the runtime for selected
                        |workspace `%s`, run
                        |`(runtime/refresh! (current/runtime))`. Record the full
                        |result and whether the bumped coordinate is adopted or
                        |pending. Refresh does not itself authorize a stop or
                       |restart."
                       workspace)))})
   (workflow/step :assess-authorized-cutover
                  "Assess generation state and use authorized cutover only when pending"
                  :self
                  :depends-on [:refresh-runtime]
                  :condition [:= :direct-user-request true]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.runtime.cutover"
                   "workflow/instruction"
                   (fmt/reflow
                    "|This step is present only for a direct user request. Inspect
                     |the recorded refresh and tooling evidence first. If no pending
                     |generation exists, do not stop or start anything; record that
                     |the adopted-generation Weaver proof is already complete. If a
                     |generation is pending, ask the direct user to confirm it, then
                     |stop and start only the selected runtime by its exact
                     |workspace/PID. Reconnect, verify the bumped coordinate is
                     |adopted with no pending generation, and repeat the selected
                     |repository-style Weaver check. Never infer restart authority
                     |from an agent, scheduled, or nested workflow call.")})
   (workflow/step :handover-runtime-generation-evidence
                  "Hand over adopted or pending runtime generation evidence"
                  :self
                  :depends-on [:refresh-runtime]
                  :condition [:= :direct-user-request false]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-spool.runtime.handover"
                   "workflow/instruction"
                   (fmt/reflow
                    "|Do not stop or restart any runtime. Record the bump results,
                     |bootstrap and repository-tooling handovers, refresh result,
                     |generation state, and exact selected workspace. If no pending
                     |generation exists, record that no cutover is required and the
                     |adopted-generation Weaver proof is complete. If a generation
                     |is pending, mark the selected repository-style Weaver check
                     |after cutover as unfinished and hand over that a direct user
                     |request is required before runtime cutover.")})))
