(ns millhouse.spools.millstrand-workflows.bootstrap-kondo
  "Bootstrap Millstrand clj-kondo support in an explicitly selected consumer.

  The workflow asks whether the consumer is greenfield or brownfield before it
  gives configuration instructions. Both routes import the complete eligible
  resolved classpath once, excluding consumer-owned producer roots, make
  explicit bootstrap the sole Kondo import owner, manually validate provenance
  and cache hygiene, and hand back the local quality command for the consumer
  rather than guessing one."
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
     |fail loudly if any root is unresolved or missing. Before resolving any
     |classpath, parse the selected `spools.edn` and the consumer's producer
     |metadata once into an `owned-roots` table. Identify exact family/root
     |coordinates whose local coordinate resolves inside this worktree and whose
     |declared root is a consumer-owned producer root. Record each exact key,
     |coordinate, canonical owner path, reported `sync.root`, and export
     |directory. Match by exact family/root and coordinate; do not infer
     |ownership from namespace text or path resemblance. Every candidate owned
     |root must match exactly one status root; fail loudly before import when it
     |matches zero or more than one, reporting the candidates and permitted
     |ownership invariant. For each resolved root, record its exact identity and
     |reported `sync.root`, read that root's `deps.edn`, and resolve its `:paths`
     |relative to `sync.root`. For every declared `millstrand/source-root`
     |coordinate, handle the source-root specially while still processing its
     |installed spool root normally. Treat
     |the declaration as a relative path and derive `BASE` by removing exactly
     |its path segments from the end of the reported `sync.root`; validate that
     |normalized `BASE` joined with the declared path is exactly `sync.root`.
     |Fail loudly when the relative path is absolute, escapes, or cannot be
     |reconciled. Never search upward, guess `BASE`, or use the worktree/current
     |directory as a substitute. Read `BASE/deps.edn` and fail loudly when it is
     |missing, not a regular file, unreadable, or invalid. Resolve its `:paths`
     |relative to `BASE` and add every declared directory (including `resources`)
     |to the resolved classpath, using the same absent `:paths` => `src` and
     |explicit `:paths []` => empty semantics. Require the Millstrand core
     |export at `BASE/resources/clj-kondo.exports/io.millstrand/millstrand/`;
     |fail loudly when that resource is absent. Match the runtime for ordinary
     |roots too: absent `:paths` defaults to the `src` path, while explicit
     |`:paths []` remains empty. Do not guess paths. Canonicalize every installed
     |contribution entry, every entry in the consumer Clojure classpath, and every
     |owned producer classpath or export comparison path with the same canonical
     |path operation used for the owned-root table, before taking the union or
     |applying ownership filtering. Any failed canonicalization is a loud
     |pre-copy failure. Combine the canonical contributions and apply ownership
     |classification to that final combined classpath: exclude an entry when it
     |is exactly an owned producer classpath or export path, or is a descendant of
     |one, according to the canonical owned-root table. This final pass must
     |remove a consumer `clojure -Spath` reintroduction even when the same owned
     |path was already removed from the installed contribution. If a classpath
     |entry cannot be canonicalized or reconciled with the table, fail loudly; do
     |not compare raw strings, infer ownership from a path prefix, or silently
     |retain an ambiguous entry. Apply this rule to every consumer-owned producer
     |export in a Millhouse local-self world. Keep every non-owned dependency
     |export, including a pinned
     |remote `millhouse/spools` family. Keep the explicit `millstrand/source-root`
     |contribution and its `BASE`-derived paths as the declared exception: they are
     |retained even when under the worktree and are not owned producer paths merely
     |because of that location. Record the owned roots, retained dependency roots,
     |and final canonical classpath. Record the exact roots and final canonical
     |classpath. The plain consumer
     |`clojure -Spath` alone is insufficient and
     |must be explicitly rejected, including when the consumer `deps.edn` has
     |`:paths []`. Fail loudly when the filtered installed-spool contribution is
     |empty. `RESOLVED_CLASSPATH` must be this final canonical-filtered
     |classpath; do not copy first and remove self-imports afterward. Every
     |`RESOLVED_CLASSPATH` entry must be canonical. Immediately before
     |`KONDO_CMD`, assert using only canonical paths that no owned producer
     |classpath or export path, nor any descendant, remains in
     |`RESOLVED_CLASSPATH`, and that the canonical owned export paths themselves
     |are absent. If the canonical-only assertion or its ownership check cannot be
     |reconciled, fail loudly. Every failure above must report the exact family/root
     |coordinate,
     |status, `sync.root`, declaration, derived `BASE`, and failing path as
     |applicable, plus the permitted corrective invariant: a synced root; a
     |relative non-escaping declaration that reconstructs `sync.root`; a readable
     |regular `BASE/deps.edn` containing valid EDN; the required export path; or at
     |least one installed-spool classpath directory. Never silently fall back.
     |Using the exact `KONDO_CMD` command prefix verified by the preceding step,
     |run exactly one import invocation:
     |`KONDO_CMD --lint RESOLVED_CLASSPATH --dependencies --parallel
     |--copy-configs --skip-lint`. Do not require GitHub, GitLab, or `jq`."
    worktree workspace)))

