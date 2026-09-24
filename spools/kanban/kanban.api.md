
-----
# <a name="millhouse.spools.kanban">millhouse.spools.kanban</a>


User-facing kanban board over Millstrand strands.

  Cards are the user<->agent tracking surface: everything a user asks for is a
  `feature` card (occasionally grouped under an `epic`), and every agent
  working directly with a user works under a claimed card. All card state
  lives under `kanban/*` attributes; `kanban/lane` is the active board lane
  (`refinement`, `pending`, `claimed`, `in_review`, or `in_production`) and `kanban/outcome`
  records a finished card's outcome. Lanes reflect current work and attention,
  not historical ownership or sequential stages. `pending` means Ready, including
  work blocked by dependencies; `claimed` means agent work is actually in progress,
  including agent review; `in_review` means the user needs to act. Idle work returns
  to `pending` unless it needs a human decision. The
  `kanban/priority` (p1 immediate blocker .. p4 someday, default p3) orders
  lanes and `kanban next`.

  Cards are work roots: each claim/handoff writes an immutable ownership record;
  current owner is the latest `(claimed-at, record-id)` projection, while branch
  and worktree are operational context. Execution strands hang beneath the card
  with `parent-of` edges — the kanban spool complements the engines that produce
  them, it does not replace them. Notes are closed note strands on cards and
  tasks; important user-visible notes
  belong on the epic or feature, while task notes are the development log.
  A cold agent self-discovers in-flight work with
  `kanban board` -> `kanban card <id>` -> the doing-task and its
  `latest-note`.




## <a name="millhouse.spools.kanban/add!">`add!`</a>
``` clojure
(add! runtime title flags)
```
Function.

Create a kanban card in the pending (or refinement) lane.

  `--type epic` creates a grouping epic; `--epic <id>` hangs a new feature
  under an existing epic with a parent-of edge.

  ```clojure
  (add! runtime "Investigate the timeout"
        {"--lane" "refinement"
         "--priority" "p2"
         "--label" ["reliability"]})
  ```

  A refinement card stays out of `next` until explicitly moved to pending with `strand update`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L310-L336">Source</a></sub></p>

## <a name="millhouse.spools.kanban/board">`board`</a>
``` clojure
(board runtime)
(board runtime labels)
(board runtime labels all?)
```
Function.

Return the grouped board snapshot: epics, feature lanes, closed count.

  Claimed and in-review cards carry their doing-task so a cold agent can see in
  one call who is working where and how to pick up interrupted work.
  `:needs-review` aggregates the human-review frontier across claimed and
  in-review cards.

  `labels` scopes the whole snapshot — lanes, epics, review frontier, and the
  closed count alike — to cards carrying every listed label, so a filtered board
  reads as a board rather than a lane list with a mismatched tally. A feature
  whose epic is filtered out keeps its lane entry and loses only the `:epic`
  annotation.

  `all?` adds `:cards`, a compact all-state card collection with direct epic
  membership. The ordinary grouped active snapshot remains unchanged.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1342-L1406">Source</a></sub></p>

## <a name="millhouse.spools.kanban/board-str">`board-str`</a>
``` clojure
(board-str {:keys [epics refinement pending claimed in_review in_production needs-review closed unknown-lane]})
```
Function.

Render a `board` result map as a stacked-lane ASCII board string.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1461-L1482">Source</a></sub></p>

## <a name="millhouse.spools.kanban/card-view">`card-view`</a>
``` clojure
(card-view runtime id)
```
Function.

Return one card joined to its notes, tasks, work, and frontier.

  This is the resume entry point: everything an agent needs to continue a
  card lives here. `:tasks` projects the feature card's child tasks with the
  four derived statuses (empty for cards that carry no task tier).

  ```clojure
  (card-view runtime "abc12")
  ;; => {:card ..., :tasks ..., :notes ..., :active-work ...,
  ;;     :ready ..., :related ...}
  ```
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1137-L1163">Source</a></sub></p>

## <a name="millhouse.spools.kanban/claim!">`claim!`</a>
``` clojure
(claim! runtime id flags)
```
Function.

