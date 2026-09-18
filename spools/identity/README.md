# Millhouse identity spool

[API reference](./identity.api.md)

`millhouse.spools.identity` gives each native Codex or Pi session one friendly,
workspace-local identity. Native startup needs only the harness name and the
host's actual session ID. It does not require a managed run, launcher state,
`MILLSTRAND_AGENT_ID`, `MILLSTRAND_RUN_ID`, or a reservation.

Workspace routing belongs to the Strand client. Native adapters pass `--cwd`
for the session directory and pass `--workspace` only for an explicit workspace
override. Identity resolution never creates or guesses a workspace and never
starts or restarts Weaver.

## Activation

Add this root to the workspace's `deps.edn`, then activate it from trusted
startup configuration. The startup API requires the identity implementation at
`9939588e925c5a3c73608feb8182c4f52d586f64` or a subsequent release; `v4` does not
include it:

```clojure
{:deps
 {millhouse.spools/identity
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/sha "9939588e925c5a3c73608feb8182c4f52d586f64"
   :deps/root "spools/identity"}}}
```

```clojure
(runtime/module! runtime :millhouse/spools-identity
  {:ns 'millhouse.spools.identity
   :required? true})
```

## Native startup

Call the library with the actual native session ID:

```clojure
(identity/startup! runtime
  {:harness "pi"
   :native-session-id session-id
   :model "claude-sonnet"
   :thinking-level "high"
   :parent-identity parent-name
   :run-id optional-run-id})
```

Or call the Strand operation:

```text
strand identity startup pi SESSION_ID
strand identity startup codex THREAD_ID --identity EXISTING_NAME
strand identity startup codex CHILD_KEY --parent-identity PARENT_NAME
```

The first call for a `(harness, native-session-id)` pair mints one identity.
Repeat, concurrent, reload, and resume calls recover it. Resolution and minting
are serialized by a workspace-local process/file guard, and identity plus
provenance edges are committed in one transaction.

The exact CLI startup JSON shape is (library `startup!` returns the same map
without the dispatcher-added `operation` key):

```json
{
  "operation": "identity startup",
  "identity": "warm-silver-lemur",
  "strand-id": "abc12",
  "result": "minted",
  "instruction": "Your Millstrand identity is warm-silver-lemur. Use warm-silver-lemur for identity-bearing operations; pass `--by-identity warm-silver-lemur` explicitly. Do not invent another identity."
}
```

`result` is one of `minted`, `recovered`, or `attached`. The instruction is the
canonical context for native adapters. Codex supplies it as developer context;
Pi composes it into its owned effective system prompt.

`--identity NAME` is a session-scoped reference, not a rename or adoption
request. `NAME` must resolve uniquely and already be bound to the exact harness
and native session. Unknown, ambiguous, reserved, or differently bound names
fail before writes. With no explicit name, startup first recovers the native
binding and otherwise mints.

`--parent-identity NAME` adds the idempotent `parent-of` edge
`parent -> current identity`; supplying the current identity produces no
self-edge. `--run-id ID` adds the idempotent `performed` edge
`current identity -> run`. Both targets are validated before identity or edge
writes.

### Native child keys

Pi passes the child's actual session ID unchanged. Codex `SubagentStart` has a
parent `session_id` and an `agent_id`, so it must not use either value alone.
Use the collision-safe composite helper:

```clojure
(identity/codex-child-session-id parent-session-id agent-id)
```

The stable format is `codex-child:v1:<base64url(parent UTF-8)>:<base64url(agent
UTF-8)>`, without padding. It is always distinct from the parent. The CLI can
produce the same value:

```text
strand identity codex-child-key PARENT_SESSION_ID AGENT_ID
```

```json
{"operation":"identity codex-child-key","native-session-id":"codex-child:v1:cGFyZW50:YWdlbnQ"}
```

## Cross-Weaver registration

An attached identity can be registered in another running Weaver without the
caller knowing either workspace's hidden `.millstrand` path. Run the operation
from the identity's origin workspace, choose the destination's exact current
Weaver ID from `mill weaver list`, and attribute the mutation to the identity
being registered:

