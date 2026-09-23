# GitLab reviews

`millhouse.spools.auto-review` turns selected GitLab merge-request revisions into local review
records. Consumer-owned lifecycle hooks acquire and release each review
worktree, Harnesses reviewers inspect the prepared revision, and their evidence
is published through `strand review` and the Millstrand Reviews tab.

Polling, reviewer execution, curation, and finishing are local. Only the explicit
`review publish` command writes GitLab discussions; it never approves or merges
the MR. Finishing a local review never writes GitLab.

## Prerequisites and activation

The consumer workspace needs Millstrand 0.5.3, this spool in `deps.edn`, `git`,
authenticated `glab`, a matching GitLab `origin`, and available headless reviewer
seats. Add the spool using the repository's [consumption instructions](../../README.md#consumption). Compose the shared modules before activating the coordinator:

```clojure
(require '[me.config :as config]
         '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(config/register! runtime)
(config/register-executor! runtime)

(runtime/module! runtime :mr-review
  {:ns 'millhouse.spools.auto-review
   :after [:work/config :work/workflow]
   :required? true})

(runtime/module! runtime :local-mr-review
  {:file "review.clj"
   :after [:mr-review :millstrand/spools-agent-executor]
   :required? true})
```

The local module declares the reviewer roster and owns the lifecycle resource.
Here `review-setup` and `review-teardown` are Vars containing the Bash described
in the workspace-hook contract below:

```clojure
(ns local-mr-review
  (:require [ct.spools.harnesses.reviewers :as reviewers]
            [millhouse.spools.auto-review :as review]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

(reviewers/defreviewer! correctness
  "Find concrete correctness regressions."
  {:seat ['reviewer 'luna]}
  "Inspect relevant source, callers, and tests. Report actionable findings.")

(defn open! [ctx]
  (review/open! ctx
    {:repo-dir "/absolute/path/to/repo"
     :reviewers ["correctness"]
     :setup review-setup
     :teardown review-teardown
     :poll? false}))

(lifecycle/defresource! local-review-runtime
  "Own this repository's review coordinator."
  {:open 'local-mr-review/open!
   :close 'millhouse.spools.auto-review/close!})
```

Loading `millhouse.spools.auto-review` registers commands but launches no worker. Opening the
resource starts completion recovery and log retention. Recurring GitLab polling
starts only when `:poll?` is true.

## Configuration

| Setting | Default | Meaning |
| --- | --- | --- |
| `:repo-dir` | required | Canonical repository checkout |
| `:reviewers` | required | Non-empty list of registered reviewer names |
| `:setup` | required | Trusted Bash that acquires and prepares a worktree |
| `:teardown` | required | Trusted, idempotent Bash that releases the worktree |
| `:poll?` | `false` | Enable recurring GitLab polls |
| `:interval-seconds` | `300` | Poll cadence |
| `:max-active-reviews` | `2` | Ordinary reviews allowed to await execution or a local decision; reviews explicitly requested from the authenticated GitLab user are additional |
| `:labels` | `[]` | Labels that an MR must contain; drafts are always excluded |
| `:glab-bin` | `"glab"` | GitLab CLI executable |
| `:setup-timeout-seconds` | `900` | Setup deadline |
| `:teardown-timeout-seconds` | `240` | Teardown deadline; this bounds `review finish` while its invocation remains open |
| `:log-retention-days` | `7` | Operational-log and completed-review hook-output retention |

## Workspace hook contract

Both hooks run through the user's login shell and then trusted Bash, with
`:repo-dir` restored as the working directory. They receive these variables:

| Variable | Meaning |
| --- | --- |
| `MILLSTRAND_REVIEW_ID` | Durable review strand ID |
| `MILLSTRAND_REVIEW_REPO` | Canonical `:repo-dir` |
| `MILLSTRAND_REVIEW_MR_IID` | GitLab MR IID |
| `MILLSTRAND_REVIEW_HEAD` | Exact admitted head SHA |
| `MILLSTRAND_REVIEW_BASE` | Exact GitLab diff-base SHA |

Setup also receives `MILLSTRAND_REVIEW_RESULT`, the path of its result file. It
must create or acquire an isolated worktree outside `:repo-dir`, prepare the
exact head and base objects, then write one JSON object containing an absolute
`worktree_path`. When `kind` is present it must be `"ready"`. Other keys are
preserved for teardown, so the JSON emitted by `wktree add --json` is accepted
directly. Write the result only after all setup—including any returned
`post_create_script_path` and dependency preparation—has succeeded. A setup
hook must roll back its own partial allocation when it exits unsuccessfully.

For example, a setup can create a review branch, allocate it through `wktree`,
run its generated bootstrap, and publish the unchanged result:

