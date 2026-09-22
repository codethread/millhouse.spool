
-----
# <a name="millhouse.spools.auto-run">millhouse.spools.auto-run</a>


Dispatch opted-in, ready Kanban features into repository-owned workflows.

  One assignment owns a card's delivery. This module only admits work; it never
  advances lanes, retries workers, interprets results, or approves a merge.




## <a name="millhouse.spools.auto-run/auto-run">`auto-run`</a>
``` clojure
(auto-run #:op{:keys [runtime args]})
```
Function.

Inspect or explicitly scan the repository's automatic card dispatcher.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run.clj#L375-L397">Source</a></sub></p>

## <a name="millhouse.spools.auto-run/auto-run-workflow">`auto-run-workflow`</a>



<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run.clj#L51-L60">Source</a></sub></p>

## <a name="millhouse.spools.auto-run/classify">`classify`</a>
``` clojure
(classify collected)
```
Function.

Classify already-collected delivery evidence without mutation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run.clj#L348-L351">Source</a></sub></p>

## <a name="millhouse.spools.auto-run/configure!">`configure!`</a>
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
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run.clj#L78-L121">Source</a></sub></p>

## <a name="millhouse.spools.auto-run/eligible?">`eligible?`</a>
``` clojure
(eligible? rt card)
```
Function.

Return whether a graph-ready strand permits a first automatic assignment.

  Readiness itself belongs to Weaver. This predicate checks card state, opt-in,
  authoritative current ownership, and previous dispatch receipts. Reporter,
  actor, and other participation history do not make an unclaimed card owned.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run.clj#L132-L146">Source</a></sub></p>

## <a name="millhouse.spools.auto-run/explain">`explain`</a>
``` clojure
(explain rt card-id)
```
Function.

Return one bounded, read-only delivery explanation for `card-id`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run.clj#L353-L359">Source</a></sub></p>

## <a name="millhouse.spools.auto-run/scan!">`scan!`</a>
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
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run.clj#L305-L336">Source</a></sub></p>

## <a name="millhouse.spools.auto-run/status">`status`</a>
``` clojure
(status rt)
```
Function.

Return configuration and durable card receipts without inferring completion.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run.clj#L361-L373">Source</a></sub></p>

## <a name="millhouse.spools.auto-run/stop!">`stop!`</a>
``` clojure
(stop! rt)
```
Function.

Disable admission and cancel its wake without touching any worker.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run.clj#L123-L130">Source</a></sub></p>

## <a name="millhouse.spools.auto-run/wake!">`wake!`</a>
``` clojure
(wake! {:keys [runtime payload]})
```
Function.

Rearm and scan one durable wake, ignoring an obsolete configuration.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run.clj#L338-L346">Source</a></sub></p>

-----
# <a name="millhouse.spools.auto-run-explain">millhouse.spools.auto-run-explain</a>


Collect and classify read-only evidence for one auto-run delivery.




## <a name="millhouse.spools.auto-run-explain/classify">`classify`</a>
``` clojure
(classify {:keys [card admission agents workflow land agent-blocker], :as collected})
```
Function.

Classify collected delivery evidence without proposing a mutation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run_explain.clj#L170-L230">Source</a></sub></p>

## <a name="millhouse.spools.auto-run-explain/explain">`explain`</a>
``` clojure
(explain rt config card-id)
```
Function.

Return one bounded, read-only delivery explanation for feature `card-id`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run_explain.clj#L232-L294">Source</a></sub></p>

-----
# <a name="millhouse.spools.auto-run-land">millhouse.spools.auto-run-land</a>


Optional two-role landing handoff for autorun delivery workflows.




## <a name="millhouse.spools.auto-run-land/autonomous-land">`autonomous-land`</a>




Review and hand off to a distinct, initially blocked canonical-root finisher.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run_land.clj#L155-L168">Source</a></sub></p>

## <a name="millhouse.spools.auto-run-land/failure-policy">`failure-policy`</a>
``` clojure
(failure-policy card)
```
Function.

Render the full-land workflow's stop and custody rules.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run_land.clj#L12-L24">Source</a></sub></p>

-----
# <a name="millhouse.spools.auto-run-reporting">millhouse.spools.auto-run-reporting</a>


Publish agent blockers with evidence references through validated patterns.




## <a name="millhouse.spools.auto-run-reporting/auto-run-needs-decision">`auto-run-needs-decision`</a>
``` clojure
(auto-run-needs-decision {:keys [input]})
```
Function.

Report that the agent needs a decision, then end the run.

  Input: strand (work strand ID), evidence (existing evidence strand ID).
  Save the question and context on that evidence strand before applying this
  pattern. The complete blocker state is published atomically.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run_reporting.clj#L35-L43">Source</a></sub></p>

## <a name="millhouse.spools.auto-run-reporting/auto-run-unblock">`auto-run-unblock`</a>
``` clojure
(auto-run-unblock {:keys [input]})
```
Function.

Clear the agent blocker without starting or resuming work.

  Input: strand (work strand ID). Remove all three blocker attributes together;
  retain the referenced evidence strand.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run_reporting.clj#L55-L66">Source</a></sub></p>

## <a name="millhouse.spools.auto-run-reporting/auto-run-unknown-failure">`auto-run-unknown-failure`</a>
``` clojure
(auto-run-unknown-failure {:keys [input]})
```
Function.

Report a problem the agent cannot resolve, then end the run.

  Input: strand (work strand ID), evidence (existing evidence strand ID).
  Save the investigation and supporting evidence before applying this pattern.
  The complete blocker state is published atomically.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run_reporting.clj#L45-L53">Source</a></sub></p>

## <a name="millhouse.spools.auto-run-reporting/derive-labels">`derive-labels`</a>
``` clojure
(derive-labels {:keys [hook/value]})
```
Function.

Derive board labels atomically from a complete agent-blocker update.

  Explicit selection enables the hook. An omitted blocker is unchanged;
  a blocker update must carry all three attributes, including nil on removal.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run_reporting.clj#L68-L86">Source</a></sub></p>

## <a name="millhouse.spools.auto-run-reporting/read-blocker">`read-blocker`</a>
``` clojure
(read-blocker rt strand)
```
Function.

Read the agent-reported blocker and resolve its evidence strand summary.

  Return an unblocked state or the blocked status with evidence ID and title.
  Missing evidence or an incomplete union fails visibly.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run_reporting.clj#L88-L106">Source</a></sub></p>

-----
# <a name="millhouse.spools.auto-run-worktree">millhouse.spools.auto-run-worktree</a>


Optional wktree preparation recipe for the auto-run dispatcher.




## <a name="millhouse.spools.auto-run-worktree/prepare!">`prepare!`</a>
``` clojure
(prepare! _rt {:keys [repo card]})
```
Function.

Create auto/<card-id> using repository wktree policy and run its setup script.

  Existing branches and blocked allocations fail visibly; the dispatcher never
  retries this side effect. Repositories may supply their own callback instead.
  No card is claimed and no fallback to the canonical checkout is permitted.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run_worktree.clj#L29-L44">Source</a></sub></p>

## <a name="millhouse.spools.auto-run-worktree/ready-result">`ready-result`</a>
``` clojure
(ready-result text branch)
```
Function.

Parse wktree's ready result, rejecting blocked allocation and wrong branches.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-run/src/millhouse/spools/auto_run_worktree.clj#L9-L20">Source</a></sub></p>
