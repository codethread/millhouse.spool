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

The update moves a `claimed` or `in_review` card to `in_production`. It is optional agent
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
tasks, note the doing-task as work progresses, and keep agent review in `claimed`.
Use `in_review` only when a human needs to act, then resume agent work and finish.

```sh
card=$(strand kanban next | jq -r '.next.id')
# Owner is the role; by-identity is an optional distinct actor.
strand kanban claim "$card" --owner "$MILLSTRAND_AGENT_ID" --branch kanban-spool

impl=$(strand kanban task add "$card" "Implement the change" | jq -r '.task.id')
docs=$(strand kanban task add "$card" "Document the change" --depends-on "$impl" \
  | jq -r '.task.id')
strand kanban claim "$impl" --owner "$MILLSTRAND_AGENT_ID"

strand kanban note "$impl" "Implementation started; tests are next." \
  --by-identity "$MILLSTRAND_AGENT_ID" --kind activity
strand --stdin kanban note "$impl" :stdin --by-identity "$MILLSTRAND_AGENT_ID" --kind review-dump <<'NOTE'
Validation:
- clojure -M:test
Next: get agent review while the card stays claimed.
NOTE

strand update "$impl" --state closed
strand kanban claim "$docs" --owner "$MILLSTRAND_AGENT_ID"
# Once documentation is complete:
strand update "$docs" --state closed

# Agent review and resolving agent findings stay in claimed.
# Only if human approval or a human decision is needed:
strand kanban note "$card" "Human decision needed: confirm the proposed behavior before landing." --kind decision
strand update "$card" --attr kanban/lane=in_review
# Once the human responds and agent work resumes:
strand update "$card" --attr kanban/lane=claimed

# After reviewed work is merged and the outcome is satisfied:
strand kanban note "$card" "Implementation reviewed and landed; outcome verified." \
  --by-identity "$MILLSTRAND_AGENT_ID" --kind summary
strand kanban finish "$card" --outcome done
```

**Why this shape.** The same friendly identity may fill different roles:
`--owner` claims responsibility while `--by-identity` records the actor on a
claim or note. A dispatcher can claim on behalf of a worker by supplying owner
and actor; note authorship never changes ownership. Each claim is a
durable source record, and a changed owner is an explicit handoff; no pending
lane toggle is needed. Same-owner retries must repeat the exact context. Tasks
make a resumable
doing-task and reuse the same dependency DAG that determines readiness. Closing
tasks as they complete unblocks dependent work immediately. Task notes are the
devlog for details and bulk findings; `board` and `card` surface the newest note
for a cold-start handoff. Important user-visible notes always belong on the epic
or feature, not only on tasks users will rarely see. Review is a human-attention
status, not a later progress stage: a blocker or pending human decision can need
`in_review` even before implementation. Agent review and active work on agent-resolvable blockers stay in `claimed`.
If the worker stops or hands off without an active successor, return the card to
`pending`; an old claim or failed gate does not make idle work In Progress.
The final card note records the outcome rather than pretending that a closed card
is self-explanatory.

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
serve only that epic's direct pending features. Ready (`pending`) may include
blocked cards: `kanban next` orders the pending queue, while
`strand ready --query kanban-pending` selects dependency-ready work. Do not confuse
a queue candidate with permission to bypass its prerequisites.

## 3. Keep waiting work out of In Progress

**Situation.** Implementation has stopped because another card must deliver first.
No human decision is needed, and the old owner/PR remains useful history.

**Composition.** Move the dependent card to Ready and record its prerequisite:

```sh
strand update DEPENDENT_ID --edge depends-on:PREREQUISITE_ID
strand update DEPENDENT_ID --attr kanban/lane=pending
strand kanban note DEPENDENT_ID "Waiting for prerequisite delivery; no active implementation."
```

If the prerequisite is on another board, do not add its ID to the local graph.
Record its confirmed `.millstrand` workspace path and ID. An explicitly authorized
waiter inspects the remote `query explain` and `help await`, then calls
`strand --workspace PATH await` against that exact target. Reissue bounded waits
while outstanding; do not busy-poll or treat a timeout as completion. Verify the
source card and declared outcome after a wake before any coordinated gate release.
An await-only agent does not perform that release or implementation work and does
not move the dependent card to `claimed`.

**Why this shape.** Ready means ready for pickup once dependencies clear, not
necessarily unblocked now. In Progress means actual work. Ownership history,
blocker evidence and local mirror gates survive the lane change. Use `in_review`
only if the user needs to act, with the exact request in a card note.

## 4. Resume work and collect review across cards

**Situation.** A new agent has no conversation context, while several branches
may need human attention at once.

**Composition.** The identity query is state-neutral and selects direct claim
targets plus reported cards from durable evidence. The Clojure `identity-work`
helper expands those targets to containing epics, inherited tasks, and all
matching claim records. Open the feature's resume view and use the cross-card
human-review frontier for coordination. Mark human-attention strands with an
open review signal such as `kind=review`, `hitl=true`, or a human checkpoint.
Do not stamp agent reviews with these human-attention signals.

```sh
strand list --query kanban-identity-work \
  --param identity="$MILLSTRAND_AGENT_ID"
strand ready --query kanban-identity-work \
  --param identity="$MILLSTRAND_AGENT_ID"
strand kanban board | jq '{claimed, in_review, needs_review: .["needs-review"]}'
strand kanban card "$card" | jq '{card, reporter, ownership, tasks, notes, active_work: .["active-work"], ready, related}'

review=$(strand add "Human approval of the implementation" --attr kind=review | jq -r '.id')
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
