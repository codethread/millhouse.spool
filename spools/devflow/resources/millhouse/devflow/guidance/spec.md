# Devflow guide: spec

Describe a stable system boundary: why it exists, what it contains, what it excludes, and how it should evolve. Root specs are the current source of truth; feature-local specs and deltas stage pending changes.

## Artifacts

- Root spec: `devflow/specs/<spec-name>.md`
- Feature spec: `devflow/feat/<feature>/specs/<spec-name>.md`
- Spec delta: `devflow/feat/<feature>/specs/<spec-name>.delta.md`
- Spec index: `devflow/README.md`

## Prerequisites

- The system being specified has a clear scope, and relevant accepted RFCs have been read.
- For updates, the current root spec and its referenced modules have been read first.
- For feature staging, proposal.md, the feature plan if present, and relevant feature-local specs have been read.
- Code first: never write a spec from memory or assumption — read the actual code before documenting implemented behavior.

## Locations

- `devflow/specs/<spec-name>.md` — current durable domain spec.
- `devflow/feat/<feature>/specs/<spec-name>.md` — new spec drafted by a feature before promotion.
- `devflow/feat/<feature>/specs/<spec-name>.delta.md` — pending changes to an existing root spec, merged when the feature ships. State only what changes relative to the root spec; never duplicate it wholesale.

## Naming

A root spec names a stable system boundary, not a feature request or delivery task. Deltas use the root spec name plus `.delta.md` (`task-engine.delta.md`).

- Good: `auth-system`, `task-engine`, `data-pipeline`
- Bad: `add-priority-filter`, `spec-003`, `phase-2-redesign`

## Code references

Reference code at module/package granularity, never per-file; feature plans and task files may name exact files, specs may not.

- Allowed: module roots (`packages/pithos`), named concepts the module README maps, a one-line test directory pointer
- Forbidden: individual files; per-file tables, code-location or testing-file inventories

## Statuses

- **Draft** — Initial write-up; may not reflect code accurately yet.
- **Planned** — Intended contracts for a system not yet built; same density as Implemented — contracts and rationale, not build instructions.
- **Implemented** — Spec matches the code.
- **Partial** — Some sections implemented, others still planned.
- **Deprecated** — System is being replaced or removed.

## Spec vs code

| The spec records | The code shows |
|---|---|
| Why this design was chosen | What the design is |
| What was explicitly rejected | What was built |
| Non-goals and scope boundaries | Current behavior |
| Cross-system tradeoffs | Local implementation details |
| External API contracts | Internal types and functions |
| Domain concepts and invariants | The mechanics that enforce them |

## Document ownership

{{ownership-table:root-spec,spec-delta,rfc,plan}}

## Procedures

### Write a root spec

1. Read accepted RFCs, existing root specs, relevant feature folders, and code for implemented behavior.
2. Create `devflow/specs/<stable-domain-name>.md`, allocating the next SPEC ID per the ID convention.
3. Write the lightest spec that captures the boundary from the root-spec template; Purpose, Goals, Non-goals, and Design decisions are expected for most specs.
4. Add or update the spec row in `devflow/README.md`.

### Update a root spec

1. Read the root spec, relevant code, accepted RFCs, and archive context if it explains the change.
2. Update only durable current knowledge: contracts, rationale, non-goals, design decisions, status, open questions.
3. Remove stale planned text once it no longer describes the current contract.
4. Keep code references at module level and update `devflow/README.md` if index data changed.

### Write a feature spec or delta

1. Read the proposal, feature plan if present, relevant root specs, RFCs, and code.
2. Create `devflow/feat/<feature>/specs/` if needed and allocate the next DELTA (or SPEC) ID per the ID convention.
3. For an existing root spec, write `<spec-name>.delta.md` from the delta template; for a new feature-owned spec, use the root-spec format with status Planned or Draft.
4. Link the file from the feature plan when present; never add the link to an approved proposal.

### Promote feature specs

1. Read all files in `devflow/feat/<feature>/specs/` plus the affected root specs.
2. Merge each `*.delta.md`'s durable changes into its root spec and mark the delta Merged.
3. Move or copy each new spec's durable current version into `devflow/specs/` with the right status.
4. Update `devflow/README.md` with promoted specs and status changes.
5. Leave the feature-local copies in place for archive history.

## Constraints

- Root specs are the current source of truth; archived feature folders are historical context.
- No implementation phases, task checklists, or per-file code maps in specs.
- Do not duplicate RFC alternatives or proposal narrative in specs.
- Feature deltas are temporary staging; merge shipped outcomes into root specs.
- Deltas absorb contract change discovered after proposal sign-off; the approved proposal is never edited to match.
- Prefer minimal specs; grow only when the domain needs more explanation.
- {{id-editing}}

## Validation

- Root spec lives in `devflow/specs/` and names a stable domain boundary
- Status is valid and code pointers are module-level only
- Durable contracts and design decisions are captured; no phases, checklists, file trees, or test inventories
- Document has a stable SPEC/DELTA ID with document-prefixed sub IDs
- Deltas live in `devflow/feat/<feature>/specs/`, use `<spec-name>.delta.md`, and state only changes relative to the root spec
- `devflow/README.md` index is updated for root spec changes
- Feature plan links are updated when present

## Templates

### Root spec

{{template:root-spec.md}}

### Spec delta

{{template:spec-delta.md}}

## See also

rfc, plan, finish-archive — fetch another guide with `strand devflow guidance <key>`.
