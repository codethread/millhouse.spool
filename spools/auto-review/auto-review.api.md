
-----
# <a name="millhouse.spools.auto-review">millhouse.spools.auto-review</a>


Provider-neutral, read-only remote polling into ordinary Auto-run feature cards.

  No scheduler, worker, reviewer dispatcher, publication API or implicit recovery
  lives here. Compose poll! with Cron and the cards with Auto-run and Workflow.




## <a name="millhouse.spools.auto-review/poll!">`poll!`</a>
``` clojure
(poll! rt config)
```
Function.

Poll a provider and atomically publish each unseen passing revision as a card.

  Config requires :repo (existing local checkout), :poll (qualified callback),
  :provider-config (opaque map), :max-open (ordinary inbox limit), and :workflow
  (Auto-run workflow override). Optional :seat/:effort override Auto-run defaults.
  The callback receives runtime and {:repo canonical-path :config provider-config}
  and returns a vector conforming to ::revision. Providers filter closed/draft/
  label-ineligible requests; core admits only passed CI for the exact head.

  Requested reviews sort first, receive p1, and bypass only the ordinary inbox
  limit. Ordinary cards receive p3. ALL execution obeys Auto-run max-running.
  Open cards, including failures and human waits, retain their inbox slots.
  Closed cards remain dedup tombstones. Identity is provider/repository/request/
  head, independent of local checkout or workflow settings. Never delete receipts
  to retry; use the existing explicit continuation/blocker mechanisms.

  Calls serialize per runtime, including remote reads. Card and receipt are one
  graph add; a lost response is reconciled by the next scan's durable query, not
  by retrying a side effect. The Weaver is the single writer. Exceptions propagate
  to the caller (Cron records failures); there is no automatic local recovery.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L94-L149">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/request">`request`</a>
``` clojure
(request card)
```
Function.

Read a card's frozen provider-neutral revision, rejecting non-review cards.

  Keys are :provider, :repository (stable remote identity), :request (opaque
  provider request ID), :url, :title, :head, :base, :requested? and :ci. CI holds
  :status (passed/pending/failed/unknown), optional exact :head and :url.
  No provider-specific fields or executable instructions are accepted.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L50-L62">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/start-params">`start-params`</a>
``` clojure
(start-params _rt {:keys [card]})
```
Function.

Auto-run :start-params callback: copy :review and :review-repo from the card.

  Compose this with consumer-owned reviewer selection. The workflow context is
  poured once; subsequent provider observations never rewrite it.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L64-L70">Source</a></sub></p>

-----
# <a name="millhouse.spools.auto-review.glab">millhouse.spools.auto-review.glab</a>


GitLab's read-only adapter for the provider-neutral Auto-review contract.

  All GitLab fields end here. Only explicit GETs are issued; no comments,
  approvals, merges, worktrees or agent requests can be published by this adapter.




## <a name="millhouse.spools.auto-review.glab/poll">`poll`</a>
``` clojure
(poll _rt {:keys [repo config]})
```
Function.

Auto-review :poll callback, returning normalized open eligible revisions.

  Receives runtime (unused) and {:repo local-checkout :config {...}}. Config
  requires :host (explicit GitLab hostname) and :project (numeric target project
  ID); :bin defaults to glab and :labels to []. Authentication is glab-owned.
  Listing is paginated, requested-review identity uses the authenticated user ID,
  and detail is rechecked against the listed head before normalization. Only the
  current head_pipeline aggregate is used, never job success or pipeline history.
  Core requires its passed evidence to name the exact admitted head.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review/glab.clj#L67-L90">Source</a></sub></p>

-----
# <a name="millhouse.spools.auto-review.workflow">millhouse.spools.auto-review.workflow</a>


An inert review workflow: executor evidence, local decision, then owned cleanup.

  Select review-request explicitly after the Workflow engine and code/agent
  executors. The human decision is local only; this workflow never authorizes
  remote publication, approval or merge. Consumers can compose a different
  explicitly authorized decision policy without changing polling.




## <a name="millhouse.spools.auto-review.workflow/inspect-workspace!">`inspect-workspace!`</a>
``` clojure
(inspect-workspace! {:keys [repo head base cwd]})
```
Function.

Code-executor callback to verify frozen trees immediately before review.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review/workflow.clj#L21-L24">Source</a></sub></p>

## <a name="millhouse.spools.auto-review.workflow/review-request">`review-request`</a>




Review a frozen request using one agent gate, then await an explicit local decision.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review/workflow.clj#L36-L80">Source</a></sub></p>

-----
# <a name="millhouse.spools.auto-review.workspace">millhouse.spools.auto-review.workspace</a>


Optional exact-revision preparation for Auto-run's existing callback seam.




## <a name="millhouse.spools.auto-review.workspace/inspect!">`inspect!`</a>
``` clojure
(inspect! repo revision directory)
```
Function.

Require an external, clean Git worktree root at the frozen head and base.

  Used after consumer preparation and again before reviewer execution. Tracked
  changes are rejected; untracked dependency/build artifacts are allowed. Both
  comparison trees must exist. Returns {:cwd canonical-path :head sha :base sha}.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review/workspace.clj#L14-L38">Source</a></sub></p>

## <a name="millhouse.spools.auto-review.workspace/prepare!">`prepare!`</a>
``` clojure
(prepare! rt {:keys [repo card]})
```
Function.

Auto-run :prepare callback for an isolated exact-head review workspace.

  Fetches frozen head/base objects from origin, creates review/<card-id> at HEAD,
  allocates that existing branch through wktree, records the allocation before
  running its bootstrap, then
  validates the clean revision. Returns {:cwd ... :branch ...}; never claims.
  Consumer-specific dependency preparation can wrap this and re-run inspect!.
  Existing/blocked allocation, bootstrap or inspection failure stays visible in
  Auto-run's error receipt. No retries, fallback checkout or rollback is invented.
  Retain any allocated workspace/branch for explicit inspection and cleanup.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review/workspace.clj#L40-L79">Source</a></sub></p>
