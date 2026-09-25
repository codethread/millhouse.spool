# Identity attribution cookbook

## Record first, resolve later

Store the supplied friendly identity string in the same durable mutation as the
source record. Do not call `identity/current` before accepting otherwise-valid
work:

```clojure
(weaver/add!
  runtime
  {:title "Durable work record"
   :attributes {:identity/by-identity supplied-identity}})
```

When exactly one local identity has that friendly name, the identity spool adds
`identity --attributed--> source`. Unknown or ambiguous names remain stored and
visible without rejecting the source mutation.

## Inspect convergence

Inspect every configured source:

```text
strand identity attributions
```

Inspect one source:

```text
strand identity attributions SOURCE_ID
```

The projection preserves the raw `identity` string and reports exact matches and
current links separately. Use `status` to distinguish `resolved`, `unresolved`,
`ambiguous`, `malformed`, and a transient stale-link `absent` state.

No projection performs fuzzy matching or reaches another workspace. An
unresolved value needs an exact local registration; an ambiguous value needs the
registry conflict corrected. A malformed value needs the source record fixed.

## Reconcile explicitly

Activation and post-commit events normally converge links. To repair after
missed events or inspectable event-handler failure:

```text
strand identity reconcile
strand identity reconcile SOURCE_ID
```

Library callers use `reconcile-attributions!`. Reconciliation derives everything
from durable source records and current local identities. Repeating it on a
converged graph performs no write.

## Add a spool-owned role

Keep role semantics separate. In the owning module source, publish an explicit
attribute/relation contribution:

```clojure
(require '[millhouse.identity :as identity])

(identity/contribute-attribution!
  :support/caller
  :support/caller-identity
  "called")
```

The owning spool then stores `:support/caller-identity` on its durable source
records. Reconciliation creates `identity --called--> source`; it does not
collapse that role into canonical `attributed` edges.

Contribution attributes must be qualified keywords and relation names must be
non-blank strings. Every effective contribution owns a unique attribute and a
unique relation. `:identity/by-identity` and `attributed` are reserved for the
canonical contract.

Prefer immutable spool-owned records for history. For example, a handoff should
create a durable claim record attributed to its owner rather than depending only
on a card's overwritten current-owner attribute. Reconciliation augments those
records; it does not fabricate prior claims or reporters.

## Keep custody strict

Do not use best-effort attribution for native session binding. `startup!`,
`reserve!`, `attach!`, `bind!`, and cross-Weaver registration keep their strict
exact-match and conflict validation. Attribution neither attaches a reservation
nor authorizes execution custody.
