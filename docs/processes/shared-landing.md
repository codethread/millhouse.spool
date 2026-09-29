# Repository-owned review and landing

Each ecosystem repository owns its `land` workflow in its `.millstrand`
workspace. Shared Millhouse Land supplies only low-level FIFO queue, exact-HEAD
validation, merge, cleanup, query, and card-action primitives. A repository's
workflow is the readable source of truth for review depth, merge history,
release preservation, and cleanup timing.

## Working contract

1. Claim a Kanban feature with its branch and worktree. Never make feature
   changes on `main`.
2. Iterate with the repository's quality command. Executable
   `.millstrand/land-quality.sh` is the authoritative landing gate.
3. Inspect `strand workflow show land` in the target workspace. Do not assume
   another repository's parameters, checkpoints, or merge method.
4. Drive that repository's `land`; do not merge or push `main` manually.
5. The repository records its required review evidence before sign-off. Richer
   or release-specific review belongs in that local graph, not a shared switch.
6. Approved work joins the shared durable FIFO queue. A failed turn remains in
   place for repair rather than allowing another landing to overtake it.
7. After merge, repository policy updates canonical `main`, releases the turn,
   and owns cleanup and card completion.

Inspect queue state with `strand merge-queue status`. Use live help for await and
withdrawal syntax. Withdrawal stops owned shell work and refuses a turn whose
merge may already have been submitted.

A failed predecessor belongs to its recovery owner. Record that dependency and
notify the owner once, then keep awaiting the same Land run. Re-read the frontier
after every wait because executors may merge and remove the worktree without
another agent turn. Queue release alone does not prove cleanup or completion.

Keep the board truthful: use `pending` while solely waiting for another card or
when stopping without an active successor, `claimed` while agent work continues,
and `in_review` only for a specific human action recorded on the card.

Before sign-off, remove owned scratch files and stop owned processes by exact PID
or session name. Repository cleanup that must wait until after merge belongs in
tracked executable `.millstrand/land-cleanup.sh`. Hook failure retains resources
and prevents completion.

## Repository policy examples

Millhouse and Millstrand UI use local one-seat review and squash landing.
Millstrand uses a local merge-commit policy and refuses queue-time rebasing so
validated release and formula commits retain their identities. These choices are
independent even though all three use the same FIFO infrastructure.

A local queue-compatible merge continuation declares:

- `workflow/family=land` and `land/stage=merge`;
- its fully qualified local `land/abort-definition`;
- `merge-turn` before preparation and irreversible work;
- `merge-release` after canonical `main` is verified and before housekeeping.

The abort definition declares `:continue`. Withdrawal passes it the landing
context plus `:reason`, merges its defaults underneath those params, and requires
the result to satisfy its parameter spec before changing the queue.

The Land package README documents the shared primitive contract. Repository
instructions and tests document the chosen composition.

## Bootstrap consumption

The recommended consumer uses the shared config bootstrap:

```clojure
(require '[millhouse.config.bootstrap :as config])

(config/register! runtime)
;; Register repository-specific aliases, workflows, and the local land module.
(config/register-executor! runtime [:consumer/modules :consumer/land])
```

`register!` activates Identity, Workflow, Kanban, Harnesses, shared aliases and
reviewers, and landing infrastructure. It does not register a `land` workflow.
The sole shared `:agent` executor remains last so restored gates cannot run before
consumer policy is reconciled.

A consumer that does not want the shared agent catalog can depend directly on
`millhouse/land`, activate `millhouse.land.spool` after Workflow and Kanban, and
register its own workflow and required executors.

Published dependencies use immutable Git SHAs. Consolidated Millhouse packages
use declared relative local roots within the same revision; external consumers
pin that revision by Git SHA.

## Repository quality scripts

Each repository keeps executable `.millstrand/land-quality.sh`:

- UI repositories run their package quality gate, such as `pnpm quality`.
- Clojure repositories run their aggregate gate where one exists.
- The script starts at the repository root and runs `git diff --check` before
  behavior gates.

Shared code never guesses a repository's language, Make target, warm process, or
generated resources.

## Verification

Before publishing a consumer pin, verify its checked-in workspace in a disposable
world. The consumer smoke checks landing infrastructure, `merge-queue`, and the
repository-owned `land` definition selected by that workspace.

After changes reach `main`, runtime owners may separately authorize an activation
window. Never restart a Weaver without explicit user sign-off. Verify
`strand help merge-queue` and `strand workflow show land` against each live
workspace after its pin is adopted.