Claim or hand off an active feature or task with durable ordered history.

  `--owner` is the role identity. `--by-identity` optionally records a distinct
  actor; when actor and owner are equal only the owner role is stored. Features
  require `--branch`; tasks are direct claims and do not. A changed owner on an
  already claimed target is an explicit handoff/reclaim. Repeating the exact
  current-owner request is idempotent and performs no write; a same-owner request
  with different context fails rather than pretending it is either a retry or a
  new action.

  The claim record and feature lane/context transition commit in one batch.
  `kanban/run-id`, when supplied, is context on that record only and is never an
  ownership authority. Current owner is the final `(claimed-at, record-id)`
  projection, not the target's legacy scalar `owner` attribute.

  ```sh
  strand kanban claim abc12 --owner worker --by-identity dispatcher \
    --branch feature-timeouts --worktree /work/feature-timeouts
  ```
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L512-L600">Source</a></sub></p>

## <a name="millhouse.spools.kanban/close-kanban!">`close-kanban!`</a>
``` clojure
(close-kanban! {:keys [runtime]})
```
Function.

Remove Kanban's ownership guard without retracting durable graph state.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L2004-L2008">Source</a></sub></p>

## <a name="millhouse.spools.kanban/current-ownership">`current-ownership`</a>
``` clojure
(current-ownership rt target-id)
```
Function.

Return the latest explicit claim for target-id, or nil when never claimed.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L255-L258">Source</a></sub></p>

## <a name="millhouse.spools.kanban/finish!">`finish!`</a>
``` clojure
(finish! runtime id flags)
```
Function.

Close a kanban card with an explicit outcome, polymorphic on `kanban/type`.

  A feature card closes from claimed, in_review, or optional in_production (`--outcome` defaults
  to done). A grouping epic is never claimed, so it closes from the refinement or
  pending lane. Finishing either tier cascade-closes its open children with
  `kanban/outcome=unactioned` and `kanban/closed-by=parent-cascade`, distinguishing
  them from manually completed work. An abandoned epic also records each
  transitioned card's lane in
  `kanban/abandon-restore-lane` so `kanban reopen` can reverse exactly what the
  abandon closed.

  ```sh
  strand kanban finish abc12 --outcome done
  strand kanban finish ep789 --outcome abandoned
  strand kanban reopen ep789
  ```

  Reopen is paired with abandon only; a completed epic remains closed.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L698-L725">Source</a></sub></p>

## <a name="millhouse.spools.kanban/identity-work">`identity-work`</a>
``` clojure
(identity-work rt friendly-id)
```
Function.

Project an identity's durable Kanban participation hierarchy.

  Returns historical direct claim targets, inherited tasks, containing epics,
  reporter cards, and every matching claim record. This helper expands the
  one-edge `kanban-identity-work` named query without consulting scalar `owner`.
  Closed strands remain present; callers choose their own active frontier.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1174-L1221">Source</a></sub></p>

## <a name="millhouse.spools.kanban/immutable-ownership-record-guard!">`immutable-ownership-record-guard!`</a>
``` clojure
(immutable-ownership-record-guard! ctx)
```
Function.

Reject mutation or deletion of an existing ownership source record.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1978-L1992">Source</a></sub></p>

## <a name="millhouse.spools.kanban/kanban">`kanban`</a>
``` clojure
(kanban ctx)
```
Function.

Manage the user-facing kanban work board.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1905-L1909">Source</a></sub></p>

## <a name="millhouse.spools.kanban/kanban-batch">`kanban-batch`</a>
``` clojure
(kanban-batch {:keys [input]})
```
Function.

Create pending feature cards with bodies and depends-on edges.

  Input shape: {:items [{:key "slug" :title "Title" :body "optional"
  :priority "p1|p2|p3|p4 (optional, default p3)"
  :depends-on ["sibling-key-or-existing-strand-id"]}]}. `depends-on` values matching sibling
  keys become batch-local edges; all other values are treated as durable strand
  ids and fail loudly if absent.

  ```sh
  strand weave --pattern kanban-batch --input \
    '{"items":[{"key":"design","title":"Design the board"},
               {"key":"docs","title":"Write the docs",
                "depends-on":["design"]}]}'
  ```

  The pattern validates the complete input before publishing the batch, so
  duplicate keys and missing durable dependencies fail without a partial
  backlog.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L374-L413">Source</a></sub></p>

