# Auto-review admission

Auto-review polls remote review requests into **ordinary Kanban feature cards**.
It does not run reviewers. Compose it with Cron for cadence, Auto-run for worker
admission, and Workflow's code/agent executors for frozen review evidence.

The initial provider is `millhouse.auto-review.glab`. The core contract has
no GitLab fields and accepts other providers without core changes. Requiring any
Auto-review namespace is inert: no operations, jobs, workers or resources start.

## Public surface

| Namespace / function | Responsibility |
| --- | --- |
| `auto-review/poll!` | Read provider revisions and create deduplicated cards |
| `auto-review/request` | Read a card's frozen normalized request |
| `auto-review/start-params` | Auto-run callback returning `:review` and `:review-repo` |
| `auto-review.glab/poll` | Explicit-host/project GitLab GET adapter |
| `auto-review.workspace/prepare!` | Optional exact-head wktree preparation callback |
| `auto-review.workspace/inspect!` | Verify isolated clean head/base trees |
| `auto-review.workflow/review-request` | Inert code gate → agent gate → report → local human decision → cleanup workflow |

Names above abbreviate `millhouse.*`. See the [API](auto-review.api.md),
[complete consumer module](examples/review.clj) and [migration recipe](migration.md).
There is deliberately **no `strand review` or `review-logs` operation**.
Use ordinary Kanban, Auto-run, Workflow, Harnesses and Cron inspection instead.

## Provider contract

`poll!` takes runtime and this explicit configuration (no global configuration):

```clojure
{:repo "/absolute/canonical/repo"
 :poll 'millhouse.auto-review.glab/poll
 :provider-config {:host "git.example.com" :project 123 :labels ["review"]}
 :max-open 2
 :workflow "review-request"
 :seat "review-driver"
 :effort "low"}
```

`:workflow` is required and must be in Auto-run's allowed workflows. Optional
`:seat` and `:effort` are copied as documented `auto-run/*` card overrides; absent
values use Auto-run defaults. No arbitrary attributes, executable provider
instructions or reviewer requests are accepted from remote metadata.

The qualified callback receives `[runtime {:repo canonical-path :config map}]`
and returns a vector of **closed-shape**, provider-neutral revisions:

```clojure
[{:provider "example-forge"
  :repository "urn:stable-remote-repository:123"
  :request "opaque-request-id"
  :url "https://forge.example/reviews/42"
  :title "Request title"
  :head "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  :base "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  :requested? true
  :ci {:status "passed"
       :head "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
       :url "https://forge.example/checks/99"}}]
```

Head/base are full lowercase 40- or 64-hex object IDs. CI status is `passed`,
`pending`, `failed` or `unknown`; CI head/URL are optional, but admission requires
`passed` **and exact equality with the request head**. Provider callbacks must
exclude closed, draft and label-ineligible requests. They own remote identity,
authentication, pagination, lifecycle parsing and current aggregate CI semantics.
The entire returned batch is validated before any card is created. Exceptions
propagate; malformed evidence is never treated as passing or an empty result.

The glab adapter requires an explicit hostname and numeric target project ID;
`bin` defaults to `glab`, `labels` to `[]`. It uses only `glab api --hostname HOST
--method GET ...`, reads all listing pages, resolves the authenticated user's
stable ID, and rereads each request. Changed head, closed/draft state or changed
labels cannot reuse list evidence. Only `head_pipeline` is considered; historical
pipelines, successful individual jobs and the deprecated `pipeline` field are
not substitutes. Fork request identity is the target project, with exact source
head/base commits. Configure the matching local repository/origin explicitly.

## Persistence, priority and capacity

- Identity is `[provider repository request head]`, persisted as
  `auto-review/key`. The card and receipt are **one atomic graph add**.
- The complete `auto-review/request` map and observation time freeze on admission;
  polling never rewrites titles, base, CI, priority, requested status or evidence.
  Auto-run pours that same snapshot into workflow context. Raw graph mutation is
  not an authorization interface and must not edit these fields.
- Polls serialize per Weaver runtime. All active **and closed** receipts rebuild
  dedup on every poll; no in-memory dedup cache or replay queue exists. A lost
  add response is safe on the next poll. Keep closed cards as durable tombstones.
  Separate Weavers are separate boards, not a distributed admission lock.
