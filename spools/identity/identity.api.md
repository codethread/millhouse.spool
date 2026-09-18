
-----
# <a name="millhouse.spools.identity">millhouse.spools.identity</a>


Logical native-session identities and optional run provenance.




## <a name="millhouse.spools.identity/attach!">`attach!`</a>
``` clojure
(attach! runtime request)
```
Function.

Attach a reserved identity to one actual native session exactly once.

  `:reservation-id` is the capability returned by `reserve!`. Replaying the same
  attachment converges; another harness or native session fails before writes.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L245-L253">Source</a></sub></p>

## <a name="millhouse.spools.identity/bind!">`bind!`</a>
``` clojure
(bind! runtime {:keys [harness native-session-id expected-identity], :as request})
```
Function.

Compatibility binding for existing managed and maintenance providers.

  Mint/recovery and `:run-id` provenance retain the historical result shape.
  `:expected-identity` remains an assertion: without an existing native binding,
  or on mismatch, it fails before minting or adding edges.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L346-L373">Source</a></sub></p>

## <a name="millhouse.spools.identity/codex-child-session-id">`codex-child-session-id`</a>
``` clojure
(codex-child-session-id parent-session-id agent-id)
```
Function.

Return the collision-safe native key for a Codex `(session_id, agent_id)` child.

  Pi callers do not transform IDs: they pass the child's actual native session
  ID directly to `startup!`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L375-L387">Source</a></sub></p>

## <a name="millhouse.spools.identity/current">`current`</a>
``` clojure
(current runtime friendly-id)
```
Function.

Resolve an existing identity by friendly ID, failing when absent or ambiguous.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L89-L96">Source</a></sub></p>

## <a name="millhouse.spools.identity/identity">`identity`</a>
``` clojure
(identity #:op{:keys [runtime args]})
```
Function.

Dispatch `strand identity` operations.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L555-L568">Source</a></sub></p>

## <a name="millhouse.spools.identity/identity?">`identity?`</a>
``` clojure
(identity? strand)
```
Function.

Return true when `strand` is an identity record.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L68-L71">Source</a></sub></p>

## <a name="millhouse.spools.identity/receive!">`receive!`</a>
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
  local. Conflicting names, sessions or origin pointers fail without writes.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L443-L465">Source</a></sub></p>

## <a name="millhouse.spools.identity/register!">`register!`</a>
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

  `by-identity`, when supplied, must resolve in the origin. Returns `:identity`,
  destination `:strand-id`, and `:result` (`registered` or `existing`). Exact
  replay makes no changes; conflicting bindings/provenance fail. Transport errors
  propagate without automatic retry. `identity/origin-workspace` is the durable
  lookup pointer, so an origin Weaver restart does not change the descriptor.
  Registration does not start, restart or reconfigure Weavers.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L467-L500">Source</a></sub></p>

## <a name="millhouse.spools.identity/reserve!">`reserve!`</a>
``` clojure
(reserve! runtime {:keys [harness model thinking-level], :as request})
```
Function.

Mint an unattached identity reservation for an optional managed caller.

  The returned opaque `:reservation-id` is required to attach the identity; the
  friendly name alone never authorizes attachment.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L316-L344">Source</a></sub></p>

## <a name="millhouse.spools.identity/startup!">`startup!`</a>
``` clojure
(startup! runtime request)
```
Function.

Resolve identity at native startup without launcher state.

  Requires only `:harness` and the actual `:native-session-id`. An optional
  `:identity` must already name this exact binding. An optional reservation
  attaches through the managed compatibility path. Parent and run targets are
  validated before the transactional identity/provenance write.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L304-L314">Source</a></sub></p>
