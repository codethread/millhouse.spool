# Devflow guide: rfc

Pre-feature decision record: frame an unresolved idea, compare options, recommend a direction, and record the outcome before proposal/spec/plan work.

## Artifacts

- RFC: `devflow/rfcs/YYYY-MM-DD-<slug>.md`

## Prerequisites

- The idea has meaningful uncertainty: a tradeoff, architectural choice, product direction, or scope question worth recording.
- Relevant specs, active feature folders, READMEs, and code have been read when the idea touches existing behavior.

## Write an RFC when

- Multiple plausible approaches exist and the tradeoff matters.
- The change crosses system boundaries or affects long-lived architecture.
- Product or user-experience direction is unclear.
- The safest next artifact is a recommendation, not code.

Skip it when:

- The approach is already chosen and only sequencing is needed — write the plan instead.
- The request is durable domain documentation with little tradeoff exploration — write a spec instead.
- The change is a small obvious fix where code and tests are clearer than a document.

## Statuses

- **Draft** — Authoring in progress; not ready for decision.
- **Open** — Ready for feedback or explicit decision.
- **Accepted** — Proposal chosen; follow-up belongs in specs and feature folders.
- **Rejected** — Intentionally not pursued; still valuable — it stops the question being reopened without new evidence.
- **Superseded** — Replaced by a newer RFC; link to the replacement.

## Naming

Filename is creation date plus a short kebab-case idea slug (`2026-06-22-subagent-cost-budget.md`). The document ID is separate from the filename.

## Document ownership

{{ownership-table:rfc,root-spec,proposal,plan}}

## Procedures

### Write

1. Read relevant existing context: root specs, active feature folders, READMEs, and code.
2. Create `devflow/rfcs/` if it does not exist and add `YYYY-MM-DD-<slug>.md`.
3. Allocate the next RFC document ID per the ID convention.
4. Write the RFC from the template; keep implementation details at consequence level.
5. Leave status Draft while drafting; set Open when ready for decision.
6. If exploration proves the decision trivial, say so and switch to the lighter artifact instead.

### Update

1. Read the RFC and linked context.
2. Update the proposal, options, recommendation, or outcome in place; replace stale reasoning rather than preserving a debate log.
3. If an accepted RFC changes durable contracts, update the root spec or feature-local delta that owns the current contract.

### Close

1. Set status to Accepted, Rejected, or Superseded.
2. Fill Outcome with the decision, rationale, date, and follow-up links.
3. For Accepted RFCs, update or create the affected root specs or feature-local spec deltas.
4. If implementation is needed, write a feature proposal (or update one still under review; an approved proposal is frozen) and continue with the plan.

## Constraints

- RFC status records the decision state, not implementation progress; finished feature work retires the RFC by archiving it with the feature.
- Keep RFCs concise enough that a future agent can quickly recover the decision.
- Never use an RFC as the current contract; root specs own that.
- Never put implementation phases, task checklists, or detailed migration runbooks in an RFC.
- {{id-editing}}

## Validation

- File lives in `devflow/rfcs/` and follows `YYYY-MM-DD-<slug>.md` naming
- Status is one of the allowed RFC statuses
- Problem, goals, options, recommendation, consequences, and outcome are present when relevant
- Document has a stable `RFC-<name>-<nnn>[@<version>]` ID with document-prefixed sub IDs
- Alternatives and tradeoffs are clear enough to make the decision repeatable
- Accepted outcomes that affect current contracts are represented in root specs or feature-local deltas

## Template

{{template:rfc.md}}

## See also

proposal, spec, plan — fetch another guide with `strand devflow guidance <key>`.