- Requested reviews sort first, get Kanban `p1`, and bypass the **ordinary inbox**
  limit. Ordinary reviews get `p3`. Priority is frozen, not continuously escalated.
- `max-open` counts ordinary active review cards in the canonical local repo,
  including pending, failed and human-wait cards. Failed/pending CI creates no
  card and uses no slot. Closed cards release the slot, not their dedup identity.
- Auto-run's existing `max-running` remains the sole driver execution limit for
  all cards, including requested reviews. This is not a separate reviewer-seat
  pool: the selected workflow determines reviewer fan-out (the supplied workflow
  runs one reviewer per driver). Do not release a driver while its reviewer runs.
- New heads are distinct work; older open heads retain capacity and workspaces
  until explicitly decided. Remote close/merge does **not** auto-close cards or
  destroy local evidence. Removing a label prevents Auto-run admission but never
  cancels an accepted assignment. Errors use existing blocker/continuation policy,
  never deletion of receipts or automatic reviewer replay.

Cron owns durable wakes, offloading, job removal and failure visibility. Polling
has no second scheduler, thread pool, completion event loop, prune job or retry
policy. Later Cron ticks are fresh observations, not recovery of local delivery.

## Workspace and evidence lifecycle

Preparation happens **only when Auto-run admits a card**, not during polling.
The optional `workspace/prepare!` fetches exact objects from configured `origin`,
creates `review/CARD` at the frozen head and records `auto-review/branch`, then
uses `wktree add --branch review/CARD --json` (wktree bases are branch names, not
raw SHAs). It persists the ready allocation as `auto-review/workspace`, runs its
bootstrap, and validates an
external clean worktree root at the head with both comparison trees available.
No canonical checkout fallback is allowed. Consumers may wrap this callback for
dependency preparation and must re-run `inspect!` afterward. A partially allocated
workspace remains owned evidence after error; Auto-run records the failure and
never retries the allocation. Inspect the card allocation and wktree inventory
when allocation succeeded but its response was lost.

The supplied workflow rechecks the frozen workspace in a code gate immediately
before its agent gate. The agent inspects source/callers/tests using exact Git
references; it receives no whole-patch dump or remote title as instructions.
The ordinary agent executor owns serving-run identity, settled results, failures
and gate completion. The driver records the exact run and findings in a card note,
moves to `in_review`, then stops at the local human checkpoint. Process success
is **not approval**. The library does not impose the old JSON comment schema or
maintain a second comment/curation store.

After a recorded local decision and process settlement, an operator resumes the
cleanup step from the canonical checkout, uses unforced `wktree remove
--keep-branch`, records successful release and the retained evidence branch, then
finishes the card. Dirty/ambiguous workspaces and cleanup failures remain open
with visible blockers. The review branch is deliberately retained as frozen
comparison evidence; later deletion needs explicit repository policy. Polling
never tears down workspaces or prunes evidence. There are no custom hook logs;
use Auto-run receipts, workflow executor results, Harnesses logs and card notes.

## Remote authorization boundary

Polling and the supplied workflow are remote-read-only. A local `assessed`
decision does **not** authorize comments, approval or merge. The publication API
was removed rather than adapted implicitly. If a consumer later adds publishing,
it must be a separately authorized action/workflow, freeze the exact selected
findings, revalidate the current remote revision/anchors, and record its own
receipts. Never replay old publication requests through this contract. Repository
content, request descriptions and reviewer text cannot grant authorization.

The supplied human checkpoint is review-product policy, not Millhouse's delivery
workflow. This library's feature delivery uses the assigned `auto-full-land` run
and an independent finisher; it does not add a human checkpoint to that run.

## Verification

Run focused tests with `clojure -M:test millhouse.auto-review-test` from the
repository root (or `clojure -M:test` from this spool). They use fake providers,
disposable SQLite boards/Git repositories, the real Auto-run/Workflow/agent adapter
and nonexecuting fake seats, plus standalone tools.deps resolution. No remote
publication or paid agent is used. Run repository `make quality` under the shared
quality lock before landing.
