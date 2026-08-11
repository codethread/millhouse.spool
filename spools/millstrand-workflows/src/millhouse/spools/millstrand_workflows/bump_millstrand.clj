(ns millhouse.spools.millstrand-workflows.bump-millstrand
  "The local-aware consumer workflow for updating Millstrand.

  The workflow does not guess whether a consumer dependency is local or
  pinned. It asks the caller to inspect the selected deps.edn and records that
  classification as a checkpoint choice. A local checkout needs a second
  explicit decision before validation; a pinned checkout delegates to the
  registered bump-spool workflow."
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

(defn- millstrand-request?
  "Return true when `bumps` contains exactly the Millstrand request."
  [bumps]
  (and (= 1 (count bumps))
       (= "io.millstrand/millstrand" (:family (first bumps)))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::family ::non-blank-string)
(s/def ::version spool-version?)
(s/def ::bump (s/keys :req-un [::family ::version]))
(s/def ::bumps
  (s/and (s/coll-of ::bump :kind vector? :count 1)
         millstrand-request?))
(s/def ::worktree ::non-blank-string)
(s/def ::workspace ::non-blank-string)
(s/def ::deps-file ::non-blank-string)
(s/def ::direct-user-request boolean?)
(s/def ::quality-argv
  (s/coll-of ::non-blank-string :kind vector? :min-count 1))
(s/def ::millstrand-bump-params
  (s/keys :req-un [::bumps ::worktree ::workspace ::direct-user-request]
          :opt-un [::deps-file ::quality-argv]))

(def ^:private copy-configs-command
  "Import every resolved dependency export in one classpath invocation."
  ["sh" "-c"
   (str "set -eu\n"
        "clj-kondo --lint \"$(clojure -Spath)\" --dependencies --parallel"
        " --copy-configs --skip-lint\n")])

(def ^:private validate-kondo-command
  "Validate the selected local dependency classpath after importing exports."
  ["sh" "-c"
   (str "set -eu\n"
        "clj-kondo --lint \"$(clojure -Spath)\" --dependencies --parallel\n")])

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
    "|The consumer is intentionally using the local Millstrand checkout. In
     |`%s`, record that decision and run the imported dependency clj-kondo
     |configuration through the validation gate. Do not replace the local
     |coordinate with a fabricated Git SHA."
    worktree)))

(defn- refresh-instruction
  [{:keys [workspace]}]
  (fmt/reflow
   (format
    "|In the runtime for selected workspace `%s`, run
     |`(runtime/refresh! (current/runtime))`. Record the full result and
     |whether the local Millstrand checkout is adopted or pending. Refresh
     |does not itself authorize a stop or restart."
    workspace)))

(defn- quality-instruction
  [{:keys [quality-argv]}]
  (fmt/reflow
   (format
    "|In the selected worktree, run the consumer's ordinary quality boundary
     |from `quality-argv` (`%s`). A failing check blocks runtime refresh and
     |cutover; fix the local-checkout integration and retry."
    (pr-str quality-argv))))

