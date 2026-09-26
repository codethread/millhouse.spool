
-----
# <a name="millhouse.auto-run">millhouse.auto-run</a>


Dispatch opted-in, ready Kanban features into repository-owned workflows.

  One assignment owns a card's delivery. This module only admits work; it never
  advances lanes, retries workers, interprets results, or approves a merge.




## <a name="millhouse.auto-run/auto-run">`auto-run`</a>
``` clojure
(auto-run #:op{:keys [runtime args]})
```
Function.

Inspect or explicitly scan the repository's automatic card dispatcher.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run.clj#L376-L410">Source</a></sub></p>

## <a name="millhouse.auto-run/auto-run-workflow">`auto-run-workflow`</a>



<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run.clj#L52-L61">Source</a></sub></p>

## <a name="millhouse.auto-run/classify">`classify`</a>
``` clojure
(classify collected)
```
Function.

Classify already-collected delivery evidence without mutation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run.clj#L349-L352">Source</a></sub></p>

## <a name="millhouse.auto-run/configure!">`configure!`</a>
``` clojure
(configure! rt config)
```
Function.

Enable repository-owned dispatch from a lifecycle resource's open hook.

  Required config names the canonical repo, default seat/effort/workflow,
  allowed workflow names, qualified preparation callback, concurrency, cadence,
  and enabled flag. Preparation receives runtime and {:repo ... :card ...}, and
  returns {:cwd ... :branch ...}. It must not claim the card.

  Optional :start-params names a qualified callback. It receives runtime and
  {:repo ... :card ... :settings ... :prepared {:cwd ... :branch ...}}, then
  returns additional workflow start parameters. It must return a map and cannot
  replace :card (the card ID string), :feature, :worktree, :branch, :seat, or
  :effort.

  Invalid configuration fails activation. Disabling prevents new admission;
  it never stops existing workers. Reconfiguration is serialized with scans.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run.clj#L79-L122">Source</a></sub></p>

## <a name="millhouse.auto-run/eligible?">`eligible?`</a>
``` clojure
(eligible? rt card)
```
Function.

Return whether a graph-ready strand permits a first automatic assignment.

  Readiness itself belongs to Weaver. This predicate checks card state, opt-in,
  authoritative current ownership, and previous dispatch receipts. Reporter,
  actor, and other participation history do not make an unclaimed card owned.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run.clj#L133-L147">Source</a></sub></p>

## <a name="millhouse.auto-run/explain">`explain`</a>
``` clojure
(explain rt card-id)
```
Function.

Return one bounded, read-only delivery explanation for `card-id`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run.clj#L354-L360">Source</a></sub></p>

## <a name="millhouse.auto-run/scan!">`scan!`</a>
``` clojure
(scan! rt)
(scan! rt by-identity)
```
Function.

Admit ready cards up to repository capacity, returning dispatch receipts.

  Scheduled and manual scans serialize on runtime-owned state. A manual caller
  may supply its friendly identity for best-effort assignment attribution;
  scheduler-driven scans supply none and never fabricate one. Errors stay on
  their card and are never retried by another scan. Accepted assignments remain
  assigned after process exit; moving a card or toggling its label cannot rearm
  it. Use explicit Harnesses continuation for subsequent work.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run.clj#L306-L337">Source</a></sub></p>

## <a name="millhouse.auto-run/status">`status`</a>
``` clojure
(status rt)
```
Function.

Return configuration and durable card receipts without inferring completion.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run.clj#L362-L374">Source</a></sub></p>

## <a name="millhouse.auto-run/stop!">`stop!`</a>
``` clojure
(stop! rt)
```
Function.

Disable admission and cancel its wake without touching any worker.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run.clj#L124-L131">Source</a></sub></p>

## <a name="millhouse.auto-run/wake!">`wake!`</a>
``` clojure
(wake! {:keys [runtime payload]})
```
Function.

Rearm and scan one durable wake, ignoring an obsolete configuration.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run.clj#L339-L347">Source</a></sub></p>

-----
# <a name="millhouse.auto-run-explain">millhouse.auto-run-explain</a>


Collect and classify read-only evidence for one auto-run delivery.




## <a name="millhouse.auto-run-explain/classify">`classify`</a>
``` clojure
(classify {:keys [card admission agents workflow land agent-blocker], :as collected})
```
Function.

Classify collected delivery evidence without proposing a mutation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_explain.clj#L170-L230">Source</a></sub></p>

## <a name="millhouse.auto-run-explain/explain">`explain`</a>
``` clojure
(explain rt config card-id)
```
Function.

Return one bounded, read-only delivery explanation for feature `card-id`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_explain.clj#L232-L294">Source</a></sub></p>

-----
# <a name="millhouse.auto-run-land">millhouse.auto-run-land</a>


Recorded autonomous landing phases with one persistent finisher target.




## <a name="millhouse.auto-run-land/autonomous-land">`autonomous-land`</a>




Review, freeze and release one independent finisher through recorded phases.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_land.clj#L85-L292">Source</a></sub></p>

## <a name="millhouse.auto-run-land/failure-policy">`failure-policy`</a>
``` clojure
(failure-policy card)
```
Function.

Render the handoff and landing stop rules, after delivery validation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_land.clj#L12-L26">Source</a></sub></p>