## <a name="millhouse.spools.kanban/kanban-cards">`kanban-cards`</a>




Select every Kanban card strand.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1922-L1925">Source</a></sub></p>

## <a name="millhouse.spools.kanban/kanban-dash">`kanban-dash`</a>




Open the interactive Kanban board in the caller's terminal.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1917-L1920">Source</a></sub></p>

## <a name="millhouse.spools.kanban/kanban-epic-pending">`kanban-epic-pending`</a>




Select active pending cards hanging directly under one epic.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1934-L1941">Source</a></sub></p>

## <a name="millhouse.spools.kanban/kanban-export">`kanban-export`</a>
``` clojure
(kanban-export ctx)
```
Function.

Return a card's full parent-of subtree with its internal depends-on edges.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1911-L1915">Source</a></sub></p>

## <a name="millhouse.spools.kanban/kanban-identity-work">`kanban-identity-work`</a>




Select cards and tasks in an identity's durable participation history.

  A directly claimed feature or task remains selected after handoff when any
  linked ownership record names the identity; reporter cards are included as
  participation without implying ownership. Use `identity-work` to expand direct
  targets to inherited tasks and containing epics.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1943-L1970">Source</a></sub></p>

## <a name="millhouse.spools.kanban/kanban-pending">`kanban-pending`</a>




Select active Kanban cards in the pending lane.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1927-L1932">Source</a></sub></p>

## <a name="millhouse.spools.kanban/kanban-runtime">`kanban-runtime`</a>




Own Kanban runtime-state setup for the module lifetime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L2010-L2013">Source</a></sub></p>

## <a name="millhouse.spools.kanban/label-add!">`label-add!`</a>
``` clojure
(label-add! runtime id labels)
```
Function.

Add labels to a card, one `kanban.label/<slug>` attribute key per label.

  Adding a label a card already carries is idempotent, and labels are free-form:
  no vocabulary is registered up front, so a new label exists the moment it is
  first used.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L455-L462">Source</a></sub></p>

## <a name="millhouse.spools.kanban/label-list">`label-list`</a>
``` clojure
(label-list runtime)
```
Function.

Return every label in use on active cards with the count of cards carrying it.

  Labels have no registry of their own, so the board's own cards are the
  vocabulary: this is how an agent discovers which labels exist before reusing
  one instead of coining a near-duplicate.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1237-L1250">Source</a></sub></p>

## <a name="millhouse.spools.kanban/label-rm!">`label-rm!`</a>
``` clojure
(label-rm! runtime id labels)
```
Function.

Remove labels from a card by deleting their attribute keys.

  Removing a label a card does not carry is a no-op, so an unlabel is safe to
  repeat without first reading the card.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L464-L470">Source</a></sub></p>

## <a name="millhouse.spools.kanban/next-card">`next-card`</a>
``` clojure
(next-card runtime)
(next-card runtime labels)
(next-card runtime labels epic-id)
```
Function.

Return the highest-priority (p1 first) oldest active pending feature card, or nil.

  `labels` narrows the queue to cards carrying every listed label, so an agent
  working one axis pulls the next card on that axis rather than the next card
  overall. `epic-id` narrows to one epic's direct features — the pick-up read
  for a loop working a single epic — and fails loudly when the id does not
  name an epic card.

  ```clojure
  (next-card runtime ["reliability"])
  (next-card runtime nil "ep789")
  ```
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1275-L1301">Source</a></sub></p>

## <a name="millhouse.spools.kanban/note!">`note!`</a>
``` clojure
(note! runtime id text flags)
```
Function.

Append a note to a card or task via the blessed notes relation.

  The note rides the shared `notes` edge (`millstrand.api.notes.alpha/note!`) with
  optional canonical `identity/by-identity` attribution and the kanban-owned
  `note/kind` view hint, so concurrent agents never race a read-merge-write cycle.
  Every note keeps its own timestamp and attribution. Note the doing-task as you
  go — that is what `kanban card <id>` surfaces as each task's `:latest-note` —
  and keep important user-visible notes on the epic or feature, not only in the
  task's development log. `--kind` stamps the open `note/kind` view hint (blessed
  values: activity, decision, review-dump, summary). A task note reports its
  owning card alongside the task when one parents it.

  ```sh
  strand kanban note task01 "Parser is green; review next" \
    --by-identity claude --kind activity
  strand --stdin kanban note task01 :stdin --by-identity claude --kind review-dump <<'NOTE'
  Review findings and command output belong on the task, not the card.
  NOTE
  ```
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L998-L1036">Source</a></sub></p>