(defn- select-world-instruction
  [{:keys [worktree workspace]}]
  (fmt/reflow
   (format
    "|Use worktree `%s` and Millstrand workspace `%s` exactly. Confirm the
     |worktree is the intended consumer checkout and the workspace is its
     |selected `.millstrand` world. Do not fall back to the process current
     |directory or a canonical workspace."
    worktree workspace)))

(defn- ensure-kondo-instruction
  [{:keys [worktree]}]
  (fmt/reflow
   (format
    "|In `%s`, inspect the repository's Makefile, `deps.edn`, scripts, and CI for
     |an existing clj-kondo command that accepts raw clj-kondo arguments. A
     |tools.deps invocation such as `clojure -M:lint` is valid when its alias
     |resolves clj-kondo and accepts the import flags. Otherwise check for a
     |standalone executable with `command -v clj-kondo`. Record the selected
     |command prefix as `KONDO_CMD` and verify `KONDO_CMD --version`; do not
     |reinstall or replace a working repository-native command. When neither form
     |is available, stop before importing configs and discuss the official
     |installation options with the user:
     |https://github.com/clj-kondo/clj-kondo/blob/master/doc/install.md
     |Let the user choose the installation method and destination. Do not download
     |or run an installer or package-manager command without their explicit
     |approval. After an approved installation, record and verify `KONDO_CMD`
     |before continuing. If the user declines installation, leave the bootstrap
     |blocked and record the missing prerequisite."
    worktree)))

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
     |`.clj-kondo` config, imports, hooks, and ignore rules before editing, and
     |ensure `.clj-kondo/.cache/` is ignored by
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
     |classpath and producer `clj-kondo.exports` resources. Reuse the exact
     |`owned-roots` table parsed before import; do not rediscover ownership from
     |the copied tree. Confirm every retained Millstrand and installed sibling
     |spool export has one provenance source, no duplicate config or hook mapping,
     |no overlapping consumer-owned remap, and no tracked `.clj-kondo/.cache` file.
     |Identify the consumer repository's own producer namespace and import
     |coordinates through that table. Confirm every owned producer export is
     |absent from `.clj-kondo/imports` and
     |every retained dependency export expected by status is present exactly once.
     |For a Millhouse local-self root, reject every repository-relative self-import;
     |for an ordinary pinned remote Millhouse family, retain and validate its
     |producer imports. Legitimate Millhouse and other producer imports remain
     |valid. Any missing, duplicate, or ambiguous ownership result fails loudly.
     |Record the self-import result before import,
     |immediately after import, and after quality. Confirm `.lsp/config.edn`
     |exists and records `:copy-kondo-configs? false`, proving explicit bootstrap
     |remains the sole import owner. Record the producer path for each imported
     |mapping and the exact LSP setting. From the worktree, run these exact
     |hygiene checks manually and stop if any check fails:
     |`test -f .lsp/config.edn`;
     |`clojure -e '(require (symbol \"clojure.edn\")) (let [config
     | (clojure.edn/read-string (slurp \".lsp/config.edn\")) observed
     | (:copy-kondo-configs? config)] (when (not= false observed)
     | (binding [*out* *err*] (println \".lsp/config.edn must set
     | :copy-kondo-configs? false; observed\" (pr-str observed)))
     | (System/exit 1)))'`;
     |`git diff --check`;
     |`git check-ignore -q --no-index .clj-kondo/.cache/`; and
     |`test -z \"$(git ls-files '.clj-kondo/.cache/**')\"`. Record each command
     |and result in the handover."
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
  [(workflow/step :ensure-kondo
                  "Ensure the clj-kondo binary is available"
                  :self
                  :depends-on [:prepare]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.ensure-kondo"
                   "workflow/instruction" ensure-kondo-instruction})
   (workflow/step :copy-configs
                  "Resolve installed spool classpaths and import Kondo configs"
                  :self
                  :depends-on [:ensure-kondo]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.copy"
                   "workflow/instruction" copy-configs-instruction})
   (workflow/step :validate
                  "Validate Kondo provenance, duplicates, and cache hygiene"
                  :self
                  :depends-on [:copy-configs]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.validate"
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

  Start it with the exact consumer worktree and Millstrand workspace:

  ```clojure
  (require '[millhouse.spools.workflow :as workflow])

  (workflow/start! \"consumer-kondo\" :bootstrap-kondo
    {:worktree \"/abs/path/to/consumer-worktree\"
     :workspace \"/abs/path/to/consumer-worktree/.millstrand\"})
  ```

  Both routes import all eligible resolved dependency exports once, excluding
  consumer-owned producer roots, validate provenance, duplicate mappings, and
  cache hygiene manually, discover local quality checks, and leave a precise
  handover. Both route preparations merge
  `:copy-kondo-configs? false` into `.lsp/config.edn` without overwriting other
  LSP settings, so explicit bootstrap remains the sole import owner. Before
  resolving paths, the agent parses the selected spool metadata once into an
  `owned-roots` table. It canonicalizes every installed contribution entry,
  every consumer classpath entry, and every owned classpath/export comparison
  path with one path operation before union and ownership filtering. A failed
  canonicalization is a loud pre-copy failure. It filters the final combined
  classpath for exact owned producer classpath/export paths and their
  descendants, retains true remote dependency exports, and asserts immediately
  before `KONDO_CMD` using only canonical paths that no owned export remains. It
  fails loudly on ambiguous or unreconcilable canonical ownership. The explicit
  `millstrand/source-root` contribution and its `BASE`-derived paths remain
  retained even under the worktree. The agent-owned import runs
  `strand --workspace
  <workspace> spool status`, derives every installed root's classpath from its
  `sync.root` and `deps.edn` `:paths`, defaulting absent `:paths` to the `src`
  path while preserving explicit `[]`. A declared `millstrand/source-root`
  coordinate is special: derive `BASE` by removing exactly its relative path
  segments from the end of `sync.root`, validate that `BASE` joined with the
  declaration reconstructs `sync.root`, then read `BASE/deps.edn` and add its
  declared paths, including `resources`, to the classpath. Require the
  Millstrand core export at
  `BASE/resources/clj-kondo.exports/io.millstrand/millstrand/` and fail loudly
  for an absent or unreconcilable base, a `BASE/deps.edn` that is missing, not a
  readable regular file, or invalid EDN, or a missing export; do not search
  upward or guess. Failures report the exact coordinate, source-root values,
  failing path, and permitted corrective invariant. The installed source-root
  spool is still resolved normally. It canonicalizes every installed directory,
  consumer classpath entry, and owned classpath/export comparison path with the
  same operation before union and filtering, then records the exact roots and
  final canonical-filtered classpath, including each base derivation, before one
  `KONDO_CMD --lint RESOLVED_CLASSPATH
  --dependencies --parallel --copy-configs --skip-lint` invocation. Immediately
  before it, the agent asserts using only canonical paths that no owned producer
  classpath or export path, or descendant, remains in `RESOLVED_CLASSPATH`, and
  that every owned export path is absent. Plain consumer `clojure -Spath` alone
  is insufficient; unresolved or ambiguously owned roots, failed
  canonicalization, unreconcilable canonical ownership, and an empty filtered
  installed-spool contribution fail loudly. No cleanup-after-copy workaround is
  allowed. No repository hosting or release operation is part of this workflow."
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
  "Establish a greenfield Kondo boundary and import Millstrand exports.

  This is the `greenfield` continuation selected by `bootstrap-kondo`; callers
  normally start the parent workflow so adoption mode is recorded first."
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
  "Inventory and merge an existing Kondo boundary before importing exports.

  This is the `brownfield` continuation selected by `bootstrap-kondo`; callers
  normally start the parent workflow so adoption mode is recorded first."
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
