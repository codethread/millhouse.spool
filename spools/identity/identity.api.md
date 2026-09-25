
-----
# <a name="millhouse.identity">millhouse.identity</a>


Logical native-session identities and optional run provenance.




## <a name="millhouse.identity/attach!">`attach!`</a>
``` clojure
(attach! runtime request)
```
Function.

Attach a reserved identity to one actual native session exactly once.

  `:reservation-id` is the capability returned by `reserve!`. Replaying the same
  attachment converges; another harness or native session fails before writes.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L510-L518">Source</a></sub></p>

## <a name="millhouse.identity/attribution-contributions">`attribution-contributions`</a>
``` clojure
(attribution-contributions rt)
```
Function.

Return the canonical and effective explicit attribution contributions.

  The canonical entry is always first; custom entries follow in deterministic
  key order. No attribute namespace is scanned or inferred.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L191-L205">Source</a></sub></p>

## <a name="millhouse.identity/attribution-engine">`attribution-engine`</a>




Own post-commit attribution reconciliation for the active identity module.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L357-L360">Source</a></sub></p>

## <a name="millhouse.identity/attribution-kind">`attribution-kind`</a>




Owner-partitioned registry kind for explicit spool attribution roles.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L116-L118">Source</a></sub></p>

## <a name="millhouse.identity/bind!">`bind!`</a>
``` clojure
(bind! runtime {:keys [harness native-session-id expected-identity], :as request})
```
Function.

Compatibility binding for existing managed and maintenance providers.

  Mint/recovery and `:run-id` provenance retain the historical result shape.
  `:expected-identity` remains an assertion: without an existing native binding,
  or on mismatch, it fails before minting or adding edges.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L611-L638">Source</a></sub></p>

## <a name="millhouse.identity/close-attribution-engine!">`close-attribution-engine!`</a>
``` clojure
(close-attribution-engine! {:keys [runtime resource], :as context})
```
Function.

Unregister the attribution handler. Durable evidence and edges remain.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L347-L355">Source</a></sub></p>

## <a name="millhouse.identity/codex-child-session-id">`codex-child-session-id`</a>
``` clojure
(codex-child-session-id parent-session-id agent-id)
```
Function.

Return the collision-safe native key for a Codex `(session_id, agent_id)` child.

  Pi callers do not transform IDs: they pass the child's actual native session
  ID directly to `startup!`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L640-L652">Source</a></sub></p>

## <a name="millhouse.identity/contribute-attribution!">`contribute-attribution!`</a>
``` clojure
(contribute-attribution! key attribute relation)
```
Function.

Publish one explicit spool-owned attribution role during module collection.

  `key` identifies the contribution. `attribute` is the durable raw friendly-ID
  attribute on source records, and `relation` is the role-specific edge name.
  Both are exclusive to the contribution. Outside module collection the call is
  passive, matching `runtime/collect-entry!`.

  ```clojure
  (identity/contribute-attribution!
    :kanban/reporter :kanban/reporter "reported")
  ```
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L170-L189">Source</a></sub></p>

## <a name="millhouse.identity/current">`current`</a>
``` clojure
(current runtime friendly-id)
```
Function.

Resolve an existing identity by friendly ID, failing when absent or ambiguous.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L107-L114">Source</a></sub></p>

## <a name="millhouse.identity/identity">`identity`</a>
``` clojure
(identity #:op{:keys [runtime args]})
```
Function.

Dispatch `strand identity` operations.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L848-L864">Source</a></sub></p>

## <a name="millhouse.identity/identity?">`identity?`</a>
``` clojure
(identity? strand)
```
Function.

Return true when `strand` is an identity record.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L86-L89">Source</a></sub></p>

## <a name="millhouse.identity/inspect-attributions">`inspect-attributions`</a>
``` clojure
(inspect-attributions rt)
(inspect-attributions rt source-ids)
```
Function.

Project durable identity attribution evidence and its current graph links.

  The zero-filter form scans every source carrying a configured attribute plus
  any source with a managed edge. `source-ids`, when supplied, bounds that
  projection and every id must exist. Each result always contains
  `:source-id`, `:contribution`, `:attribute`, `:relation`, raw `:identity`,
  `:status`, exact `:identity-strand-ids`, and current
  `:linked-identity-strand-ids`. Status is `:resolved`, `:unresolved`,
  `:ambiguous`, `:malformed`, or `:absent`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L219-L272">Source</a></sub></p>