## <a name="millhouse.spools.kanban/open-kanban!">`open-kanban!`</a>
``` clojure
(open-kanban! {:keys [runtime]})
```
Function.

Materialize Kanban state and protect immutable ownership source records.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1994-L2002">Source</a></sub></p>

## <a name="millhouse.spools.kanban/ownership-history">`ownership-history`</a>
``` clojure
(ownership-history rt target-id)
```
Function.

Return a target's immutable ownership claims in deterministic order.

  Claims sort by `kanban/claimed-at`, then durable record id. `:order` is the
  one-based position in that order; the final claim is current, including when
  its raw owner cannot yet resolve to an identity strand.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L226-L253">Source</a></sub></p>

## <a name="millhouse.spools.kanban/print-board!">`print-board!`</a>
``` clojure
(print-board! runtime)
```
Function.

Print the live board as ASCII; the human view for `mill weaver repl`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L1484-L1487">Source</a></sub></p>

## <a name="millhouse.spools.kanban/reopen!">`reopen!`</a>
``` clojure
(reopen! runtime id)
```
Function.

Reopen an abandoned epic, reversing exactly the cascade a matching abandon closed.

  The inverse of abandon only: the epic must be a closed epic with
  `kanban/outcome=abandoned`; a done epic (or any non-abandoned card) is refused,
  because reopen pairs with abandon, not complete. The epic returns to its stored
  `kanban/abandon-restore-lane` (state active, outcome and marker cleared). Each
  direct feature child that is closed *and* carries the marker is reopened to its
  own stored restore lane; a child closed before the abandon (no marker) was
  legitimately done and stays closed. Reopen is a true inverse, never a blanket
  reopen.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L741-L786">Source</a></sub></p>

## <a name="millhouse.spools.kanban/reporter">`reporter`</a>
``` clojure
(reporter rt card)
```
Function.

Project a card's durable reporter evidence and current graph enrichment.

  Returns nil for anonymous cards. The raw friendly identity remains present
  even while `:identity-strand-ids` is empty because resolution is best effort.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L212-L220">Source</a></sub></p>

## <a name="millhouse.spools.kanban/set-priority!">`set-priority!`</a>
``` clojure
(set-priority! runtime id priority)
```
Function.

Set an active card's priority (p1 highest urgency .. p4 someday).
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L431-L441">Source</a></sub></p>

## <a name="millhouse.spools.kanban/task-add!">`task-add!`</a>
``` clojure
(task-add! runtime feature-id title flags)
```
Function.

Create a task strand under a feature card via a `parent-of` edge.

  `--depends-on <id>` is repeatable and lays the same `depends-on` edges that
  are the concurrency DAG and drive the derived `blocked`/`ready` split; task
  status is never stored.

  ```sh
  strand kanban task add abc12 "Implement the parser"
  strand kanban task add abc12 "Document the parser" --depends-on task01
  ```
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L931-L955">Source</a></sub></p>

## <a name="millhouse.spools.kanban/task-list">`task-list`</a>
``` clojure
(task-list runtime feature-id)
```
Function.

Project a feature card's tasks with their derived statuses.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L957-L963">Source</a></sub></p>

## <a name="millhouse.spools.kanban/task-op">`task-op`</a>
``` clojure
(task-op runtime {:keys [feature title subcommand]} flags)
```
Function.

Dispatch a parsed `kanban task ...` action, failing loudly on an unknown one.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L965-L972">Source</a></sub></p>

## <a name="millhouse.spools.kanban/task-ownership">`task-ownership`</a>
``` clojure
(task-ownership rt task)
```
Function.

Project direct task ownership or inherited current feature ownership.

  A direct claim wins. Inheritance is a read projection only: it does not create
  a task claim record and does not make every task appear actively `doing`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban.clj#L865-L877">Source</a></sub></p>
