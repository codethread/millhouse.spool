
-----
# <a name="millhouse.spools.auto-review">millhouse.spools.auto-review</a>


Own the optional GitLab review runtime and its public Millstrand operations.

  This namespace validates consumer configuration and coordinates the serial
  worker, scheduler wakes, MR admission, reviewer dispatch, crash recovery, and
  report settlement. Persistence, external processes, user projections, and
  activity logs stay in the focused millhouse.spools.auto-review.* namespaces.




## <a name="millhouse.spools.auto-review/close!">`close!`</a>
``` clojure
(close! {:keys [runtime]})
```
Function.

Cancel this spool's wake and stop its worker; agent custody stays with Harnesses.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L414-L435">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/dispatch!">`dispatch!`</a>
``` clojure
(dispatch! rt card)
```
Function.

Replay frozen agent CLI requests safely after a crash or partial dispatch.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L106-L128">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/on-agent-completion">`on-agent-completion`</a>
``` clojure
(on-agent-completion event)
```
Function.

Reconcile reviewer completion after the Harnesses run mutation commits.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L479-L487">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/open!">`open!`</a>
``` clojure
(open! {:keys [runtime]} config)
```
Function.

Open from a consumer-owned lifecycle resource. Polling defaults to disabled.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L395-L412">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/passing-revisions">`passing-revisions`</a>
``` clojure
(passing-revisions config candidates capacity)
(passing-revisions config candidates capacity observe)
```
Function.

Fill available slots from passing MRs without letting waiting CI occupy one.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L200-L220">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/poll-once!">`poll-once!`</a>
``` clojure
(poll-once! rt config)
```
Function.

Poll once on the owned worker; persisted revision keys prevent repeat reviews.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L242-L307">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/prune-wake!">`prune-wake!`</a>
``` clojure
(prune-wake! {:keys [runtime]})
```
Function.

Handle the daily review-log pruning scheduler wake.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L386-L393">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/request!">`request!`</a>
``` clojure
(request! rt job)
```
Function.

Coalesce work off the shared event lane; never wait for GitLab or agents there.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L351-L362">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/review">`review`</a>
``` clojure
(review #:op{:keys [runtime args]})
```
Function.

Inspect, curate, and explicitly publish frozen GitLab MR reviews.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L491-L500">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/review-logs">`review-logs`</a>
``` clojure
(review-logs #:op{:keys [runtime args emit!]})
```
Function.

Read recent persisted MR review activity as JSONL, oldest first. See review status for the linked log root.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L565-L573">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/settle!">`settle!`</a>
``` clojure
(settle! rt)
```
Function.

Settle completed runs into a durable structured snapshot and derived report.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L167-L194">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/validate-config">`validate-config`</a>
``` clojure
(validate-config config)
```
Function.

Require an explicit repository, workspace lifecycle hooks, and reviewer roster.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L36-L60">Source</a></sub></p>

## <a name="millhouse.spools.auto-review/wake!">`wake!`</a>
``` clojure
(wake! {:keys [runtime]})
```
Function.

Handle the recurring review-poll scheduler wake.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/auto-review/src/millhouse/spools/auto_review.clj#L371-L379">Source</a></sub></p>