```bash
review_branch="review/mr-${MILLSTRAND_REVIEW_MR_IID}-${MILLSTRAND_REVIEW_ID}"
git -C "$MILLSTRAND_REVIEW_REPO" fetch --no-tags --no-write-fetch-head \
  origin "$MILLSTRAND_REVIEW_HEAD" "$MILLSTRAND_REVIEW_BASE"
if git -C "$MILLSTRAND_REVIEW_REPO" show-ref --verify --quiet \
  "refs/heads/$review_branch"; then
  test "$(git -C "$MILLSTRAND_REVIEW_REPO" rev-parse "$review_branch")" = \
    "$MILLSTRAND_REVIEW_HEAD"
else
  git -C "$MILLSTRAND_REVIEW_REPO" branch \
    "$review_branch" "$MILLSTRAND_REVIEW_HEAD"
fi
wktree_result=$(wktree --cwd "$MILLSTRAND_REVIEW_REPO" add \
  --branch "$review_branch" --json)
worktree=$(printf '%s\n' "$wktree_result" |
  jq -er 'select(.kind == "ready") | .worktree_path')
post_create=$(printf '%s\n' "$wktree_result" |
  jq -r '.post_create_script_path // ""')
if test -n "$post_create"; then bash "$post_create"; fi
# Run repository-specific preparation in "$worktree" here.
result_tmp=$(mktemp "${MILLSTRAND_REVIEW_RESULT}.tmp.XXXXXX")
printf '%s\n' "$wktree_result" > "$result_tmp"
mv "$result_tmp" "$MILLSTRAND_REVIEW_RESULT"
```

Teardown receives two additional variables:

| Variable | Meaning |
| --- | --- |
| `MILLSTRAND_REVIEW_WORKTREE` | Canonical path reported by setup, or empty when setup never reported one |
| `MILLSTRAND_REVIEW_WORKSPACE` | Path to a JSON file containing setup's complete result object |

Use `wktree remove --keep-branch` when the review branch should remain after its
checkout is released. Teardown must be idempotent: after a process succeeds but
before completion is durably recorded, a retry may run it again. Hook output is
file-backed and its path is recorded in review activity. Successful setup and
teardown output remains available through the local decision, then daily cleanup
deletes its owned temporary files after `:log-retention-days`. Failed hook output
is removed immediately. A non-zero exit or timeout leaves the review open and
records a visible failure. After correcting the problem, retry `review finish`.

```bash
review_branch="review/mr-${MILLSTRAND_REVIEW_MR_IID}-${MILLSTRAND_REVIEW_ID}"
current_branch=$(git -C "$MILLSTRAND_REVIEW_WORKTREE" \
  branch --show-current 2>/dev/null || true)
if test "$current_branch" = "$review_branch"; then
  wktree --cwd "$MILLSTRAND_REVIEW_REPO" remove \
    --branch "$review_branch" --keep-branch --json
fi
```

## Reviewer result contract

Every successful reviewer must return one strict JSON object as its complete
Harness result. Prose, Markdown, and fenced JSON are rejected. The object has a
summary plus zero or more candidate comments:

```json
{
  "summary": "Checked the changed state transition and its callers.",
  "comments": [
    {
      "title": "Preserve the prior state on failure",
      "text": "This assignment happens before validation and leaks partial state.",
      "severity": "P1",
      "position": {
        "kind": "line",
        "oldPath": "src/example.clj",
        "newPath": "src/example.clj",
        "side": "new",
        "line": 42
      }
    }
  ]
}
```

`severity` is optional. A line position may also carry `startSide` and
`startLine` for a same-side range. Whole-MR observations use
`{"kind":"general","reason":"..."}`. A reviewer that cannot express an
important location uses `{"kind":"unsupported","reason":"..."}`; such a
candidate remains visible locally but cannot be published.

## Command API

Start with:

```text
strand prime review
strand review list
strand review show <id>
strand review comments <id>
```

### Inbox and evidence

| Command | Result |
| --- | --- |
| `strand review list` | Reviews awaiting a local decision |
| `strand review list --all` | Active and decided revisions |
| `strand review list --mr <iid>` | Revisions for one MR |
| `strand review list --stage <stage>` | Reviews in `preparing`, `dispatching`, `running`, `reviewed`, or `failed` |
| `strand review show <id>` | Full report, reviewer evidence, activity, worktree, links, and other MR revisions |
| `strand review comments <id>` | Canonical structured comments, curation version, and publication receipts |

`list` returns `{reviews, counts}`. `show` returns `{review}`. Review summaries
identify the review, MR, revision SHA, stage, local decision, teardown status,
timestamps, reviewers, and report availability. Reviewer details include their
run status, summary, or error. The report is a derived Markdown view of the
structured records. Optional comment and publication fields are omitted when
absent. MR identity uses `headSha`, `baseSha`, and `startSha`.

