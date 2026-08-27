# Millhouse Kanban cookbook

Compositions for running user↔agent work through the Kanban board. The
[contract](./README.md) defines lanes, attributes, and consumer-visible
surfaces; the generated [API](./kanban.api.md) and [peering API](./kanban.peering.api.md)
define signatures and focused calls. These recipes combine those surfaces into
repeatable operating patterns.

## 1. Carry one card from queue to handoff

**Situation.** A user request is ready to work, and the next agent must leave a
clear owner, branch, resume point, and review trail.

**Composition.** Select with `next`, claim the card, split work into dependent
tasks, note the doing-task as work progresses, then expose review and finish.

```sh
card=$(strand kanban next | jq -r '.next.id')
strand kanban claim "$card" --owner claude --branch kanban-spool

impl=$(strand kanban task add "$card" "Implement the change" | jq -r '.task.id')
docs=$(strand kanban task add "$card" "Document the change" --depends-on "$impl" \
  | jq -r '.task.id')
strand update "$impl" --attr owner=claude

strand kanban note "$impl" "Implementation started; tests are next." \
  --by claude --kind activity
strand --stdin kanban note "$impl" :stdin --by claude --kind review-dump <<'NOTE'
Validation:
- clojure -M:test
Next: hand the branch to review.
NOTE

strand kanban review "$card"
# If review requests changes:
strand kanban rework "$card"
strand kanban review "$card"

strand kanban note "$card" "Handover: implementation reviewed and ready to land." \
  --by claude --kind summary
strand kanban finish "$card" --outcome done
```

**Why this shape.** The claim makes the work discoverable by branch and keeps
two agents from selecting the same pending feature. Tasks make a resumable
doing-task and reuse the same dependency DAG that determines readiness. Notes
on the task retain progress and bulk findings without turning the card into a
log; `board` and `card` surface the newest note for a cold-start handoff.
Review is a visible lane transition, and the final card note records the
handoff after the branch is ready rather than pretending that a closed card is
self-explanatory.

## 2. Build a dependent backlog atomically

**Situation.** A release has several features, with design blocking
implementation and documentation. The backlog should be created as one graph
and then served in priority order.

**Composition.** Use the `kanban-batch` pattern when the items and their
dependencies are known together. Add an epic separately when the board needs a
durable grouping and an epic-scoped frontier.

```sh
strand weave --pattern kanban-batch --input "$(jq -n '{
  items: [
    {key: "design", title: "Design the board", priority: "p2"},
    {key: "implementation", title: "Implement the board", depends-on: ["design"]},
    {key: "docs", title: "Document the board", depends-on: ["implementation"]}
  ]
}')"

strand kanban next
```

**Why this shape.** The batch pattern validates keys and dependencies before
publishing the cards, so a typo cannot leave a half-built backlog. Sibling keys
are resolved inside the weave and other dependency values are durable strand
ids. If the same work needs an initiative lens, create an epic and attach
features with `--epic`; its children keep independent priority, claims,
branches, and review paths, while `next --epic` and the parameterized query
serve only that epic's direct pending features.

## 3. Resume work and collect review across cards

**Situation.** A new agent has no conversation context, while several branches
may have review work ready at once.

**Composition.** Start with the identity-scoped ready query. It returns the
active epic context, owned feature, and owned tasks that are not blocked by an
active dependency. Then open the feature's resume view and use the cross-card
review frontier for coordination. Mark review strands with an open review
signal such as `kind=review`.

```sh
strand ready --query kanban-identity-work \
  --param identity="$MILLSTRAND_AGENT_ID"
strand kanban board | jq '{claimed, in_review, needs_review: .["needs-review"]}'
strand kanban card "$card" | jq '{card, tasks, notes, active_work: .["active-work"], ready, related}'

review=$(strand add "Review the implementation" --attr kind=review | jq -r '.id')
strand update "$card" --edge parent-of:"$review"
strand update "$review" --edge depends-on:"$impl"

# This remains out of needs-review until implementation is closed and the
# review strand is in the engine's ready frontier.
strand kanban board | jq '.["needs-review"]'
```

**Why this shape.** The doing-task is the durable resume point: its body,
dependencies, derived status, and `latest-note` tell the next agent what is
active and what to do next. The card view also exposes blockers in both
directions through `related`. The review queue is computed from the graph on
every board read, so it cannot drift from actual readiness or require a second
index maintained by coordinators.

## 4. Transfer queued board work between sibling weavers

**Situation.** Two repositories have independent weavers, but a pending card
belongs on the other board. The receiving board should get the work shape while
execution history and claims remain local.

**Composition.** Approve Guild and Kanban in both workspaces, activate peering
after them, publish a distinct weaver name, discover the target, and send the
queued card.

```clojure
;; In each workspace's init.clj: guild, then kanban, then peering.
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

```json
{"configFormat": "alpha", "name": "backend"}
```

```sh
strand kanban-peers | jq '.peers[] | select(.name == "frontend")'
strand kanban-send frontend "$card"
```

**Why this shape.** The activation edge makes Guild's receiver seam and the
Kanban board op prerequisites explicit. Peering refuses claimed, in-review,
closed, or otherwise malformed work, so a transfer cannot silently split an
active execution history. The receiver calls the local card-creation path,
which gives the new card local identity and defaults; only queued board data
and provenance cross the boundary. A successful send notes the source card but
does not move or close it, leaving that lifecycle decision with the caller.
