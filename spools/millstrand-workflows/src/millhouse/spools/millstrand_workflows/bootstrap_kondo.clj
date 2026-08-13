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
(s/def ::inherited-pre-refresh-evidence boolean?)
(s/def ::bootstrap-kondo-params
  (s/keys :req-un [::worktree ::workspace]
          :opt-un [::inherited-pre-refresh-evidence]))

(defn- copy-configs-instruction
  [{:keys [worktree]}]
  (fmt/reflow
   (format
    "|In `%s`, use only the pre-refresh spool-status evidence recorded by
     |`capture-spool-status`; do not run, retry, or otherwise re-enter that CLI
     |operation after refresh. Treat every intended installed root reported by
     |the recorded structured result as required:
     |fail loudly if any root is unresolved or missing. For a fully applied or
     |unchanged refresh with no pending generation, each intended root uses its
     |active `sync.root`. For the one supported pending next-generation shape,
     |the preceding refresh result must have top-level `:status :partial` and a
     |nonempty `:modules` outcome map. Every top-level `:modules` map key must
     |equal its outcome `:module/key`; reject any missing or mismatched map-key
     |identity. Every module outcome must be exactly one of three forms: (a) an
     |unchanged module with `:status :unchanged` and no `:error`, `:reason`,
     |refusal, `:root/outcome`, `:dependency`, or `:dependency/outcome`; (b) direct
     |`:status :refused` and `:reason :hard-conflict`, carrying the exact
     |`:root/outcome`; or (c) `:status :failed` or `:status :skipped` with
     |`:reason :missing-dependency`, whose `:dependency` equals the nested
     |`:dependency/outcome` `:module/key` at every hop. Missing-dependency
     |wrappers must form a finite acyclic chain of the same allowed wrapper
     |shape and terminate in an exact direct refused hard-conflict outcome.
     |The direct outcome has `:status :hard-conflict` and must carry `:root-lib`
     |equal to exactly one `:lib` in the declared nonempty changed-root set;
     |reject any direct or terminal refusal whose `:root-lib` is outside or
     |mismatched against the declared nonempty changed-root set. No `:applied`
     |outcome,
     |other status or reason, unrelated terminal, missing or mismatched
     |dependency outcome, cycle, or other refusal/error may be present.
     |Declare one nonempty prepared conflict classification with exactly
     |`:changed-roots` and `:namespace-residuals`. Every direct refusal and
     |every terminal refusal reached through a wrapper chain must carry that
     |exact same shared classification. Every such root outcome's `:conflict`
     |must contain exactly `:changed-roots` and `:namespace-residuals`, and its
     |`:changed-roots` must equal that exact declared set. Each changed-root
     |entry must have exactly `:lib`, `:previous-root`, and `:new-root`.
     |Resolve every changed `:lib` to exactly one family/root in the pre-refresh
     |current-root evidence. Its `:previous-root` must equal that recorded
     |`sync.root`; its `:new-root` is the prepared-root evidence. The changed-root
     |set must equal the prepared-root set exactly. Unchanged intended roots stay
     |on their recorded pre-refresh current roots. Reject any missing, extra,
     |ambiguous, or mismatched current, changed, or prepared root.
     |Accept residuals only when every one maps to exactly one changed-root
     |entry. The only allowed residual reasons are `:root-repointed` and
     |`:unledgered-loaded-namespace`; every allowed residual must have a
     |nonempty `:namespace` and nonempty `:providers`. A `:root-repointed`
     |residual must have exactly one old `:binding`; its binding and every
     |provider must use `:root-lib` equal to the matched changed-root `:lib`.
     |The binding `:root` must equal `:previous-root`, while every provider
     |`:root` must equal `:new-root`. An
     |`:unledgered-loaded-namespace` residual must have no binding, and every
     |provider must use that same `:root-lib` and `:new-root`. Every binding and
     |provider `:namespace` must equal the residual namespace. Every binding
     |and provider `:file` path must be nonempty, canonical, and belong to its
     |stated root; provider paths must be distinct. Reject an empty `:providers`
     |collection and every other empty or vacuous,
     |duplicate, missing, extra, unrelated, or mixed mappings, including a
     |wrong `:root-lib`, wrong root, wrong namespace, or wrong provider path.
     |An extra conflict or residual key or any mismatch fails loudly. Record the
     |current and prepared generations and select
     |exactly one unambiguous prepared `new-root` for every changed declared
     |root. Verify that each prepared path exists, belongs to the declared
     |family/root and coordinate, has matching cache provenance, and has
     |readable `deps.edn` and the required exports. Never use the active old
     |`sync.root` for a changed family. Unchanged roots remain on active
     |`sync.root`. Any other partial/error shape or any mismatch fails loudly
     |before import. The runtime status must also contain a matching
     |`:pending-generation` with exactly `:status`, `:generation`, `:diff`,
     |`:approved-spools`, and `:remedy`; its `:diff` must be the same changed-root
     |and residual classification, including both `:changed-roots` and
     |`:namespace-residuals`. If no coordinate changed, accept only the recorded
     |full `(runtime/refresh! (current/runtime))` result with top-level `:status
     |:unchanged`, every entry in `:modules` having the exact unchanged shape
     |above, and
     |the recorded `(runtime/status (current/runtime))` result having
     |`:pending-generation nil`. Combine those results with the exact intended
     |family/root set and `[family root] -> sync.root` current-root evidence from
     |the pre-refresh capture. The unchanged refresh proves that every recorded
     |active root remains current. Reject `:status :applied`,
     |`:status :partial`, `:status :refused`, refresh errors, other module
     |statuses, non-nil pending generation, or absent, contradictory, or
     |malformed evidence. Kondo,
     |LSP, and tools.deps all use these prepared roots; Weaver proof remains
     |current-generation-only and makes no adoption claim. Before resolving any
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
     |Before the one import, parse the consumer `.clj-kondo/config.edn` once and
     |inventory each `:config-paths` entry both as its original value and as its
     |resolved target. Preserve every existing local config-path entry byte for
     |byte; do not remove, replace, rewrite, or silently deduplicate it. The
     |retained dependency export inventory is the only set eligible for new
     |activation.
     |Using the exact `KONDO_CMD` command prefix verified by the preceding step,
     |run exactly one import invocation:
     |`KONDO_CMD --lint RESOLVED_CLASSPATH --dependencies --parallel
     |--copy-configs --skip-lint`. Do not require GitHub, GitLab, or `jq`.
     |After the import, manually merge one repository-relative
     |activation entry for each retained dependency export, normally
     |`imports/<group>/<artifact>`, resolved from `.clj-kondo/config.edn`. If an
     |existing portable entry already resolves to the retained import, validate
     |it as that one activation and do not append a duplicate. Persist the
     |relative entry, never its canonical or absolute target. Canonicalize the
     |resolved target only for provenance, ownership, ambiguity, and exact-once
     |identity comparison. An absolute or outside-repository activation fails
     |loudly; so does an absent, duplicate, ambiguous, or owned target. Do not
     |discover a replacement path or edit this by script."
    worktree)))

