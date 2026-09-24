# Mechanical consumer migration (x3xkl)

This is a **clean break**, not an as-is pin bump. The accepted extraction baseline
is Millhouse `8a66ab98030460ec7c41f35c418971a17a4cec71`; work.spool consumed it in
`3a4a3691c5fa183109fb479429dd3bb7154a72b7`. Do not deploy the new shared pin with
that old consumer module. The exact accepted new SHA/PR and quality evidence are
recorded on x3xkl by its independent landing finisher. Use that **accepted SHA**,
not a moving branch or the baseline migration's pin. hfogs owns work.spool edits;
fkat9 owns the final Deals/workfiles bump. This feature changes neither consumer.

## 1. Drain the old generation before switching

Disable old recurring polling using its current configuration, then assess and
finish all old `mr-review/*` records and owned workspaces **using the old code**.
Inventory partial setup, failed teardown, pending curation/publication and active
runs. Resolve them explicitly; do not turn an ambiguous remote publication into
a fresh request. Preserve historical strands and logs as evidence.

No old review record becomes an Auto-run card. New polling deduplicates only
`auto-review/key` receipts. Consequently a still-open remote head reviewed under
the old coordinator can be reviewed once again after the clean break; confirm
this deliberate new-generation admission policy before enabling. If that is not
wanted, keep polling disabled until the old remote requests leave scope. Do not
invent tombstones, migrate partially published comments, or clear old receipts.

## 2. Replace dependency pins together

The standalone library remains:

```clojure
millhouse.spools/auto-review
{:git/url "https://github.com/codethread/millhouse.spool.git"
 :git/sha "ACCEPTED_X3XKL_SHA"
 :deps/root "spools/auto-review"}
```

In the consolidated distribution, this root requires Workflow through a relative
local root at the same Millhouse revision. It does not transitively start Auto-run,
Cron or Harnesses. For the complete recipe, generate the selected production
closure from the checkout of the accepted revision:

```text
scripts/consumer-deps.sh ACCEPTED_SHA millhouse.spools/auto-review millhouse.spools/auto-run millhouse.spools/cron
```

