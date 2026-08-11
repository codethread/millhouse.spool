(ns millhouse.spools.millstrand-workflows.bootstrap-kondo
  "Bootstrap Millstrand clj-kondo support in an explicitly selected consumer.

  The workflow asks whether the consumer is greenfield or brownfield before it
  gives configuration instructions. Both routes import the complete resolved
  classpath once, validate provenance and cache hygiene, and hand back the
  local quality command for the consumer rather than guessing one."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as fmt]
            [millhouse.spools.workflow :as workflow]))

(defn- non-blank-string?
  "Return true when `value` is a non-blank string."
  [value]
  (and (string? value)
       (not (str/blank? value))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::worktree ::non-blank-string)
(s/def ::workspace ::non-blank-string)
(s/def ::bootstrap-kondo-params
  (s/keys :req-un [::worktree ::workspace]))

(def ^:private copy-configs-command
  "Import every resolved dependency export in one classpath invocation."
  ["sh" "-c"
   (str "set -eu\n"
        "clj-kondo --lint \"$(clojure -Spath)\" --dependencies --parallel"
        " --copy-configs --skip-lint\n")])

(def ^:private validate-kondo-command
  "Check formatting and reject tracked clj-kondo cache files after import."
  ["sh" "-c"
   (str "set -eu\n"
        "git diff --check\n"
        "test -z \"$(git ls-files '.clj-kondo/.cache/**')\"\n")])

(defn- select-world-instruction
  [{:keys [worktree workspace]}]
  (fmt/reflow
   (format
    "|Use worktree `%s` and Millstrand workspace `%s` exactly. Confirm the
     |worktree is the intended consumer checkout and the workspace is its
     |selected `.millstrand` world. Do not fall back to the process current
     |directory or a canonical workspace."
    worktree workspace)))

(defn- greenfield-instruction
  [{:keys [worktree]}]
  (fmt/reflow
   (format
    "|In `%s`, establish the greenfield Kondo boundary: create a minimal
     |`.clj-kondo/config.edn` only when it is absent, and ensure
     |`.clj-kondo/.cache/` is ignored by the consumer's Git ignore file. Do not
     |pre-create producer mappings or hooks; those come from the resolved
     |dependency exports. Record the exact files changed."
    worktree)))

(defn- brownfield-instruction
  [{:keys [worktree]}]
  (fmt/reflow
   (format
    "|In `%s`, inventory the existing `.clj-kondo` config, imports, hooks, and
     |ignore rules before editing. Merge only missing local settings and keep
     |one producer-owned source for each imported mapping. Remove no existing
     |consumer rule without recording why, and do not duplicate a producer hook
     |or replace it with a consumer remap. Record the inventory, merge, and any
     |unresolved overlap for handover."
    worktree)))

(defn- validate-instruction
  [{:keys [worktree]}]
  (fmt/reflow
   (format
    "|In `%s`, validate the imported `.clj-kondo` tree against the resolved
     |classpath and producer `clj-kondo.exports` resources. Confirm every
     |Millstrand and installed sibling spool export has one provenance source,
     |no duplicate config or hook mapping, no overlapping consumer-owned remap,
     |no generated self-import, and no tracked `.clj-kondo/.cache` file. Record
     |the producer path for each imported mapping and run the supplied hygiene
     |gate."
    worktree)))

(defn- quality-discovery-instruction
  [{:keys [worktree]}]
  (fmt/reflow
   (format
    "|In `%s`, discover the consumer's appropriate local quality checks from
     |its own Makefile, project documentation, scripts, and CI configuration.
     |Run the focused checks that cover this Kondo adoption and record the exact
     |commands and results. Do not assume a repository-wide command or invent a
     |GitHub/GitLab, push, PR, or CI-polling step."
    worktree)))

(defn- handover-instruction
  [{:keys [worktree workspace]}]
  (fmt/reflow
   (format
    "|Leave a precise local handover for worktree `%s` and workspace `%s`:
     |record greenfield/brownfield mode, files changed, every imported
     |producer/provenance path, duplicate and overlap decisions, cache hygiene,
     |the discovered quality commands and results, and any pending local action.
     |Keep local-checkout coordinates intact and do not stop, restart, push, land,
     |or create a release."
    worktree workspace)))

(defn- shared-steps
  "Return the steps shared by greenfield and brownfield bootstrap routes."
  []
  [(workflow/gate :copy-configs
                  "Import all resolved dependency clj-kondo configs"
                  :shell
                  :depends-on [:prepare]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.copy"
                   "shell/argv" copy-configs-command
                   "shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 300
                   "workflow/instruction"
                   (fmt/reflow
                    "|Run exactly one full resolved-classpath import in the
                     |selected worktree: `clj-kondo --lint \"$(clojure -Spath)\"
                     |--dependencies --parallel --copy-configs --skip-lint`.
                     |This must copy Millstrand and every installed sibling spool
                     |export in one invocation; do not run one import per spool.")})
   (workflow/gate :validate
                  "Validate Kondo provenance, duplicates, and cache hygiene"
                  :shell
                  :depends-on [:copy-configs]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.validate"
                   "shell/argv" validate-kondo-command
                   "shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 300
                   "workflow/instruction" validate-instruction})
   (workflow/step :discover-quality
                  "Discover and run appropriate local quality checks"
                  :self
                  :depends-on [:validate]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.quality"
                   "workflow/instruction" quality-discovery-instruction})
   (workflow/step :handover
                  "Leave the local Kondo bootstrap handover"
                  :self
                  :depends-on [:discover-quality]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.handover"
                   "workflow/instruction" handover-instruction})])