(defn- select-world-instruction
  [{:keys [worktree workspace]}]
  (fmt/reflow
   (format
    "|Use worktree `%s` and Millstrand workspace `%s` exactly. Confirm the
     |worktree is the intended consumer checkout and the workspace is its
     |selected `.millstrand` world. Do not fall back to the process current
     |directory or a canonical workspace."
    worktree workspace)))

(defn- capture-spool-status-instruction
  [{:keys [worktree workspace inherited-pre-refresh-evidence]}]
  (if inherited-pre-refresh-evidence
    (fmt/reflow
     (format
      "|In `%s`, reuse only the complete `:bump-pre-refresh-evidence` recorded
       |by the calling bump workflow before its first coordinate mutation. Do
       |not run, retry, or otherwise re-enter `spool status`. Require the
       |evidence to contain the exact command, complete structured result,
       |selected workspace, Weaver identity, intended family/root set, and
       |`[family root] -> sync.root` map. Verify that its worktree is `%s` and
       |its workspace is `%s`, then carry that same evidence through this run's
       |refresh proof without replacing, weakening, or recapturing it. Reject
       |missing or contradictory inherited evidence loudly. Derive one exact
       |intended family/root set from the selected activation and relevant
       |producer metadata. Require `:families` and every nested `:roots` map to
       |cover exactly that set, with no missing, extra, or mismatched family/root.
       |Every intended root must have `:status :synced`, a `:sync` map, and a
       |nonempty `:sync.root`. Require the inherited `[family root] -> sync.root`
       |map to equal that exact projection. Failed, conflicted, source-reload,
       |partial, missing, extra, mismatched, blank, or malformed root evidence
       |fails loudly. This inherited evidence is the only spool-status input
       |used by the route after coordinate mutation or refresh."
      worktree worktree workspace))
    (fmt/reflow
     (format
      "|In `%s`, before any coordinate edit or runtime refresh, run exactly
       |`strand --workspace %s spool status` once and record its complete
     |structured result, selected workspace, and Weaver identity. Derive one
     |exact intended family/root set from the selected activation and relevant
     |producer metadata. Require `:families` and every nested `:roots` map to
     |cover exactly that set, with no missing, extra, or mismatched family/root.
     |Every intended root must have `:status :synced`, a `:sync` map, and a
     |nonempty `:sync.root`. Record the exact `[family root] -> sync.root` map as
     |pre-refresh current-root evidence. Failed, conflicted, source-reload,
     |partial, missing, extra, mismatched, blank, or malformed root evidence
     |fails loudly. When this run belongs to `configure-consumer-tooling`, require
     |the complete result, intended set, current-root map, workspace, and Weaver
     |identity to match its repository inspection exactly. Do not continue on
     |any mismatch. This recorded evidence is the only spool-status input used by
     |the route after refresh."
      worktree workspace))))

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
     |Immediately after import, reread `.clj-kondo/config.edn` and validate its
     |`:config-paths` against the retained dependency export inventory and the
     |same canonical resolved targets recorded before import. Every retained
     |dependency producer export must have exactly one matching copied import
     |path activated exactly once. Existing local config-path entries and their
     |values must remain byte-for-byte unchanged. Fail loudly when an expected
     |activation is missing, appears more than once, resolves to more than one
     |copied export, is absolute, escapes the repository, or resolves to an
     |owned producer export; do not accept an ambiguous path or silently repair
     |it. A portable existing entry that resolves to a retained import is the one
     |activation and must not be duplicated. Canonicalization is for provenance,
     |ownership, ambiguity, and exact-once identity comparison only.
     |Identify the consumer repository's own producer namespace and import
     |coordinates through that table. Confirm every owned producer export is
     |absent from `.clj-kondo/imports` and from `:config-paths`, and
     |every retained dependency export expected by status is present exactly
     |once and activated exactly once.
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
     |producer/provenance path, every retained repository-relative
     |`:config-paths` activation exactly once, preserved local config paths,
     |duplicate and overlap decisions, cache hygiene,
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
  handover. After import, each retained dependency export is manually activated
  exactly once in the consumer `.clj-kondo/config.edn` `:config-paths` using a
  repository-relative entry resolved from that config, normally
  `imports/<group>/<artifact>`. Existing local entries remain byte-for-byte
  preserved. An existing portable entry that resolves to the retained import is
  validated as the one activation rather than duplicated. The workflow
  canonicalizes resolved targets only for provenance, ownership, ambiguity, and
  exact-once identity comparison; absolute or outside-repository activations,
  missing, duplicate, ambiguous, or owned paths fail loudly. Both route
  preparations merge
  `:copy-kondo-configs? false` into `.lsp/config.edn` without overwriting other
  LSP settings, so explicit bootstrap remains the sole import owner. Before any
  coordinate edit or refresh, a standalone parent captures exact selected
  family/root/sync evidence. A calling bump workflow instead supplies the exact
  evidence it verified before its first coordinate mutation. The routes use
  that recorded evidence and never re-enter spool status after mutation or
  refresh. A fully applied refresh uses active `sync.root` values.

  The only pending shape this workflow accepts is top-level `:status :partial`
  with a nonempty module outcome map. Every top-level `:modules` map key equals its outcome
  `:module/key`; reject any missing or mismatched map-key identity. Every
  unchanged module must have `:status :unchanged` and no `:error`, `:reason`,
  refusal, `:root/outcome`, `:dependency`, or `:dependency/outcome`. Every other
  module outcome is exactly one of a direct refused hard-conflict with the exact
  `:root/outcome` and shared changed-root/residual classification, or a
  failed/skipped missing-dependency wrapper whose
  `:dependency` equals the nested `:dependency/outcome` `:module/key` at every
  hop. Wrapper chains are finite and acyclic and terminate in a direct refused
  hard-conflict whose `:conflict` exactly matches that shared classification.
  Every direct refused hard-conflict terminal, including one reached through a
  missing-dependency chain, carries `:root-lib` equal to exactly one `:lib` in
  the declared nonempty changed-root set; reject any terminal whose `:root-lib`
  is outside or mismatched against that set. No applied outcome, other status
  or reason, missing or mismatched dependency, cycle, unrelated terminal, or
  other refusal/error is allowed. Each
  changed-root entry has exactly `:lib`, `:previous-root`, and `:new-root`.
  Every previous root equals its unique pre-refresh current-root evidence, and
  the changed-root set equals the prepared-root set. Residuals map one to one
  to those entries, have nonempty providers, and use only `:root-repointed` or
  `:unledgered-loaded-namespace`; root-repointed has exactly one old binding.
  Binding and provider entries use `:root-lib`, equal the matched changed-root
  `:lib`, and reconcile every namespace, old/new root, and nonempty distinct
  provider `:file` path. Unledgered residuals have no binding. Empty, vacuous,
  duplicate, missing, extra, unrelated, or mixed mappings fail loudly, as do
  wrong root-lib, root, namespace, or provider paths. The runtime status also
  contains the matching pending-generation record, whose `:diff` exactly
  equals the conflict classification including both `:changed-roots` and
  `:namespace-residuals`. It records current and
  prepared generations, validates each prepared root's coordinate, cache
  provenance, `deps.edn`, and exports, and uses prepared roots for tooling
  without claiming Weaver adoption. Other partial or error results fail
  loudly. With no coordinate change, accept only the recorded full
  `(runtime/refresh! (current/runtime))` result with top-level refresh `:status
  :unchanged`, every module in `:modules` with the exact unchanged shape above,
  and runtime `:pending-generation nil`. The selected activation and relevant
  producer metadata define one exact intended family/root set before refresh;
  the recorded status `:families` and every `:roots` map cover exactly that
  set, with no missing, extra, or mismatched family/root. Every root outcome must be
  `:status :synced` with a `:sync` map whose nonempty `:root` (`:sync.root`)
  equals the recorded active-root evidence for that family/root. Failed,
  conflicted, source-reload, partial, missing, extra, or mismatched roots, and
  absent, blank, or otherwise invalid `:sync.root`, fail loudly. Applied,
  partial, refused, error, other module statuses, non-nil pending generation, or
  malformed and contradictory evidence fail loudly. Before
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
  retained even under the worktree. The agent-owned import derives every
  installed root's classpath from the recorded pre-refresh `sync.root` evidence
  and `deps.edn` `:paths`, defaulting absent `:paths` to the `src`
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
   :defaults {:inherited-pre-refresh-evidence false}
   :example {:worktree "/abs/path/to/consumer-worktree"
             :workspace "/abs/path/to/consumer-worktree/.millstrand"
             :inherited-pre-refresh-evidence false}
   :param-docs {:worktree "Exact consumer worktree to inspect and update."
                :workspace "Exact Millstrand workspace selected for the consumer."
                :inherited-pre-refresh-evidence
                (fmt/reflow
                 "|True only for a calling bump workflow that already recorded
                  |complete exact pre-mutation status evidence in its run context.
                  |The bootstrap must reuse that evidence without another status
                  |command.")}}
  (workflow/workflow
   "Bootstrap Millstrand clj-kondo support"
   (workflow/step :select-world
                  "Confirm the selected consumer worktree and workspace"
                  :self
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.world"
                   "workflow/instruction" select-world-instruction})
   (workflow/step :capture-spool-status
                  "Capture exact installed roots before refresh"
                  :self
                  :depends-on [:select-world]
                  :attributes
                  {"workflow/action-ref" "millstrand-workflows.bootstrap-kondo.spool-status.capture"
                   "workflow/instruction" capture-spool-status-instruction})
   (workflow/checkpoint :adoption-mode
                        "Choose the consumer's Kondo adoption mode"
                        :kind :agent
                        :depends-on [:capture-spool-status]
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
