
-----
# <a name="millhouse.spools.kanban.peering">millhouse.spools.kanban.peering</a>


Opt-in board peering: the RECEIVE guild op plus the SEND-side local ops.

  A trusted-config module activates this namespace after the guild and kanban
  modules. Static forms publish the two local operations, and a named lifecycle
  resource registers the Guild receiver; the prerequisites fail loudly when
  missing:

  - `kanban.send.v1` — the guild receive op. A sibling weaver drops a card, or
    an epic bundle, onto this board. Received cards travel the same
    `millhouse.spools.kanban/add!` code path as local cards, so defaults, lanes, and
    epic `parent-of` wiring are identical; `:from` provenance is stamped as one
    `kanban/from` attribute. Guild parses the op's single JSON argument to a
    keyword-keyed map at `:guild/input`, so `::send-input` specs keyword keys
    throughout.
  - `kanban-peers` — list sibling weavers and, for each running one, whether it
    advertises `kanban.send.v1` (so a caller knows where a card can be sent).
  - `kanban-send` — resolve a local card and mirror the board tier onto a peer's
    board over `kanban.send.v1`.

  The two peering seams onto sibling weavers — enumerate/probe and invoke — go
  through `millstrand.api.peers.alpha` behind `*list-peers*`, `*list-peer-guild*`, and
  `*send-card*` so classification and payload building are testable without a
  live socket peer.




## <a name="millhouse.spools.kanban.peering/*list-peer-guild*">`*list-peer-guild*`</a>




Peer Guild-listing seam used by peering tests.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban/peering.clj#L243-L245">Source</a></sub></p>

## <a name="millhouse.spools.kanban.peering/*list-peers*">`*list-peers*`</a>




Peer-listing seam used by peering tests.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban/peering.clj#L239-L241">Source</a></sub></p>

## <a name="millhouse.spools.kanban.peering/*send-card*">`*send-card*`</a>




Peer card-send seam used by peering tests.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban/peering.clj#L247-L249">Source</a></sub></p>

## <a name="millhouse.spools.kanban.peering/close-peering!">`close-peering!`</a>
``` clojure
(close-peering! _context)
```
Function.

Close peering's module resource without claiming Guild's dispatch-table teardown.

  Guild owns receiver registration and has no per-op removal seam in this
  baseline. Its own lifecycle reset removes receivers; this close therefore
  preserves the established process-lifetime receiver behavior while static
  peering operations retract with their module owner.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban/peering.clj#L734-L742">Source</a></sub></p>

## <a name="millhouse.spools.kanban.peering/kanban-peering-receiver">`kanban-peering-receiver`</a>




Own guarded Guild receiver registration for the peering module lifetime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban/peering.clj#L744-L747">Source</a></sub></p>

## <a name="millhouse.spools.kanban.peering/kanban-peers">`kanban-peers`</a>
``` clojure
(kanban-peers ctx)
```
Function.

List sibling weavers and whether each accepts peered kanban cards.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban/peering.clj#L705-L710">Source</a></sub></p>

## <a name="millhouse.spools.kanban.peering/kanban-send">`kanban-send`</a>
``` clojure
(kanban-send ctx)
```
Function.

Send a pending or refinement card (or epic bundle) to a sibling weaver's board.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban/peering.clj#L712-L717">Source</a></sub></p>

## <a name="millhouse.spools.kanban.peering/open-peering!">`open-peering!`</a>
``` clojure
(open-peering! {:keys [runtime]})
```
Function.

Register the guarded `kanban.send.v1` receiver through Guild's supported seam.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban/peering.clj#L719-L732">Source</a></sub></p>

## <a name="millhouse.spools.kanban.peering/send-op">`send-op`</a>
``` clojure
(send-op {:guild/keys [input], :op/keys [runtime]})
```
Function.

Receive a peered card or epic bundle onto this board.

  Handles the guild op `kanban.send.v1`: `:guild/input` is the spec-validated,
  keyword-keyed JSON body. A `:card` creates a single feature; an `:epic` +
  `:features` bundle creates the epic and hangs each feature under it with a
  `parent-of` edge (same path as `kanban add --epic`), preserving input order.
  Returns JSON-safe ids only.

  A single-card payload has this shape:

  ```clojure
  {:card {:title "Investigate timeout" :priority "p2"}
   :from {:board "backend" :card "abc12"}}
  ```

  The receiver creates a fresh local id and stamps optional provenance as
  `kanban/from`; claims, tasks, notes, labels, and execution strands do not
  cross the boundary.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/kanban/src/millhouse/spools/kanban/peering.clj#L185-L214">Source</a></sub></p>
