
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
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L242-L250">Source</a></sub></p>

## <a name="millhouse.spools.identity/bind!">`bind!`</a>
``` clojure
(bind! runtime {:keys [harness native-session-id expected-identity], :as request})
```
Function.

Compatibility binding for existing managed and maintenance providers.

  Mint/recovery and `:run-id` provenance retain the historical result shape.
  `:expected-identity` remains an assertion: without an existing native binding,
  or on mismatch, it fails before minting or adding edges.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L343-L370">Source</a></sub></p>

## <a name="millhouse.spools.identity/codex-child-session-id">`codex-child-session-id`</a>
``` clojure
(codex-child-session-id parent-session-id agent-id)
```
Function.

Return the collision-safe native key for a Codex `(session_id, agent_id)` child.

  Pi callers do not transform IDs: they pass the child's actual native session
  ID directly to `startup!`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L372-L384">Source</a></sub></p>

## <a name="millhouse.spools.identity/current">`current`</a>
``` clojure
(current runtime friendly-id)
```
Function.

Resolve an existing identity by friendly ID, failing when absent or ambiguous.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L87-L94">Source</a></sub></p>

## <a name="millhouse.spools.identity/identity">`identity`</a>
``` clojure
(identity #:op{:keys [runtime args]})
```
Function.

Dispatch `strand identity` operations.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L430-L441">Source</a></sub></p>

## <a name="millhouse.spools.identity/identity?">`identity?`</a>
``` clojure
(identity? strand)
```
Function.

Return true when `strand` is an identity record.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L66-L69">Source</a></sub></p>

## <a name="millhouse.spools.identity/reserve!">`reserve!`</a>
``` clojure
(reserve! runtime {:keys [harness model thinking-level], :as request})
```
Function.

Mint an unattached identity reservation for an optional managed caller.

  The returned opaque `:reservation-id` is required to attach the identity; the
  friendly name alone never authorizes attachment.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L313-L341">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/identity/src/millhouse/spools/identity.clj#L301-L311">Source</a></sub></p>