Use the printed direct `:deps` map. It includes the declared shared dependencies
at that same SHA, avoiding conflicts between tools.deps' per-library Git cache
paths. Include any other selected Millhouse roots in the generator arguments.
Inspect the elected top-level basis; see [consumption](../../README.md#consumption).

Millstrand remains an external dependency pinned by the package manifests.
Auto-run uses the consolidated Harnesses package, including its publication-lock
API. There is no separate internal Harnesses release pin to coordinate.
Millstrand itself is supplied by Mill in a Weaver basis: do **not** add a reserved
`io.millstrand/millstrand` direct dependency to workspace deps. Ordinary standalone
tools.deps projects resolve it transitively from the spool.

For Deals, preserve existing `me/config` local/root semantics and inspect owning
symlinks first. The tracked workfiles `deps.ref.edn` and real active `deps.edn` need
the same intended bytes. A source copy alone does not prove selected basis or live
activation. Dependency changes require a **separately authorized new Weaver
generation**. This migration does not authorize restart; never stop Mill.

## 3. Remove the old surface

Delete local `me.review` coordinator/adapters/API shims, old review lifecycle
resource and setup/teardown shell variables. Remove imports and activation of the
old shared coordinator and its `internal.board/comments/io/logs/publication/views`
namespaces. Delete obsolete `scripts/test-review`, `smoke-review.py`, comment JSON,
curation/version/CAS and publication test scaffolding rather than retaining aliases.

Removed public functions: `validate-config`, `dispatch!`, `settle!`,
`passing-revisions`, `poll-once!`, `request!`, `wake!`, `prune-wake!`, `open!`,
`close!`, `on-agent-completion`, `review`, `review-logs`.
Removed commands: all `review list/show/comments/curate/publish/finish/link/poll/
reconcile/status` subcommands and `review-logs`. No new Reviews-tab projection is
provided: ordinary Kanban cards, workflow gates and agent evidence replace it.

Old configuration → new owner:

| Old field | Mechanical replacement |
| --- | --- |
| `repo-dir` | `poll! :repo` and the sole Auto-run `:repo` |
| `poll?`, `interval-seconds` | selected Cron job, `interval-ms` |
| `max-active-reviews` | `poll! :max-open`; Auto-run `max-running` is separate |
| `labels`, `glab-bin` | provider-config `:labels`, `:bin`; add explicit `:host` and numeric `:project` |
| `reviewers` roster | workflow `:reviewer` **Harness alias**, not a roster-name lookup |
| `setup`, setup timeout | Auto-run `:prepare` callback; optional shared `workspace/prepare!` has bounded commands |
| `teardown`, teardown timeout | explicit workflow cleanup after local decision and process settlement |
| log retention | existing Cron/Harnesses/workflow evidence; no custom review log store |

The supplied workflow deliberately uses **one** reviewer seat. Select the
consumer's existing review harness alias (`reviewer` in the example), retaining
its seat/model policy. Old named reviewer-roster entries are not aliases and do
not need to be recreated. If a consumer requires multiple remits, declare multiple
ordinary dependent/parallel `:agent` gates using the same frozen `:review` and
worktree, join before the report/decision, and keep the driver active until all
settle. That is consumer workflow policy, not another shared dispatcher or an
implicit compatibility interpretation of the old roster.

## 4. Copy and activate the complete recipe

Copy [examples/review.clj](examples/review.clj) to the consumer module tree. Keep
its declared namespace `consumer.review` or rename **every** qualified callback
symbol along with it. Fill in canonical repository, host/project/labels, driver
and reviewer harness aliases. Start with `enabled? false`.

Retain the existing consumer `config/register!` and `config/register-executor!`
activation for its configured Harnesses seats. Ensure the Workflow engine,
Kanban/identity, Harnesses core/assignment, code executor, Cron, and the ordinary
agent executor are selected. Example ordering in the existing init:

```clojure
;; Existing consumer registration remains responsible for seats and base spools.
(config/register! runtime)
(runtime/module! runtime :review/cron
  {:ns 'millhouse.spools.cron :required? true})
;; If code execution is not already selected, activate this consumer-owned file:
(runtime/module! runtime :review/code
  {:file "review-code.clj" :after [:work/workflow] :required? true})
(runtime/module! runtime :review/admission
  {:file "review.clj" :after [:work/config :work/workflow :review/cron :review/code]
   :required? true})
;; The existing sole agent executor is registered after the consumer workflows.
(config/register-executor! runtime)
```

Use the actual existing engine/config module keys instead of `:work/workflow` and
`:work/config`. Do not register an executor twice. `review-code.clj`, when needed:

```clojure
(ns consumer.review-code
  (:require [millhouse.spools.executors.code :as code]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))
(workflow/use-executor! code/code-stalled?)
(millstrand/use-query! code/stalled-code-gates)
(lifecycle/use-resource! code/code-engine)
```

If the consumer already has an Auto-run resource, **merge** this recipe into that
resource instead of configuring a second one: extend allowed workflows with
`review-request`, route `:prepare` and `:start-params` by the presence of
`auto-review/key`, and leave ordinary feature callbacks unchanged. Preserve one
repository execution capacity. Select the review workflow and Cron job in the new
module without its duplicate `review-admission` resource or Auto-run operation.
Example callback routing:

```clojure
(defn prepare! [rt {:keys [card] :as params}]
  (if (attr-get card :auto-review/key)
    (workspace/prepare! rt params)
    (existing/prepare! rt params)))
(defn start-params [rt {:keys [card] :as params}]
  (if (attr-get card :auto-review/key)
    (assoc (review/start-params rt params) :reviewer "reviewer")
    (existing/start-params rt params)))
```

Use `attr-get` from `millstrand.api.spool.alpha`. `existing/*` means the actual
consumer callbacks, not new fallback shims. The Auto-run start-params result
cannot override card, feature, worktree, branch, seat or effort. No setup callback
may claim the card. The assigned driver owns claiming and following the exact
poured workflow.

## 5. Verify before authorized enablement

1. In a standalone temporary tools.deps project, select only the new Auto-review
   root and require its core, glab, workspace and workflow namespaces. They must
   resolve without the Millhouse checkout's root classpath.
2. In a disposable Weaver, activate the actual copied module with fake/nonexecuting
   seats and a fake provider returning the documented normalized vector. Verify
   one deduplicated pending card, frozen overrides, requested priority, exact-head
   CI rejection, and Auto-run capacity/workflow context. Exercise the agent adapter
   with fake settled evidence; never publish externally or invoke paid seats.
3. Run native repository quality under the shared lock and mandatory review/land.
   Record native accepted commit, exact upstream pin, basis and owned cleanup on
   hfogs, then pass them to fkat9. Do not close cross-repo cards before native land.
4. Leave production polling disabled and activation/restart explicitly outstanding
   unless separately authorized. When authorized, inspect the new generation's
   `workflow show review-request`, Auto-run status and selected Cron jobs before
   enabling. A pin update, disposable activation and live rollout are distinct facts.

Remote publication remains intentionally unavailable. Human local assessment is
not permission for comments, approval or merge. Add any such action only under a
new explicit authorization and separately reviewed provider-specific boundary.
