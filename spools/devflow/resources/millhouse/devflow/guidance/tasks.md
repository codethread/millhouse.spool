# Devflow guide: tasks

A deterministic, feature-local AFK task queue authored as strands in the Millstrand
graph: tracer-bullet vertical slices an unattended agent can execute one at a
time, referencing the proposal/spec/RFC/plan instead of duplicating rationale.

## Artifacts

- Task strands: one strand per slice, carrying the vocabulary below
- Plan: `devflow/feat/<feature>/<feature>.plan.md`

Tasks are graph objects, not files. The queue's shape is the graph itself:
`strand list --query devflow-tasks` is the whole open queue, and
`strand ready --query devflow-tasks` is the runnable frontier.

## Prerequisites

- proposal.md exists and `<feature>.plan.md` is Reviewed; for small obvious work, create a minimal plan and mark it Reviewed after a lightweight sanity check.
- Relevant proposal, plan, feature specs, root specs, RFCs, and affected code/tests have been read; inspect the codebase when the implementation area is unclear.
- Tasks belong to exactly one feature; every task strand carries `devflow/feature`.

## Strand vocabulary

Each task is one strand:

- **Title** — short enough to use in a session name.
- **Body** — the execution contract in the standard `body` attribute, following the body template below.
- **`devflow/task-type`** — `afk` or `hitl`; this attribute's presence is what makes a strand a devflow task.
- **`devflow/feature`** — the feature name (the workflow run-id).
- **`hitl`** — `true` on HITL tasks, so the batteries convention (a ready strand carrying hitl=true means stop and ask the user) applies unchanged.
- **Dependencies** — `depends-on` edges to prerequisite task strands; never dependency prose in the body.
- **State** — active while open; close the strand when the slice is finished and committed.

Optional but recommended: one task-root strand per feature (title
`Tasks: <feature>`, attributes `devflow/tasks-root=true` and
`devflow/feature`) with `parent-of` edges to every task, so the feature's
whole queue is one subgraph away.

Creating a task:

```sh
printf '%s' "<body markdown>" | strand --stdin add "Terse task title" \
  --attr devflow/task-type=afk --attr devflow/feature=<feature> \
  --attr body=:stdin --edge depends-on:<prerequisite-strand-id>
```

## Slicing

- Each task delivers a narrow but complete path through the relevant integration layers, not a horizontal layer-only change.
- Each completed task is independently verifiable.
- Prefer many thin slices over a few broad slices, and AFK-ready slices where possible.
- Keep slices small enough for one agent run; prefer a workable MVP over comprehensive scope.
- Put human/architectural uncertainty into the plan's Task context or Developer Notes, not hidden in task scope.

## AFK vs HITL classification

- **AFK** — Safe for an unattended agent loop: clear contract, enough context, deterministic validation, no user decisions, credentials, design judgment, or external access needed.
- **HITL** — Requires human interaction first: an architectural decision, product/design choice, unclear acceptance criteria, secret/access setup, manual QA, or a meaningful tradeoff.

Rules:

- Prefer AFK; do not mark a slice HITL just because it is complex — split complex work into smaller AFK slices.
- AFK tasks are `devflow/task-type=afk` and become runnable when their `depends-on` prerequisites close; never model a dependency wait as HITL.
- HITL tasks are `devflow/task-type=hitl` plus `hitl=true`; the loop never picks them up — they wait for human input.
- Put `Type: AFK` or `Type: HITL` as the first line under the body's Scope heading.
- If HITL produces a decision that unlocks implementation, make the decision strand HITL and create separate AFK implementation strands depending on it via `depends-on`.

## Specificity

- Task bodies may be more specific than the plan: name exact files, functions, commands, fixtures, and assertions when unattended execution needs them.
- Keep rationale short; link to the RFC/spec/proposal/plan for why and high-level how.
- Translate plan phases into narrow implementation contracts; never copy phase prose into every task.
- Include only references the implementer must inspect or change.

## Procedures

### Create the queue

1. Gather context: read the proposal, Reviewed plan, feature specs, root specs, RFCs, and affected code.
2. Confirm the feature; tasks must belong to exactly one.
3. Draft tracer-bullet vertical slices per the slicing rules.
4. Classify every slice AFK or HITL per the classification rules.
5. Add one strand per slice from the body template, with the vocabulary attributes and `depends-on` edges; allocate TASK document IDs per the ID convention inside each body.
6. Record task context, important references, and strategy in the plan's Task context section.
7. Request review: `strand ready --query devflow-tasks` must serve exactly the intended entry slices; check the graph for ordering issues, cycles, and deadlocks, each body for standalone clarity, and queue/plan/spec cohesion against the MVP goal.

### Update the queue

1. Read the open queue (`strand list --query devflow-tasks`), the feature plan, and relevant bodies before editing.
2. Prefer adding follow-up task strands over rewriting existing ones; wire them behind their prerequisites with `depends-on` edges.
3. Narrow a too-broad task in place only when no agent has started it; for started tasks, keep the published contract intact and extract follow-up strands.
4. Never edit closed task strands.
5. Append amendment rationale to the plan's Developer Notes; never hide important plan changes in task bodies.

## Constraints

- Create or update the queue only; do not implement the tasks.
- Make dependency edges explicit and minimal; a false edge serializes work that could land in any order.
- No speculative future work unless needed to protect the MVP boundary.
- Tasks are not durable documentation: root specs own durable outcomes and the plan owns ongoing notes.
- Acceptance criteria belong in the body's Done when; follow-up ideas belong in the plan's Developer Notes.
- When the plan uses different section numbering, preserve its local numbering but keep the Task context and Developer Notes headings with document-prefixed sub IDs.
- {{id-editing}}

## Validation

- Every task strand carries `devflow/task-type` (afk or hitl) and `devflow/feature`
- HITL strands also carry `hitl=true`; AFK strands do not
- Every body has a stable TASK ID, a Type line under Scope, Must implement exactly, Done when, Out of scope, and References
- Dependencies are `depends-on` edges only and contain no cycles
- `strand ready --query devflow-tasks` serves exactly the intended entry slices
- Task context and strategy are recorded in the feature plan

## Templates

### Task strand body

{{template:task-strand.md}}

### Plan notes

{{template:plan-notes.md}}

## See also

plan, afk — fetch another guide with `strand devflow guidance <key>`.
