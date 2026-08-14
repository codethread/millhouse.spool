# Millhouse Kanban spool

`millhouse.spools.kanban` publishes a user-facing work board over Millstrand
strands. A feature card is the durable work root for user↔agent work; execution
strands, tasks, notes, and review work hang beneath it without becoming a
second status system.

## 1. Activation

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/module! runtime :millhouse/kanban
  {:ns 'millhouse.spools.kanban
   :spools ['millhouse.spools/kanban]
   :required? true})
```

Activation publishes the `kanban` command tree, the read-only
`kanban-export` operation, the `kanban-dash` binary, and the Kanban queries.
It does not enable board peering; peering is a separate module described below.

## 2. Board model

Each card is a strand marked `kanban/card=true`. A feature is ordinary user
work. An epic groups direct feature children with `parent-of`; it is never
claimed or served by `next`.

The active lanes are:

- `refinement` — an idea that waits for an explicit `promote` act;
- `pending` — actionable work, ordered p1 first and oldest first within a priority;
- `claimed` — work has started and the card records its owner and branch;
- `in_review` — work is waiting for review; `rework` returns it to `claimed`.

Finishing removes the lane and closes the strand. Features record `done` by
default or an explicitly supplied outcome. Epics finish from `refinement` or
`pending`: `done` requires every direct feature child to be closed, while
`abandoned` closes still-open children and records each former lane so
`reopen` can restore exactly the cards that cascade closed. A completed epic
cannot be reopened.

Priorities are `p1` (immediate blocker), `p2` (high value), `p3` (default), and
`p4` (someday). `next` serves pending features only; refinement cards require
human promotion first.

## 3. Authoring and handoff

`add` creates a feature in `pending` or `refinement`; `--type epic` creates a
grouping card and `--epic` attaches a feature beneath an existing epic. The
`kanban-batch` weave pattern creates a set of pending features atomically and
resolves dependencies by sibling key or durable strand id.

Claim a feature before doing direct user work. `claim` requires `owner` and
`branch`; `worktree` is optional for work in the main checkout, and `run-id`
can carry an opaque workflow pointer. The card is the branch's discoverable
work root; execution strands beneath it use `parent-of` edges.

Tasks are the optional `feature > task` tier. `task add` marks a child with
`kanban/task=true` and can add repeatable `depends-on` edges. Task status is
derived from strand state, dependency closure, and the core `owner` attribute:
`closed`, `blocked`, `doing`, or `ready`. The first `doing` task is the board's
resume signal; status is never stored and therefore cannot drift.

Notes use the shared `notes` relation and target only a card or task. Put
progress, decisions, and review dumps on the doing-task; keep the card's notes
as short handover summaries. `card` and `board` expose each task's newest note
as `latest-note`, so a cold agent can resume from the doing-task without a
conversation transcript. `note/kind` is an open view hint; suggested values
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
add · board · card · next · priority · label · promote · claim · note · task
review · rework · finish · reopen
```

Use `board` for the grouped lanes, epics, closed count, and cross-card
`needs-review` frontier. Use `card <id>` for the resume view: tasks, notes,
active work, ready work, and related cards. Repeated `--label` flags intersect;
`label list` discovers labels already used on active cards.

Kanban does not rename generic graph operations. Use Batteries `add`, `update`,
`note`, and `show` for ordinary execution strands; apply `kanban-batch` through
`weave`; discover the registered Kanban queries through `query`; and consume
them through `list` or `ready`. The `kanban` verbs are the board-specific
projections and guarded card transitions layered on those primitives.

The REPL-only `print-board!` and pure `board-str` render a human ASCII board.
The `kanban-dash` binary provides a polling terminal dashboard with optional
closed-card and saved-label views.

`kanban-export <card-id>` is a read-only graph projection for offline use. It
returns the card's complete `parent-of` subtree, including closed strands, and
the internal `parent-of` and `depends-on` edges. The Bun consumer under
`scripts/kanban-export` turns that payload into a self-contained HTML progress
view.

## 5. Optional board peering

Peering is opt-in and requires Guild plus the base Kanban module. Activate the
modules in this order:

```clojure
(runtime/module! runtime :guild
  {:ns 'millstrand.spools.guild
   :spools ['millstrand.spools/guild]
   :required? true})
(runtime/module! runtime :millhouse/kanban
  {:ns 'millhouse.spools.kanban
   :spools ['millhouse.spools/kanban]
   :required? true})
(runtime/module! runtime :millhouse/kanban-peering
  {:ns 'millhouse.spools.kanban.peering
   :spools ['millhouse.spools/kanban 'millstrand.spools/guild]
   :after [:guild :millhouse/kanban]
   :required? true})
```

The consuming weaver must have a published name. `kanban-peers` discovers
sibling weavers and reports which advertise `kanban.send.v1`; `kanban-send`
sends a pending or refinement feature, or an epic with its pending/refinement
feature children.

Only board-tier data travels: title, body, source, priority, queued lane, and
optional `:from` provenance. Claims, worktrees, tasks, notes, execution
strands, labels, closed cards, and in-flight epic children remain local. A
received card is a new local card with a local id and defaults; its provenance
is stored as `kanban/from`. Sending never changes the source lane.

The Guild receiver accepts exactly one JSON object in one of these shapes:

```clojure
{:card {:title "…" :body "…" :source "…"
        :priority "p1|p2|p3|p4" :lane "pending|refinement"}
 :from {:board "backend" :card "abc12"}}

{:epic {:title "…"}
 :features [{:title "…"}]
 :from {:board "backend" :card "abc12"}}
```

Unknown keys, malformed cards, an incomplete epic bundle, and non-queued source
cards fail loudly. The receiver returns only the ids it created.

## 6. Millstrand state and APIs

| Surface | Identity | Consumer contract |
| --- | --- | --- |
| CLI operation | `kanban` from `defop` | Publishes the JSON command tree for cards, tasks, notes, review, and lifecycle. |
| Offline operation | `kanban-export` from `defop` | Projects one card's complete `parent-of` subtree and internal dependency edges. |
| Dashboard binary | `kanban-dash` from `defbin` | Opens the interactive terminal board; it is separate from the JSON CLI. |
| Card query | `kanban-cards` from `defquery` | Selects every strand marked `kanban/card=true`. |
| Pending query | `kanban-pending` from `defquery` | Selects active cards in the `pending` lane. |
| Epic query | `kanban-epic-pending` from `defquery` | Selects an epic's direct pending cards for composition with `strand ready`. |
| Card state | `kanban/*` attributes | Stores card type, lane, outcome, priority, source, task/run markers, provenance, and abandon restore state. |
| Label state | `kanban.label/<slug>` attributes | Stores one independent `"true"` marker per normalized free-form label. |
| Lifecycle resource | `kanban-runtime` | Declares the Kanban vocabularies and owns process-lifetime runtime state. |
| Peering operations | `kanban-peers`, `kanban-send` | Discover compatible sibling boards and send queued board-tier work. |
| Guild receiver | `kanban.send.v1` | Receives a card or epic bundle through the opt-in peering module. |
