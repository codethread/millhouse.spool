# Millhouse Kanban spool

`millhouse.spools.kanban` publishes a user-facing work board over Millstrand
strands. A feature card is the durable work root for user↔agent work; execution
strands, tasks, notes, and review work hang beneath it without becoming a
second status system.

## 1. Activation

Add this root to the workspace's `deps.edn`, then activate it from trusted startup configuration:

```clojure
{:deps
 {millhouse.spools/kanban
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/tag "v4"
   :deps/root "spools/kanban"}}}
```

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/module! runtime :millhouse/identity
  {:ns 'millhouse.spools.identity
   :required? true})
(runtime/module! runtime :millhouse/kanban
  {:ns 'millhouse.spools.kanban
   :required? true})
```

Activation publishes the `kanban` command tree, the read-only
`kanban-export` operation, the `kanban-dash` binary, and the Kanban queries.

## 2. Board model

Each card is a strand marked `kanban/card=true`. A feature is ordinary user
work. An epic groups direct feature children with `parent-of`; it is never
claimed or served by `next`.

The active lanes are:

- `refinement` — an idea that waits for explicit promotion to `pending`;
- `pending` — actionable work, ordered p1 first and oldest first within a priority;
- `claimed` — in progress: an agent is working, including implementation, testing, agent-to-agent review, resolving findings, and authorized landing; the card records its owner and branch;
- `in_review` — human attention is needed: human review, approval, blocker resolution, or a pending human decision. Record the exact request on the feature or epic; return to `claimed` when agent work resumes.
- `in_production` — optional post-merge deployment validation, settling, or coordinated release work; update into it from `claimed` or `in_review`, use `finish` to close it, or update back to `claimed` for rework. Reviewed work merged to main may still finish directly when its outcome is satisfied. Agents choose this lane only when follow-up work remains; no guard requires it.

Lanes show attention status, not sequential progress. `in_review` is not further
along than `claimed`: a human decision or blocker can need attention at any point.
Agent review stays in `claimed`; it does not require a visit to `in_review`.
Agent-resolvable blockers likewise stay in progress; use `depends-on` and notes
to expose them without implying a human needs to act.

Simple lane changes use `strand update CARD_ID --attr kanban/lane=LANE`,
with `pending` for promotion, `in_review` for human attention, `claimed` for agent work,
and `in_production` for optional observation. These are direct attribute patches,
not guarded transitions; inspect the current card and follow this lane discipline.
Use `claim`, `finish`, and `reopen` for their structured lifecycle behavior.

Finishing removes the lane and closes the strand. Features record `done` by
default or an explicitly supplied outcome, and close their open tasks. Epics
finish from `refinement` or `pending` and close their open feature children and
tasks. Cascade-closed descendants record `kanban/outcome=unactioned` and
`kanban/closed-by=parent-cascade`, distinguishing them from manually completed
work. For `abandoned`, each feature's former lane is also recorded so `reopen`
can restore exactly the cards and tasks the cascade closed. A completed epic
cannot be reopened.

Priorities are `p1` (immediate blocker), `p2` (high value), `p3` (default), and
`p4` (someday). `next` serves pending features only; refinement cards require
human promotion first.

## 3. Authoring and handoff

`add` creates a feature in `pending` or `refinement`; `--type epic` creates a
grouping card and `--epic` attaches a feature beneath an existing epic. The
`kanban-batch` weave pattern creates a set of pending features atomically and
resolves dependencies by sibling key or durable strand id.

`add --by-identity ACTOR` records canonical creator attribution and defaults the
reporter to that actor. `--reported-by REPORTER` makes the durable reporter role
explicitly different. Both flags are optional, so anonymous unowned backlog
creation remains valid. Reporting never claims work.

Claim a feature before doing direct user work. `claim` requires `--owner ID`
and, for a feature, `--branch`; `ID` is the friendly identity in the owner role.
`--by-identity ACTOR` records a distinct acting identity, such as a dispatcher
claiming on behalf of a worker. `worktree` is optional. `run-id` is retained only
as nonauthoritative context on the claim record; run participation remains in
`serves`/`performed` relations.

Every claim or handoff atomically creates a closed `kanban/ownership-claim=true`
source record and a `claim --claims--> target` edge. Records carry
`kanban/owner`, immutable `kanban/claimed-at`, optional distinct
`identity/by-identity`, and optional branch/worktree/run context. The Kanban
pre-commit guard rejects updates, supersession, batch replacement, or burn of
an existing claim record. History sorts
by `(claimed-at, record-id)`; the final record is current even if its owner does
not resolve locally. Repeating the exact current-owner request returns
`result=unchanged` without a write. A changed owner is an explicit handoff, so
A→B→A creates three records without pending-lane toggles. A same-owner request
with changed context fails as ambiguous rather than silently mutating history.

Tasks are the optional `feature > task` tier. `task add` marks a child with
`kanban/task=true` and can add repeatable `depends-on` edges. `claim TASK
--owner ID` creates a direct task claim (no branch required). Without one, task
ownership projects from the feature as `owner-source=inherited`; a direct claim
projects `owner-source=direct` and overrides inheritance. Status is `closed`,
`blocked`, `doing`, or `ready`; only direct task ownership produces `doing`, so
claiming a feature does not make all ready tasks active at once. Complete each
task as you go with `strand update TASK_ID --state closed`. Finish cascades mark
remaining open tasks as `unactioned`, not completed work.

Notes accept `--by-identity ID` and persist canonical
`identity/by-identity` attribution on each durable note record. The old `--by`
spelling is not an alias. Note/review authorship never changes ownership.

Notes use the shared `notes` relation and target only a card or task. Important
user-visible notes must always be on the epic or feature: decisions, milestones,
blockers, review outcomes, and handovers must not be buried only in task notes.
Task notes are a development log users will rarely see; use them for implementation
details, command output, detailed review findings, and resume context. `card` and
`board` expose each task's newest note as `latest-note`, so a cold agent can resume
from the doing-task without a conversation transcript. `note/kind` is an open view hint; suggested values
are `activity`, `decision`, `review-dump`, and `summary`.

Card-to-card blockers use core `depends-on` edges. The `related` projection on
`card` shows both directions, while `strand branches` shows cards and
substrands stamped for the current branch.

## 4. Command and viewing surfaces

The CLI is JSON-only. Use Millstrand's canonical discovery tiers:
`strand help kanban`, `strand about kanban`, and `strand prime kanban`.
`about` and `prime` are op metadata consumed by the built-in meta-operations,
not Kanban subcommands.

The declared command tree is available through `strand help kanban`; its main
flow is:

```text
add · board · card · next · priority · label · claim · note · task · finish · reopen
```

Use `board` for the grouped lanes, epics, closed count, and cross-card
`needs-review` frontier. Use `card <id>` for the resume view: tasks, notes,
active work, ready work, and related cards. Repeated `--label` flags intersect;
`label list` discovers labels already used on active cards.

Kanban does not rename generic graph operations. Use Batteries `add`, `update`,
`note`, and `show` for ordinary execution strands; apply `kanban-batch` through
`weave`; discover the registered Kanban queries through `query`; and consume
them through `list` or `ready`. The `kanban` verbs are the board-specific
projections and structured card operations layered on those primitives; simple
lane changes and task completion use `strand update` directly.

The REPL-only `print-board!` and pure `board-str` render a human ASCII board.
The `kanban-dash` binary provides a polling terminal dashboard with optional
closed-card and saved-label views.

`kanban-export <card-id>` is a read-only graph projection for offline use. It
returns the card's complete `parent-of` subtree, including closed strands, and
the internal `parent-of` and `depends-on` edges. The Bun consumer under
`scripts/kanban-export` turns that payload into a self-contained HTML progress
view.

## 5. Ownership projection contract

Identity reconciliation uses these explicit directions:

```text
reporter identity --reported--> card
owner identity --claimed--> ownership claim --claims--> feature or task
actor identity --attributed--> card or claim (when identity/by-identity exists)
```

Board and `next` compact cards expose the latest claim as top-level `owner`,
`claim-id`, `claimed-at`, and optional `branch`/`worktree`; `reporter` is
`{"identity": STRING, "identity-strand-ids": [ID...]}`. Those fields are
projections from source records and enrichment edges, never the legacy scalar
`owner` attribute.

`card ID` additionally returns:

```json
{
  "reporter": {"identity": "reporter", "identity-strand-ids": []},
  "ownership": {
    "current": {"id": "claim2", "owner": "worker-b", "claimed-at": "...", "order": 2,
                "owner-identity-strand-ids": []},
    "history": [{"id": "claim1", "owner": "worker-a", "claimed-at": "...", "order": 1,
                 "owner-identity-strand-ids": []}]
  }
}
```

Optional claim keys are `by-identity`, `branch`, `worktree`, and `run-id`.
Empty identity-strand IDs mean unresolved or ambiguous enrichment; the raw owner
and latest ordering do not change. Use `strand identity attributions CLAIM_ID`
when a consumer needs the exact `resolved|unresolved|ambiguous` diagnostic.
Anonymous cards return `reporter: null`; never-claimed targets return
`current: null, history: []`.

Task projections add `owner`, `owner-source` (`direct` or `inherited`), and
`ownership: {source, claim, feature?}`. `identity-work` returns
`{identity, cards, tasks, claims}`. The named query is intentionally the
one-edge direct selection; use the helper when containing epics and inherited
tasks are required.

## 6. Millstrand state and APIs

| Surface | Identity | Consumer contract |
| --- | --- | --- |
| CLI operation | `kanban` from `defop` | Publishes the JSON command tree for cards, tasks, notes, review, and lifecycle. |
| Offline operation | `kanban-export` from `defop` | Projects one card's complete `parent-of` subtree and internal dependency edges. |
| Dashboard binary | `kanban-dash` from `defbin` | Opens the interactive terminal board; it is separate from the JSON CLI. |
| Card query | `kanban-cards` from `defquery` | Selects every strand marked `kanban/card=true`. |
| Pending query | `kanban-pending` from `defquery` | Selects active cards in the `pending` lane. |
| Epic query | `kanban-epic-pending` from `defquery` | Selects an epic's direct pending cards for composition with `strand ready`. |
| Identity work query | `kanban-identity-work` from `defquery` | Selects direct feature/task claim targets and reported cards from durable raw identity evidence; it never reads scalar `owner`. |
| Identity work helper | `identity-work` | Expands direct history to inherited tasks and containing epics and returns exact `identity`, `cards`, `tasks`, and `claims` keys across all states. |
| Ownership helpers | `ownership-history`, `current-ownership`, `task-ownership` | Return ordered claim maps (`id`, `owner`, `claimed-at`, `order`, `owner-identity-strand-ids`, optional actor/context) and direct/inherited task ownership. |
| Card state | `kanban/*` attributes | Stores card type, lane, outcome, priority, source, task markers, reporter, and abandon restore state. Claim records own ownership/run context. |
| Label state | `kanban.label/<slug>` attributes | Stores one independent `"true"` marker per normalized free-form label. |
| Lifecycle resource | `kanban-runtime` | Declares the Kanban vocabularies and owns process-lifetime runtime state. |
