# <Feature name> Plan

**Document ID:** `PLAN-<name>-<nnn>[@<version>]`
**Feature:** `<feature>`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** [title](../rfcs/YYYY-MM-DD-slug.md) <!-- or: none -->
**Root specs:** [domain.md](../specs/domain.md) <!-- or: none yet -->
**Feature specs:** [specs/domain.delta.md](./specs/domain.delta.md) <!-- or: none -->
**Status:** Draft | Reviewed | Active | Shipped | Abandoned
**Last Updated:** <YYYY-MM-DD>
{{config-id:PLAN}}

## PLAN-<name>-<nnn>.P1 Goal and scope

One paragraph: what this feature delivers. Link to the proposal/spec for why it matters.

## PLAN-<name>-<nnn>.P2 Approach

- **PLAN-<name>-<nnn>.A1:** Chosen implementation strategy, architecture, sequencing, integration boundaries, and important mechanics.

## PLAN-<name>-<nnn>.P3 Affected areas

| ID                    | Area                | Expected change                                                 |
| --------------------- | ------------------- | --------------------------------------------------------------- |
| PLAN-<name>-<nnn>.AA1 | `module/or/package` | High-level change                                               |
| PLAN-<name>-<nnn>.AA2 | `key/file.ts`       | Only include specific files when they are architectural anchors |

## PLAN-<name>-<nnn>.P4 Contract and migration impact

- **PLAN-<name>-<nnn>.CM1:** High-level data model, API, CLI, config, or migration impact. Durable contract changes belong in feature-local spec deltas or new specs, not only here.

## PLAN-<name>-<nnn>.P5 Implementation phases

### PLAN-<name>-<nnn>.PH1 <name>

Outcome: <reviewable outcome this phase delivers>

### PLAN-<name>-<nnn>.PH2 <name>

Outcome: <reviewable outcome this phase delivers>

## PLAN-<name>-<nnn>.P6 Validation strategy

- **PLAN-<name>-<nnn>.V1:** What must be proven before the change is trusted.

## PLAN-<name>-<nnn>.P7 Risks and open questions

- **PLAN-<name>-<nnn>.R1:** Implementation risk with mitigation.
- **PLAN-<name>-<nnn>.Q1:** Open question blocking task generation.

## PLAN-<name>-<nnn>.P8 Task context

- **PLAN-<name>-<nnn>.TC1:** Brief context task authors and AFK agents need, including important references.

## PLAN-<name>-<nnn>.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### PLAN-<name>-<nnn>.DN1 Task <id>: <description> — <YYYY-MM-DD>

- Note relevant for later agents or follow-up scope.