(workflow/defworkflow bump-millstrand
  "Inspect a consumer Millstrand coordinate and choose its honest update path.

  A local sibling coordinate is never converted into a guessed SHA. The
  classification and local-checkout decision are recorded as checkpoints. A
  Git/SHA-pinned coordinate routes to the registered bump-spool workflow with
  the single Millstrand request supplied by the caller."
  {:entrypoints #{:start}
   :param-spec ::millstrand-bump-params
   :defaults {:deps-file "deps.edn"
              :quality-argv ["make" "quality"]}
   :example {:bumps [{:family "io.millstrand/millstrand" :version "latest"}]
             :worktree "/abs/path/to/consumer-worktree"
             :workspace "/abs/path/to/consumer-worktree/.millstrand"
             :direct-user-request false
             :deps-file "deps.edn"
             :quality-argv ["make" "quality"]}
   :param-docs {:bumps
                (fmt/reflow
                 "|Exactly one request for `io.millstrand/millstrand`. The version
                  |is passed to `bump-spool` only when the inspected coordinate is
                  |Git/SHA-pinned; it is never invented for a local checkout.")
                :worktree
                (fmt/reflow
                 "|Exact consumer worktree in which inspection and validation run.")
                :workspace
                (fmt/reflow
                 "|Exact Millstrand workspace selected for this consumer. It is
                  |passed to the delegated bump workflow and runtime instructions.")
                :direct-user-request
                (fmt/reflow
                 "|True only when the direct user requested runtime cutover. It
                  |never follows from a local-checkout or classification choice.")
                :deps-file
                (fmt/reflow
                 "|The deps.edn path relative to `worktree`; it defaults to
                  |`deps.edn` and is inspected explicitly.")
                :quality-argv
                (fmt/reflow
                 "|The consumer's ordinary quality command, defaulting to
                  |`[\"make\" \"quality\"]`.")}}
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
                                   :description "The deps.edn coordinate resolves to a local sibling; preserve it and decide explicitly whether to continue."
                                   :next :bump-millstrand-local}
                                  {:key :git-sha-pinned
                                   :label "Use Git/SHA pin"
                                   :description "The deps.edn coordinate is Git/SHA-pinned; delegate the requested Millstrand bump."
                                   :next :bump-millstrand-pinned}
                                  {:key :unsupported
                                   :label "Stop"
                                   :description "The coordinate is neither an accepted local checkout nor a Git/SHA pin; stop for repair."}])))

(workflow/defworkflow bump-millstrand-local
  "Require an explicit decision before validating a local Millstrand checkout."
  {:entrypoints #{:continue}
   :param-spec ::millstrand-bump-params}
  (workflow/workflow
   "Use local Millstrand checkout"
   (workflow/checkpoint :local-checkout-decision
                        "Decide whether to continue with the local Millstrand checkout"
                        :kind :agent
                        :choices [{:key :move-forward
                                   :label "Move forward"
                                   :description "Keep the local coordinate and run consumer clj-kondo import and validation."
                                   :next :bump-millstrand-local-validate}
                                  {:key :stop
                                   :label "Stop"
                                   :description "Do not validate or alter the consumer until a local-checkout decision is supplied."}])))

(workflow/defworkflow bump-millstrand-local-validate
  "Validate a consumer against its explicitly approved local Millstrand checkout."
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
   (workflow/gate :copy-configs
                  "Import resolved dependency clj-kondo configs"
                  :shell
                  :depends-on [:record-local-choice]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-millstrand.local.kondo.copy"
                   "shell/argv" copy-configs-command
                   "shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 300
                   "workflow/instruction"
                   (fmt/reflow
                    "|In the selected worktree, import every resolved dependency
                     |clj-kondo export in one `clojure -Spath` invocation. The
                     |local Millstrand export must be reachable; do not run a
                     |separate import per dependency.")})
   (workflow/gate :validate-kondo
                  "Validate the imported local Millstrand clj-kondo contract"
                  :shell
                  :depends-on [:copy-configs]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-millstrand.local.kondo.validate"
                   "shell/argv" validate-kondo-command
                   "shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 300
                   "workflow/instruction"
                   (fmt/reflow
                    "|Run the dependency clj-kondo validation command in the
                     |selected worktree after import. A failure blocks quality
                     |and runtime refresh.")})
   (workflow/gate :quality
                  "Run ordinary consumer quality checks"
                  :shell
                  :depends-on [:validate-kondo]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-millstrand.local.quality"
                   "shell/argv" (fn [{:keys [quality-argv]}] quality-argv)
                   "shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 1800
                   "workflow/instruction" quality-instruction})
   (workflow/step :refresh-runtime
                  "Refresh the selected runtime after local validation"
                  :self
                  :depends-on [:quality]
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
                   "workflow/instruction"
                   (fmt/reflow
                    "|This step is present only for a direct user request. Ask the
                     |direct user to confirm the recorded pending generation,
                     |then stop and start only the selected runtime by its exact
                     |workspace/PID. Never infer this authority from an agent,
                     |scheduled, or nested workflow call.")})
   (workflow/step :handover-pending-generation
                  "Hand over pending runtime generation"
                  :self
                  :depends-on [:refresh-runtime]
                  :condition [:= :direct-user-request false]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bump-millstrand.local.runtime.handover"
                   "workflow/instruction"
                   (fmt/reflow
                    "|Do not stop or restart any runtime. Record the refresh
                     |result, pending generation, and exact selected workspace;
                     |hand over that direct-user authorization is required before
                     |runtime cutover.")})))

(workflow/defworkflow bump-millstrand-pinned
  "Delegate a Git/SHA-pinned Millstrand update to registered bump-spool."
  {:entrypoints #{:continue}
   :param-spec ::millstrand-bump-params}
  (workflow/workflow
   "Bump pinned Millstrand"
   (workflow/call :bump-spool
                  :bump-spool
                  {:bumps (fn [{:keys [bumps]}] bumps)
                   :worktree (fn [{:keys [worktree]}] worktree)
                   :workspace (fn [{:keys [workspace]}] workspace)
                   :direct-user-request (fn [{:keys [direct-user-request]}]
                                          direct-user-request)
                   :quality-argv (fn [{:keys [quality-argv]}] quality-argv)})))
