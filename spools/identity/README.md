# Millhouse identity spool

[API reference](./identity.api.md)

`millhouse.spools.identity` gives each logical harness session one friendly,
canonical identity. A fresh native session mints an ID such as
`bright-calm-otter`; rebinding the same harness/session pair recovers it.
Managed resume can pass an expected identity and fails on mismatch.

## Activation

Add this root to the workspace's `deps.edn`, then activate it from trusted
startup configuration:

```clojure
{:deps
 {millhouse.spools/identity
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/tag "v2"
   :deps/root "spools/identity"}}}
```

```clojure
(runtime/module! runtime :millhouse/spools-identity
  {:ns 'millhouse.spools.identity
   :required? true})
```

## Harness binding

Harness integrations call `bind!` before launch (or `strand identity bind` from
a start wrapper) with the harness name, its native logical session ID, and an
optional run strand ID:

```clojure
(identity/bind! runtime
  {:harness "pi"
   :native-session-id session-id
   :model "claude-sonnet"
   :thinking-level "high"
   :run-id run-id
   :expected-identity prior-identity})
```

The result contains `:identity`, `:resumed`, and a short `:prompt`. Integrations
export the friendly value as `MILLSTRAND_AGENT_ID` and prepend the prompt to the
agent's initial instructions. If a run is supplied, the identity strand records
a `performed` edge to it.

This POC deliberately contains only session binding and run provenance. It does
not model durable cross-session actors, end hooks, capabilities, or domain acts.
