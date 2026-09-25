# Agent blocker rollout

> Historical rollout record: paths, pins, and board states below describe the
> pre-consolidation ecosystem, not the current source workflow. For new work use
> [consolidation and handoff](../consolidation.md) and the owning workspace
> policy. No live activation is authorized by this record.

The canonical [discriminated union and patterns](auto-run.md#agent-blocker-contract) replace the earlier independent failure and decision flags. Dispatcher admission, receipts, capacity and scheduling remain unchanged.

## Approved rollout

The Millstrand weave update and Codethread reporting contract are published. The Millhouse pilot was reviewed and approved on 2026-09-22. The approved approach is now rolled out across the consumers below. All eight discovered Weavers were restarted and verified; commits, checks and runtime generations are recorded on the repository features under coordination epic `s92hn`.

| Repository | Scope |
| --- | --- |
| Millhouse | Approved pilot: shared reporting patterns, label hook and optional landing wrapper; core Land remains independent. |
| Millstrand | Update consumer pins and autorun module activation. The weave API extension alone does not migrate its autorun policy. |
| Harnesses | Update consumer pins and module activation; remove copied reporting prose. |
| Millstrand UI | Update pins and module activation; include `agent-blocked` and `needs-decision` in autorun attention filtering. Preserve unrelated inspection findings and human-attention uses. |
| Devflow, Agents, Notes | Align existing dependency pins and restart; keep autorun inactive where it is not configured. Agents and Notes share one Weaver pool. |

Each consumer selects `millhouse.auto-run-reporting` patterns and its `derive-labels` hook. Repositories using the optional autonomous landing helper import `millhouse.auto-run-land`. Standalone Land has no reporting requirement. Use the published revisions recorded on the epic and feature cards.

## Runtime activation

Source publication does not update running Weavers or frozen assignments. The coordinator is authorized to update dependency pins and restart Weavers, including aligning sibling dependencies together when needed to clear blockers. The approved rollout includes all discovered Weavers. Track commits, checks and runtime generations on each repository's feature under the coordination epic.

At cutover, inspect active runs and existing reporting attributes. Where a current blocker must be retained, save or identify its evidence strand and publish exactly one new variant; remove the obsolete flags, question/role attributes and failure display label as part of that reviewed conversion. Retain the evidence itself. Do not infer a failure from an ordinary checkpoint or an unrelated UI inspection result.

Verify pattern discovery and reporting in a disposable world first. Existing running assignments retain their frozen guidance until an explicitly planned handoff or completion.

Millhouse PR 47 and its superseded tracking card are closed. Their history is retained; the accepted pilot and rollout are recorded on the Millhouse feature.
