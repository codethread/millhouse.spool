# Devflow guide: decompose

Decompose a merged, approved proposal into self-contained implementation cards a cold agent can work without this run's context.

## Artifacts

- Proposal: `devflow/feat/<feature>/proposal.md`

## Prerequisites

- The approved proposal is merged on the repository mainline; decomposition reads the merged copy, not a working-tree draft.
- The proposal, its linked RFCs, and the affected root specs have been read.
- Where cards live is decided by the stage's `:author-cards` defer: the shipped `author-card-strands` target authors them as strands using the tasks guide's vocabulary, and a workspace may bind targets for external card systems (issue trackers, ...) instead or as well.

## The cold-card contract

- Each card is workable cold: an agent holding only the card body and the merged proposal can claim it, do the work, and finish it — card bodies carry context, never pointers into this run's conversation.
- A card body states what exists today (with file/line evidence where it helps), the target shape, and any constraints or design decisions already validated during proposal work.
- Each card names an explicit done-when: observable outcomes, the validation gates that must be green, and how the work lands (merge and release discipline included).
- Every card lands independently before it closes; a card that cannot land alone is sliced wrong.

## Dependency edges

- Cards that must land in order declare dependency edges, so a ready/frontier view serves only workable cards.
- Independent cards declare no edges; false edges serialize work that could land in any order.

## Open decisions

A decision the proposal left open is either resolved on the card as recorded guidance, or the card instructs the worker to surface it as a blocker — a cold worker never decides it silently.

## Review scopes

Each card:

- One focused reviewer per card checks only that card's cold-work contract, proposal traceability, direct dependencies, validation, and independent landing shape.
- Focused reviews fan out without dependency edges; the configured card-reviewer seat is reused for each card.
- A focused reviewer does not redesign the card set or repeat set-wide coverage analysis.

Card set:

- One separately configured set-level reviewer starts after every focused review fans in.
- It checks proposal-goal coverage, gaps and overlaps, slicing, dependency direction, integration seams, and cross-card open decisions.
- It assumes focused review is complete and must not repeat fine-grained card-contract checks.

## Procedures

### Decompose

1. Read the merged proposal; note its goals, non-goals, and validation requirements.
2. Draft the card set: one independently landable outcome per card, sliced by outcome rather than by file or layer. Whether the set also carries a grouping card is the workspace's own convention, not devflow's.
3. Write each card body per the cold-card contract: current state, target shape, constraints, done-when with validation gates and landing discipline.
4. Declare dependency edges exactly where landing order is constrained.
5. At handoff-card-review, choose review with the complete card-ref vector; each ref carries a token-safe id and title (with strand cards, the strand id is the card id).

### Reconcile reviews

1. Wait for all focused card-review gates and the later set-level cohesion gate to close.
2. Read `harness/result` from every review gate; keep focused findings attached to their card and set-level findings attached to slicing, coverage, or edges.
3. Apply valid findings to the cards where they live — strand bodies and `depends-on` edges for the shipped target, or the bound card system — without editing the merged proposal.
4. If any material card changed, choose review-again with the current full card set; otherwise choose accepted and let the card loop own implementation.

## Constraints

- Never edit the merged proposal; divergence discovered while decomposing is recorded on the affected cards.
- Do not start implementation from this run; implementation belongs to the card loop that works the authored cards.
- Cards are never `devflow/` filesystem documents: the shipped target authors them as strands in the graph, and bound targets author them in their own card system.

## Validation

- Every proposal goal is covered by at least one card and every card traces back to the proposal
- Each card body passes the cold-card contract: context, target shape, done-when, validation gates, landing discipline
- Dependency edges reflect real landing-order constraints and nothing else
- Open decisions are recorded as card guidance or explicit surface-a-blocker instructions, never left implicit
- Every card has a focused review result from the configured card-reviewer seat
- The card set has one later cohesion result from the configured card-set-reviewer seat, scoped to connections rather than repeated fine-grained checks
- The driving agent reconciled both result classes and repeated review after material card changes

## See also

proposal, finish-archive — fetch another guide with `strand devflow guidance <key>`.
