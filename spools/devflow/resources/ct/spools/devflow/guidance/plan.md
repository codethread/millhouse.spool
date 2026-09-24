# Devflow guide: plan

The reviewable bridge between feature framing/spec work and the task queue: how to build this, at a level worth critiquing before committing to task slices.

## Artifacts

- Plan: `devflow/feat/<feature>/<feature>.plan.md`

## Prerequisites

- The change has a clear goal — a feature, fix, or refactor, not open-ended exploration; unresolved direction belongs in an RFC first.
- proposal.md exists, or is written as part of the planning pass.
- Accepted RFCs, root specs, and feature-local spec deltas relevant to the change have been read.
- The affected code has been read enough to avoid planning against imagined structure.

## Why plans exist

Plans earn their keep when a change is too large or risky to jump straight from proposal/spec to tasks: the approach gets critiqued once centrally, specs stay free of implementation mechanics, task files avoid carrying architecture, and the AFK loop has one feature-local home for task context and developer notes. Skip a plan only for small obvious changes that will not use a task queue — and any feature with a task queue needs at least a minimal Reviewed plan.

## Level of detail

- Name affected modules, packages, integration points, and key files only when they are architectural anchors.
- No exhaustive file inventories, per-function TODOs, or command-by-command instructions.
- Phases describe independently reviewable delivery increments, not final task files.
- Validation strategy names the suites, scenarios, or manual checks that matter; task files make checks exact later.
- Developer Notes are append-only operational context for agents running the task loop.

## Drift from the proposal

- The proposal is frozen at sign-off, so the plan is where the feature stays current: approach changes, re-scoping, and cut scope are recorded here and in the spec deltas.
- State a divergence plainly in Goal and scope or Developer Notes — the proposal it diverges from is intent, not a competing spec.
- If the divergence means the agreed problem itself no longer holds, raise it with the human rather than editing the proposal (see the proposal guide's rules on changed intent).

## Statuses

- **Draft** — Approach is still being written or critiqued; do not generate AFK tasks yet.
- **Reviewed** — Approach has been critiqued and is ready to slice into tasks.
- **Active** — Tasks or implementation are in progress.
- **Shipped** — Durable outcomes are merged into root specs; folder ready to archive.
- **Abandoned** — Work stopped intentionally; folder ready to archive with rationale preserved.

## Document ownership

{{ownership-table:plan,proposal,spec-delta,tasks,rfc}}

## Procedures

### Write

1. Read accepted RFCs, proposal.md, affected root specs, feature-local spec deltas, and affected code first.
2. Create `devflow/feat/<feature>/<feature>.plan.md` from the template, allocating the PLAN ID per the ID convention.
3. Omit sections that genuinely do not apply, except Goal and scope, Approach, Affected areas, Implementation phases, Validation strategy, Task context, and Developer Notes.
4. Record durable contract changes surfaced while planning as feature-local spec deltas (see the spec guide).
5. Leave status Draft until the plan has been critiqued; set Reviewed only after review feedback is addressed.

### Review or update

1. Read the plan, proposal, linked RFC/specs, task queue if present, and affected code.
2. Critique for approach fit, missing dependencies, over-broad phases, hidden domain decisions, and task-generation readiness.
3. Rewrite the plan in place; plans are working documents, not history logs.
4. Record divergence from the approved proposal here rather than editing the proposal.
5. Move durable contract changes to feature-local spec deltas or new specs.
6. If direction-level uncertainty remains, pause task generation and write an RFC.
7. When the approach is settled and phases are sliceable, set status Reviewed.

## Constraints

- Plans are reviewable strategy documents, not task queues.
- The plan may diverge from the approved proposal and say so; it must never be resolved by rewriting the proposal.
- One active plan per feature folder; split multi-feature roadmaps into separate feature folders.
- Never plan against imagined code structure; read affected code first.
- A Draft plan must not be sliced into AFK tasks; for small obvious queued work, create a minimal plan and mark it Reviewed after a sanity check.
- Once tasks exist, the task strands own sequencing (`depends-on` edges) and detailed acceptance criteria; stop maintaining the phase list as a parallel tracker.
- {{id-editing}}

## Validation

- Lives at `devflow/feat/<feature>/<feature>.plan.md`
- Links to the proposal, relevant RFCs, root specs, and feature-local specs
- Goal and scope, Approach, Affected areas, Implementation phases, Validation strategy, Task context, and Developer Notes are present
- Document has a stable `PLAN-<name>-<nnn>[@<version>]` ID with document-prefixed sub IDs
- Phase outcomes are independently buildable and verifiable
- Plan stays at strategy/phase level; no per-task implementation checklist
- Durable contract changes surfaced while planning were recorded in feature-local specs
- Status is Draft until critique is complete, then Reviewed before task generation

## Template

{{template:plan.md}}

## See also

proposal, spec, tasks — fetch another guide with `strand devflow guidance <key>`.
