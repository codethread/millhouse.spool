# Devflow workspace

Code tells you *what*. Devflow docs tell you *why*. This overview orients any devflow workspace work; each artifact has its own guide, indexed at the end.

## Layout

```
devflow/
|-- README.md
|-- rfcs/
|   `-- YYYY-MM-DD-<slug>.md
|-- specs/
|   `-- <spec-name>.md
|-- feat/
|   `-- <feature>/
|       |-- proposal.md
|       |-- specs/
|       |   |-- <existing-spec>.delta.md
|       |   `-- <new-spec>.md
|       `-- <feature>.plan.md
`-- archive/
    `-- yy-mm-dd__<feature>/
        |-- ...everything from feat/<feature>/
        `-- rfcs/
            `-- YYYY-MM-DD-<slug>.md
```

`<feature>` is the kebab-case feature name; it is also the workflow run-id.

The AFK/HITL task queue is not a folder: tasks are strands in the Millstrand graph
(`strand ready --query devflow-tasks`), carrying the vocabulary the tasks
guide defines. Workspaces that bind their own decomposition targets keep
tasks or cards in that system instead.

## Invariants

- `devflow/specs/` is canonical for current contracts.
- `devflow/feat/<feature>/specs/` is staging for active feature changes.
- `devflow/archive/*` is historical context, not current truth.
- Any feature with a task queue must have proposal.md and `<feature>.plan.md`; tasks are strands (or the bound target's cards), never files under the feature folder.
- An approved proposal is frozen: it records the intent agreed at sign-off. Later change belongs in spec deltas, the plan, and code — never in a rewritten proposal.
- Developer Notes live in the feature plan; never create task-note README files.
- Do not copy RFC alternatives into specs, plans, or tasks; link to the RFC.
- Only the current feature's documents are writable during normal stage work; never edit archives or sibling feature folders, and touch root specs/RFCs only when the stage promotes or records durable outcomes.

## Document IDs

**Format.** IDs order as document type, short name, sequential id, optional version: `PROP-Dwr-001` for v1, `SPEC-Dwr-002@3` for a third version. Known prefixes: RFC, PROP, SPEC, DELTA, PLAN, TASK.

**Versioning.** Omit `@1`; append `@2`, `@3`, ... only when a new version supersedes an externally referenced document.

**Sub IDs.** Prefix every nested point ID with the full document ID (`PLAN-Dwr-001.P1`, `RFC-Dwr-001.O1`) so references are globally grepable and never clash across documents.

**Allocation.** Before creating a document, scan the whole workspace — root specs/RFCs, active feature folders, and the archive — for existing IDs with that prefix/name pair and take the next unused number. Ask when the next number or version is ambiguous.

**Editing.** {{id-editing}}

## Document ownership

{{ownership-table:rfc,root-spec,proposal,spec-delta,plan,tasks,archive,code}}

## Guides

Fetch one with `strand devflow guidance <key>`.

- **proposal** — Feature-local problem framing; frozen at sign-off as the agreed intent.
- **rfc** — Pre-feature decision record: options, tradeoffs, recommendation, outcome.
- **spec** — Stable system boundaries: root specs, feature specs, and deltas.
- **plan** — The reviewable build strategy between framing and task slicing.
- **tasks** — A deterministic AFK queue of tracer-bullet vertical slices.
- **afk** — Running that queue unattended until it is exhausted, blocked, or failing.
- **decompose** — Turning a merged proposal into cards a cold agent can work.
- **finish-archive** — Closing out a shipped or abandoned feature.