(workflow/defworkflow bootstrap-kondo
  "Choose a greenfield or brownfield Kondo bootstrap for a consumer checkout.

  Both routes import all resolved dependency exports once, validate provenance,
  duplicate mappings, and cache hygiene, discover local quality checks, and
  leave a precise handover. No repository hosting or release operation is part
  of this workflow."
  {:entrypoints #{:start :call}
   :param-spec ::bootstrap-kondo-params
   :defaults {}
   :example {:worktree "/abs/path/to/consumer-worktree"
             :workspace "/abs/path/to/consumer-worktree/.millstrand"}
   :param-docs {:worktree "Exact consumer worktree to inspect and update."
                :workspace "Exact Millstrand workspace selected for the consumer."}}
  (workflow/workflow
   "Bootstrap Millstrand clj-kondo support"
   (workflow/step :select-world
                  "Confirm the selected consumer worktree and workspace"
                  :self
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.world"
                   "workflow/instruction" select-world-instruction})
   (workflow/checkpoint :adoption-mode
                        "Choose the consumer's Kondo adoption mode"
                        :kind :agent
                        :depends-on [:select-world]
                        :choices [{:key :greenfield
                                   :label "Greenfield"
                                   :description "Create the minimal local Kondo boundary before importing producer exports."
                                   :next :bootstrap-kondo-greenfield}
                                  {:key :brownfield
                                   :label "Brownfield"
                                   :description "Inventory and safely merge the existing Kondo boundary before importing producer exports."
                                   :next :bootstrap-kondo-brownfield}
                                  {:key :unsupported
                                   :label "Stop"
                                   :description "Stop when the consumer's configuration ownership cannot be established."}])))

(workflow/defworkflow bootstrap-kondo-greenfield
  "Establish a greenfield Kondo boundary and import Millstrand exports."
  {:entrypoints #{:continue}
   :param-spec ::bootstrap-kondo-params}
  (apply workflow/workflow
         "Bootstrap Kondo (greenfield)"
         (workflow/step :prepare
                        "Establish minimal Kondo config and cache ignore"
                        :self
                        :attributes
                        {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.greenfield"
                         "workflow/instruction" greenfield-instruction})
         (shared-steps)))

(workflow/defworkflow bootstrap-kondo-brownfield
  "Inventory and merge an existing Kondo boundary before importing exports."
  {:entrypoints #{:continue}
   :param-spec ::bootstrap-kondo-params}
  (apply workflow/workflow
         "Bootstrap Kondo (brownfield)"
         (workflow/step :prepare
                        "Inventory and safely merge existing Kondo config"
                        :self
                        :attributes
                        {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.brownfield"
                         "workflow/instruction" brownfield-instruction})
         (shared-steps)))
