# Millhouse Kanban cookbook

## Optional post-merge observation

Reviewed work merged to main can finish directly when its declared outcome is met.
When deployment validation, a settling period, or a coordinated release (such as
several features in an epic) remains, agents may instead use:

```sh
strand update CARD_ID --attr kanban/lane=in_production
strand kanban note CARD_ID "Observe deployment; finish after the release checks pass."
strand kanban note TASK_ID "Deployment observations and detailed check output go here."
strand kanban finish CARD_ID
```

The update moves an `in_review` card to `in_production`. It is optional agent
policy, not a required completion guard. Record the remaining work and completion
criterion on the feature or epic where users will see it. Keep detailed observations
in the task devlog and close each completed task with `strand update TASK_ID --state closed`.
If implementation changes are needed, use `strand update CARD_ID --attr kanban/lane=claimed`.
Finishing an epic with `done` cascade-closes open feature children and tasks as
`unactioned`; it does not mark their work as completed.

Compositions for running user↔agent work through the Kanban board. The
[contract](./README.md) defines lanes, attributes, and consumer-visible
surfaces; the generated [API](./kanban.api.md) defines signatures and focused
calls. These recipes combine those surfaces into repeatable operating patterns.

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

strand update "$impl" --state closed
strand update "$docs" --attr owner=claude
# Once documentation is complete:
strand update "$docs" --state closed

strand update "$card" --attr kanban/lane=in_review
# If review requests changes:
strand kanban note "$card" "Review found changes needed before landing." --kind summary
strand update "$card" --attr kanban/lane=claimed
# After addressing the findings:
strand update "$card" --attr kanban/lane=in_review

strand kanban note "$card" "Handover: implementation reviewed and ready to land." \
  --by claude --kind summary
strand kanban finish "$card" --outcome done
```

**Why this shape.** The claim makes the work discoverable by branch and keeps
two agents from selecting the same pending feature. Tasks make a resumable
doing-task and reuse the same dependency DAG that determines readiness. Closing
tasks as they complete unblocks dependent work immediately. Task notes are the
devlog for details and bulk findings; `board` and `card` surface the newest note
for a cold-start handoff. Important user-visible notes always belong on the epic
or feature, not only on tasks users will rarely see. Review is a visible lane
update, and the final card note records the
handoff after the branch is ready rather than pretending that a closed card is
self-explanatory.

Simple lane changes are direct `strand update` patches, not guarded Kanban
transitions. Inspect the current card and follow the lane discipline. Promote a
refinement idea explicitly with `strand update CARD_ID --attr kanban/lane=pending`.
Keep `claim`, `finish`, and `reopen` for their structured behavior.

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

**Composition.** The identity query is state-neutral. Use it with `list` for
complete history, or with `ready` for active epic context, owned features, and
directly or indirectly owned tasks that are not blocked by an active dependency.
Then open the feature's resume view and use the cross-card review frontier for
coordination. Mark review strands with an open review signal such as
`kind=review`.

```sh
strand list --query kanban-identity-work \
  --param identity="$MILLSTRAND_AGENT_ID"
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