```text
mill weaver status --json
mill weaver list
strand identity register vivid-clear-stoat \
  --to-weaver TARGET_WEAVER_ID \
  --by-identity vivid-clear-stoat
```

The origin resolves from normal Strand cwd/workspace discovery. The origin calls
the selected destination, and the destination reads the identity back from the
origin before writing anything. The resulting local identity contains the name,
harness, native-session binding, optional model/thinking level, and two durable
provenance attributes:

- `identity/origin-workspace`: canonical origin workspace path
- `identity/origin-strand-id`: identity strand ID in that workspace

It does not copy parent/performed edges, reservation capabilities, delivery
state, or a subgraph. An exact replay returns `result: existing` without a write,
including after either Weaver restarts. A conflicting friendly name, native
session binding, or origin pointer fails before mutation. Both Weavers must be
running and have a version of the identity spool that exposes `register` and its
internal `receive` transport operation; registration never starts or reloads a
Weaver.

## Optional managed reservation

Reservations are a compatibility path for managed callers, not a desktop
startup requirement:

```clojure
(def reserved
  (identity/reserve! runtime {:harness "codex" :model "gpt-5"}))

(identity/attach! runtime
  {:harness "codex"
   :native-session-id actual-thread-id
   :reservation-id (:reservation-id reserved)
   :identity (:identity reserved)})
```

```text
strand identity reserve codex --model gpt-5
strand identity attach codex ACTUAL_THREAD_ID RESERVATION_ID --identity NAME
# Equivalent native startup attachment:
strand identity startup codex ACTUAL_THREAD_ID \
  --reservation-id RESERVATION_ID --identity NAME
```

The exact CLI reservation shape is (library `reserve!` omits `operation`):

```json
{
  "operation": "identity reserve",
  "identity": "warm-silver-lemur",
  "strand-id": "abc12",
  "result": "reserved",
  "reservation-id": "907302db-f1a1-4f20-9908-da397415a7c8"
}
```

The opaque reservation ID is the attachment capability. A friendly name alone
cannot attach a reservation. First attachment records the actual native session;
an exact replay converges, while another harness/session or a conflicting native
binding fails before writes. Attached identities cannot be rebound. Both first
attachment and attachment replay return `result: attached`; startup without a
reservation subsequently returns `result: recovered`. CLI `attach` has the startup
JSON keys with `operation: identity attach`; its library equivalent omits
`operation`.

### Complete CLI argument contract

```text
strand [--cwd DIR] [--workspace DIR] identity startup HARNESS NATIVE_ID
  [--identity NAME] [--reservation-id TOKEN] [--parent-identity NAME]
  [--run-id ID] [--model MODEL] [--thinking-level LEVEL]
strand [--cwd DIR] [--workspace DIR] identity reserve HARNESS
  [--model MODEL] [--thinking-level LEVEL]
strand [--cwd DIR] [--workspace DIR] identity attach HARNESS NATIVE_ID TOKEN
  [--identity NAME] [--parent-identity NAME] [--run-id ID]
```

Wrapped lines above describe one invocation each. All option values are strings;
omit absent options rather than passing empty strings or `nil`. Model and thinking
level are recorded only when minting or reserving, not overwritten on recovery or
attachment. `run-id` resolves an existing strand, without requiring any
Harnesses-specific run schema; managed authorization belongs to the caller.

## Compatibility binding

Existing Claude, Cursor, and managed maintenance integrations may continue to
call `bind!` or `strand identity bind`:

```clojure
(identity/bind! runtime
  {:harness "claude"
   :native-session-id session-id
   :run-id run-id
   :expected-identity prior-identity})
```

Its result remains `:identity`, `:strand-id`, `:resumed`, and `:prompt`.
`expected-identity` remains an assertion rather than adoption permission. If no
native binding exists or it differs, binding fails before minting, so no orphan
identity or provenance edge is left behind.

## Validation scope

The spool is exercised with disposable CLI/runtime worlds, including cold
runtime restart and concurrent startup. Desktop behavior is an adapter contract;
no desktop application or live plugin lifecycle is operated as part of this
spool's acceptance.