## <a name="millhouse.identity/on-attribution-event">`on-attribution-event`</a>
``` clojure
(on-attribution-event _event)
```
Function.

Post-commit event handler that accelerates durable attribution convergence.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L325-L328">Source</a></sub></p>

## <a name="millhouse.identity/open-attribution-engine!">`open-attribution-engine!`</a>
``` clojure
(open-attribution-engine! {:keys [runtime], :as context})
```
Function.

Register the attribution handler and reconcile durable sources at activation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L330-L345">Source</a></sub></p>

## <a name="millhouse.identity/receive!">`receive!`</a>
``` clojure
(receive! runtime friendly-id from-weaver)
```
Function.

Receive an identity from an exact running origin Weaver ID.

  Transport counterpart to `register!`: fetches `identity show` directly from
  that peer, validates its attached native binding, then atomically creates a
  local descriptor under the identity guard. No caller-supplied descriptor is
  trusted. Copies only session/name/harness/native ID, optional model/thinking,
  and the durable origin workspace and strand ID. Edges and reservations stay
  local. Forwarding an imported descriptor preserves its original provenance.
  Conflicting names, sessions or origin pointers fail without writes.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L726-L748">Source</a></sub></p>

## <a name="millhouse.identity/reconcile-attributions!">`reconcile-attributions!`</a>
``` clojure
(reconcile-attributions! rt)
(reconcile-attributions! rt source-ids)
```
Function.

Converge attribution edges from durable source attributes.

  Exact unique local identity matches create `identity -> source` edges using
  each contribution's relation. Unknown and ambiguous names are nonfatal and
  leave no relation; stale links are removed. Malformed evidence fails before
  any write. Repeated reconciliation with a converged graph performs no write.
  The optional `source-ids` collection bounds an explicit repair. Returns exactly
  `:scanned`, `:resolved`, `:unresolved`, `:ambiguous`, `:absent`, and `:writes`;
  `:writes` counts edge mutations submitted by this call.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L296-L320">Source</a></sub></p>

## <a name="millhouse.identity/register!">`register!`</a>
``` clojure
(register! runtime friendly-id to-weaver by-identity)
```
Function.

Register an existing local identity in an exact destination Weaver ID.

  Run from the origin workspace; Strand owns cwd/workspace discovery. Select
  the destination ID from `mill weaver list`. Both Weavers must be running with
  this identity operation loaded. The destination reads back the origin binding
  before writing; same-host peer discovery is the trust boundary, not user
  authentication. No files, graph edges or reservation capabilities transfer.

  ```text
  strand identity register NAME --to-weaver WEAVER_ID --by-identity NAME
  ```

  `by-identity` must equal `NAME` and resolve in the origin. Returns `:identity`,
  destination `:strand-id`, and `:result` (`registered` or `existing`). Exact
  replay makes no changes; conflicting bindings/provenance fail. Transport errors
  propagate without automatic retry. `identity/origin-workspace` is the durable
  lookup pointer, so an origin Weaver restart does not change the descriptor.
  Registration does not start, restart or reconfigure Weavers.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L750-L787">Source</a></sub></p>

## <a name="millhouse.identity/reserve!">`reserve!`</a>
``` clojure
(reserve! runtime {:keys [harness model thinking-level], :as request})
```
Function.

Mint an unattached identity reservation for an optional managed caller.

  The returned opaque `:reservation-id` is required to attach the identity; the
  friendly name alone never authorizes attachment.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L581-L609">Source</a></sub></p>

## <a name="millhouse.identity/startup!">`startup!`</a>
``` clojure
(startup! runtime request)
```
Function.

Resolve identity at native startup without launcher state.

  Requires only `:harness` and the actual `:native-session-id`. An optional
  `:identity` must already name this exact binding. An optional reservation
  attaches through the managed compatibility path. Parent and run targets are
  validated before the transactional identity/provenance write.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L569-L579">Source</a></sub></p>

## <a name="millhouse.identity/validate-attribution-contributions!">`validate-attribution-contributions!`</a>
``` clojure
(validate-attribution-contributions! {:keys [entries], :as context})
```
Function.

Validate the effective custom attribution contribution set.

  Each attribute and relation is owned by exactly one contribution. The
  canonical `:identity/by-identity`/`attributed` pair is reserved. This function
  is public because the runtime resolves it as the registry candidate validator.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/identity.clj#L127-L148">Source</a></sub></p>
