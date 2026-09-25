# Devflow guide: finish-archive

Close out a shipped or abandoned feature: promote durable spec outcomes, reconcile task state, mark the plan, and move the feature folder (plus implemented RFCs) into the archive.

## Artifacts

- Archive: `devflow/archive/yy-mm-dd__<feature>/`
- Archived RFCs: `devflow/archive/yy-mm-dd__<feature>/rfcs/`
- Root specs: `devflow/specs/`
- Spec index: `devflow/README.md`

## Prerequisites

- Feature work is shipped, intentionally abandoned, or the user asked to finish/archive.
- The proposal, plan, linked RFCs, task queue, feature-local specs, and affected root specs have been read.

## Outcomes

- **Shipped** — Implementation is complete enough that durable outcomes should become canonical.
- **Abandoned** — Work stops intentionally; do not promote unshipped contract changes unless the user explicitly asks.

## RFC selection

- Archive RFC files explicitly linked from the proposal (Related RFCs) or plan (RFC).
- If multiple active features link the same RFC, ask before moving it; otherwise the implementing feature owns archiving it.
- Do not archive RFCs that are only background reading or still needed by another active feature.

## Procedures

### Finish

1. Identify the feature folder; ask if ambiguous.
2. Read the proposal, plan, linked RFCs, the feature's task strands (`strand list --query devflow-tasks`), feature-local specs, and affected root specs.
3. Identify the RFCs to archive per the RFC selection rules.
4. Reconcile task state with implementation reality: confirm shipped-scope task strands are closed and covered by code/tests; classify still-open tasks as cut scope and close them with a note saying so; record cut, deferred, or abandoned scope in the plan's final Developer Notes.
5. Decide the outcome: shipped or abandoned.
6. For shipped work, run the spec guide's promote-feature-specs procedure: merge deltas into root specs, promote new canonical specs, update the `devflow/README.md` index, and mark deltas Merged.
7. Update the plan: set Status Shipped or Abandoned, update Last Updated, and add a final Developer Notes entry summarizing shipped scope, cut scope, abandonment reason, and archived RFCs.
8. Move the feature folder to `devflow/archive/yy-mm-dd__<feature>/`.
9. Move each implemented RFC from `devflow/rfcs/` into that archive's `rfcs/` folder.
10. Report the root specs updated, feature folder archived, RFCs archived, and any cut or unpromoted scope.

## Constraints

- Do not promote unshipped behavior into root specs unless the user explicitly asks.
- Archive the approved proposal exactly as signed off; shipped versus cut scope is reconciled in the plan, never by rewriting intent after the fact.
- Do not delete proposal, plan, task, or archived RFC files; preserving feature-local context is the point of the archive.
- Never edit other archived features or sibling feature folders while archiving.

## Validation

- Shipped durable outcomes are merged into root specs and the README index is updated
- Cut or abandoned scope is recorded in the plan before archive
- Plan status is Shipped or Abandoned with a final Developer Notes entry
- Feature folder moved intact to `devflow/archive/yy-mm-dd__<feature>/`
- Implemented RFCs moved into the archive's `rfcs/` folder

## See also

spec, plan — fetch another guide with `strand devflow guidance <key>`.