## <a name="millhouse.auto-run-land/validation-failure-policy">`validation-failure-policy`</a>
``` clojure
(validation-failure-policy card)
```
Function.

Render agent repair authority for implementation and pre-review validation.

  Diagnosis belongs to the assigned agent. This guidance grants scoped repair,
  not permission to bypass executor results or handoff and landing custody.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_land.clj#L28-L69">Source</a></sub></p>

-----
# <a name="millhouse.auto-run-recovery">millhouse.auto-run-recovery</a>


Register an authorized, already accepted delivery-worker continuation.




## <a name="millhouse.auto-run-recovery/register-worker!">`register-worker!`</a>
``` clojure
(register-worker! rt request)
```
Function.

Register an accepted current worker after explicit coordinator authorization.

  Require the expected prior receipt, a unique published continuation path,
  every predecessor settled, unchanged task/root/worktree and no frozen handoff.
  Exact request replay is a no-op, including after subsequent finisher acceptance.
  Persist the reason and actor with the new receipt in one card update. This does
  not launch, retry, approve, claim, clear blockers or mutate Harnesses lineage.

  Requires Harnesses' public call-with-run-publication-lock boundary. It serializes
  these checks with run publication, not arbitrary raw graph edits. Actor and
  reason record provenance; callers must obtain real recovery authorization.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_recovery.clj#L154-L196">Source</a></sub></p>

## <a name="millhouse.auto-run-recovery/verify-worker">`verify-worker`</a>
``` clojure
(verify-worker {:keys [card]})
```
Function.

Code-executor callback recording verified settlement evidence.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_recovery.clj#L149-L152">Source</a></sub></p>

## <a name="millhouse.auto-run-recovery/verify-worker!">`verify-worker!`</a>
``` clojure
(verify-worker! rt card-id)
```
Function.

Verify successful current-worker settlement and the accepted finisher custody.

  Return durable run IDs for the code gate's evidence. A stopped process, stale
  worker receipt, wrong finisher target or changed canonical cwd fails loudly.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_recovery.clj#L111-L147">Source</a></sub></p>

-----
# <a name="millhouse.auto-run-reporting">millhouse.auto-run-reporting</a>


Publish agent blockers with evidence references through validated patterns.




## <a name="millhouse.auto-run-reporting/auto-run-needs-decision">`auto-run-needs-decision`</a>
``` clojure
(auto-run-needs-decision {:keys [input]})
```
Function.

Report that the agent needs a decision, then end the run.

  Input: strand (work strand ID), evidence (existing evidence strand ID).
  Save the question and context on that evidence strand before applying this
  pattern. The complete blocker state is published atomically.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_reporting.clj#L35-L43">Source</a></sub></p>

## <a name="millhouse.auto-run-reporting/auto-run-unblock">`auto-run-unblock`</a>
``` clojure
(auto-run-unblock {:keys [input]})
```
Function.

Clear the agent blocker without starting or resuming work.

  Input: strand (work strand ID). Remove all three blocker attributes together;
  retain the referenced evidence strand.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_reporting.clj#L55-L66">Source</a></sub></p>

## <a name="millhouse.auto-run-reporting/auto-run-unknown-failure">`auto-run-unknown-failure`</a>
``` clojure
(auto-run-unknown-failure {:keys [input]})
```
Function.

Report a problem the agent cannot resolve, then end the run.

  Input: strand (work strand ID), evidence (existing evidence strand ID).
  Save the investigation and supporting evidence before applying this pattern.
  The complete blocker state is published atomically.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_reporting.clj#L45-L53">Source</a></sub></p>

## <a name="millhouse.auto-run-reporting/derive-labels">`derive-labels`</a>
``` clojure
(derive-labels {:keys [hook/value]})
```
Function.

Derive board labels atomically from a complete agent-blocker update.

  Explicit selection enables the hook. An omitted blocker is unchanged;
  a blocker update must carry all three attributes, including nil on removal.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_reporting.clj#L68-L86">Source</a></sub></p>

## <a name="millhouse.auto-run-reporting/read-blocker">`read-blocker`</a>
``` clojure
(read-blocker rt strand)
```
Function.

Read the agent-reported blocker and resolve its evidence strand summary.

  Return an unblocked state or the blocked status with evidence ID and title.
  Missing evidence or an incomplete union fails visibly.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_reporting.clj#L88-L106">Source</a></sub></p>

-----
# <a name="millhouse.auto-run-worktree">millhouse.auto-run-worktree</a>


Optional wktree preparation recipe for the auto-run dispatcher.




## <a name="millhouse.auto-run-worktree/prepare!">`prepare!`</a>
``` clojure
(prepare! _rt {:keys [repo card]})
```
Function.

Create auto/<card-id> using repository wktree policy and run its setup script.

  Existing branches and blocked allocations fail visibly; the dispatcher never
  retries this side effect. Repositories may supply their own callback instead.
  No card is claimed and no fallback to the canonical checkout is permitted.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_worktree.clj#L29-L44">Source</a></sub></p>

## <a name="millhouse.auto-run-worktree/ready-result">`ready-result`</a>
``` clojure
(ready-result text branch)
```
Function.

Parse wktree's ready result, rejecting blocked allocation and wrong branches.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/auto_run_worktree.clj#L9-L20">Source</a></sub></p>
