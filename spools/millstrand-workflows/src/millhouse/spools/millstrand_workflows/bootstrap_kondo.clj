(ns millhouse.spools.millstrand-workflows.bootstrap-kondo
  "Bootstrap Millstrand clj-kondo support in an explicitly selected consumer.

  The workflow asks whether the consumer is greenfield or brownfield before it
  gives configuration instructions. Both routes import the complete resolved
  classpath once, make explicit bootstrap the sole Kondo import owner, validate
  provenance and cache hygiene, and hand back the local quality command for the
  consumer rather than guessing one."
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

(defn- copy-configs-instruction
  [{:keys [worktree workspace]}]
  (fmt/reflow
   (format
    "|In `%s`, read the live resolved world before importing by running
     |`strand --workspace %s spool status` and inspecting its structured output.
     |Treat every intended installed root reported by that status as required:
     |fail loudly if any root is unresolved or missing. For each resolved root,
     |record its exact identity and reported `sync.root`, read that root's
     |`deps.edn`, and resolve its `:paths` relative to `sync.root`. Match the
     |runtime: absent `:paths` defaults to the `src` path, while explicit
     |`:paths []` remains empty. Do not guess paths. Combine those actual root
     |directories with the consumer Clojure classpath, and record the exact roots
     |and final classpath. The plain consumer `clojure -Spath` alone is
     |insufficient and must be explicitly rejected, including when the consumer
     |`deps.edn` has `:paths []`. Fail loudly when the installed-spool contribution
     |is empty.
     |Run exactly one command with the resolved classpath:
     |`clj-kondo --lint RESOLVED_CLASSPATH --dependencies --parallel
     |--copy-configs --skip-lint`. Do not require GitHub, GitLab, or `jq`."
    worktree workspace)))

(def ^:private validate-kondo-command
  "Check the LSP import boundary, formatting, provenance, and cache hygiene."
  ["sh" "-c"
   (str "set -eu\n"
        "test -f .lsp/config.edn\n"
        "clojure -e '"
        "(require '[clojure.edn :as edn]) "
        "(let [config (edn/read-string (slurp \".lsp/config.edn\"))] "
        "(when (not= false (:copy-kondo-configs? config)) "
        "(binding [*out* *err*] (println \".lsp/config.edn must set :copy-kondo-configs? false\")) "
        "(System/exit 1)))'\n"
        "git diff --check\n"
        "git check-ignore -q --no-index .clj-kondo/.cache/\n"
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
     |`.clj-kondo/.cache/` is ignored by the repository configuration. Merge
     |`:copy-kondo-configs? false` into `.lsp/config.edn`, creating that file only
     |when absent and preserving every other existing LSP setting. Do not
     |pre-create producer mappings or hooks; those come from the resolved
     |dependency exports. Record the exact files changed and the resulting LSP
     |setting."
    worktree)))

(defn- brownfield-instruction
  [{:keys [worktree]}]
  (fmt/reflow
   (format
    "|In `%s`, establish the brownfield Kondo boundary: inventory the existing
     |`.clj-kondo` config, imports, hooks, and
    |ignore rules before editing, and ensure `.clj-kondo/.cache/` is ignored by
     |the repository configuration. Merge `:copy-kondo-configs? false` into
     |`.lsp/config.edn` without overwriting any other existing LSP setting. Merge
     |only missing local settings and keep one producer-owned source for each
     |imported mapping. Remove no existing consumer rule without recording why,
     |and do not duplicate a producer hook or replace it with a consumer remap.
     |Record the inventory, merge, resulting LSP setting, and any unresolved
     |overlap for handover."
    worktree)))

(defn- validate-instruction
  [{:keys [worktree]}]
  (fmt/reflow
   (format
    "|In `%s`, validate the imported `.clj-kondo` tree against the resolved
     |classpath and producer `clj-kondo.exports` resources. Confirm every
     |Millstrand and installed sibling spool export has one provenance source,
     |no duplicate config or hook mapping, no overlapping consumer-owned remap,
     |and no tracked `.clj-kondo/.cache` file. Identify the consumer repository's
     |own producer namespace and import coordinates from its project metadata and
     |producer exports, then confirm those coordinates are absent from
     |`.clj-kondo/imports`; reject only that repository-relative self-import.
     |Legitimate Millhouse and other producer imports in a consumer are expected
     |and must not be rejected. Record the self-import result before import,
     |immediately after import, and after quality. Confirm `.lsp/config.edn`
     |exists and records `:copy-kondo-configs? false`, proving explicit bootstrap
     |remains the sole import owner. Record the producer path for each imported
     |mapping, the exact LSP setting, and run the supplied hygiene gate."
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
     |the exact `.lsp/config.edn` `:copy-kondo-configs? false` setting, the
     |consumer self-import result before/during/after quality, the discovered
     |quality commands and results, and any pending local action. Keep
     |local-checkout coordinates intact and do not stop, restart, push, land, or
     |create a release."
    worktree workspace)))

(defn- shared-steps
  "Return the steps shared by greenfield and brownfield bootstrap routes."
  []
  [(workflow/step :copy-configs
                  "Resolve installed spool classpaths and import Kondo configs"
                  :self
                  :depends-on [:prepare]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.copy"
                   "workflow/instruction" copy-configs-instruction})
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
  leave a precise handover. Both route preparations merge
  `:copy-kondo-configs? false` into `.lsp/config.edn` without overwriting other
  LSP settings, so explicit bootstrap remains the sole import owner. The
  agent-owned import runs `strand --workspace
  <workspace> spool status`, derives every installed root's classpath from its
  `sync.root` and `deps.edn` `:paths`, defaulting absent `:paths` to the `src`
  path while preserving explicit `[]`. It combines those directories with the
  consumer classpath and records the exact roots and classpath before one
  `clj-kondo --lint RESOLVED_CLASSPATH
  --dependencies --parallel --copy-configs --skip-lint` invocation. Plain
  consumer `clojure -Spath` alone is insufficient; unresolved roots and an empty
  installed-spool contribution fail loudly. No repository hosting or release
  operation is part of this workflow."
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
                                   :description (fmt/reflow
                                                 "|Create the minimal local Kondo boundary before importing
                                                  |producer exports.")
                                   :next :bootstrap-kondo-greenfield}
                                  {:key :brownfield
                                   :label "Brownfield"
                                   :description (fmt/reflow
                                                 "|Inventory and safely merge the existing Kondo boundary
                                                  |before importing producer exports.")
                                   :next :bootstrap-kondo-brownfield}
                                  {:key :unsupported
                                   :label "Stop"
                                   :description (fmt/reflow
                                                 "|Stop when the consumer's configuration ownership cannot
                                                  |be established.")}])))

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