### Curation and publication

Read `review comments` immediately before each mutation. A new review has
curation version `0`; every candidate starts at version `1`. Inclusion and
candidate-text changes are one atomic compare-and-set operation:

```json
{
  "revision": "review-comment-revision",
  "expectedVersion": 0,
  "by": "user@example.com",
  "changes": [
    {"id": "comment-a", "inclusion": "dismissed"},
    {
      "id": "comment-b",
      "candidate": {
        "expectedVersion": 1,
        "text": "Accepted publication text"
      }
    }
  ]
}
```

Pass the request through a file-backed payload so shell quoting cannot alter it:

```text
strand --payload request=curate.json review curate <id> --request :payload/request
```

The request must contain at least one real change. Its review revision, review
version, comment IDs, and any candidate versions must all still match. A stale
or invalid request changes nothing. Candidate provenance is either the original
reviewer `{kind, reviewer, runId}` or an accepted edit `{kind, by, at}`; the
immutable `candidate.original` always retains the reviewer text and identity.

Publication points to the resulting persisted snapshot:

```json
{"revision":"review-comment-revision","curationVersion":1}
```

```text
strand --payload request=publish.json review publish <id> --request :payload/request
```

At least one candidate must be included. Before any POST, the coordinator
rechecks that the MR is open and that its project, IID, head, base, start, latest
diff anchors, and positioned lines still match the frozen review. It freezes the
selection, writes a local `reconciling` receipt, searches all GitLab discussions
for the comment's hidden stable marker, and posts only after a successful absent
search. If a POST may have succeeded remotely but its response was lost, the
result remains retryable; rerunning the same publish request finds the marker and
records the GitLab discussion without posting a duplicate. A batch can therefore
return `partial` until retry completes it. Once publication begins, curation is
immutable.

Publication does not decide the review, release its capacity slot, or run
teardown. Use `review finish` separately after assessing the published result.

### Local decisions and related work

```text
strand review link <id> <feature-or-task-id>
strand review finish <id> --outcome done --by <name> --note "Assessed findings"
strand review finish <id> --outcome dismissed --by <name>
```

`link` adds a relationship to existing work without creating a Kanban card.
`finish` runs teardown, records an immutable local decision, and releases the
review's capacity slot. A review must be `reviewed` or `failed` before it can be
finished. `done` is the normal “review is good/actioned” path; `dismissed` has
the same teardown timing.

### Polling, recovery, and logs

| Command | Purpose |
| --- | --- |
| `strand review poll` | Queue an intentional GitLab poll; may launch reviewers |
| `strand review reconcile` | Recover dispatch and collect completed reviewer runs without reading GitLab |
| `strand review status` | Show worker configuration, health, last poll, and durable revisions |
| `strand review-logs --mr <iid> --limit <1..100>` | Stream recent persisted activity as chronological JSONL |

## Review lifecycle

A revision is admitted only when the current head pipeline succeeds for the exact
source SHA. Waiting or failed pipelines consume no review slot. Each repository,
GitLab project, MR IID, and head SHA combination is reviewed once.

The coordinator resolves the current `glab` user's stable GitLab ID on each poll.
Eligible MRs that explicitly list that user as a requested reviewer are checked
first and admitted in addition to `:max-active-reviews`; they neither consume nor
wait for an ordinary slot. Draft, label, exact-head pipeline, and duplicate-review
gates still apply. Existing requested reviews likewise do not reduce ordinary
capacity.

On each poll, the coordinator also reconciles settled local reviews with
GitLab. When a review is `reviewed` or `failed` and its MR has since been closed
or merged, the coordinator automatically runs teardown, records a local `done`
decision, closes the review, and releases its capacity slot before admitting new
work. A teardown failure leaves the review open and fails the poll visibly so a
later poll can retry the idempotent hook. Reviews still executing are retained
until their reviewer runs settle, then reconciled by a later poll.

Reviewers receive the setup-owned worktree path and exact base/head SHAs. Before
dispatch, the coordinator verifies that `worktree_path` is an external Git
worktree root at the exact head and that both comparison trees are available.
Reviewers use Git to select relevant changes and inspect surrounding source,
callers, and tests. The coordinator does not place a full patch in the request.

Reviewer completion persists first-class attributed comment records and derives
the readable report from them. `reviewed` means the runs completed successfully;
it is not approval. Failures remain visible as `failed` reviews for local
assessment. Both states keep their slot until `review finish` records a decision.

Completing, pausing, or reconciling reviewer passes does not run teardown. The
review record, branch, worktree, and setup log remain available while the result
awaits action or is revisited. Only `review finish` runs teardown. Successful
teardown is durably marked before the review is closed, so a failed decision
mutation can retry without repeating known-complete cleanup.
