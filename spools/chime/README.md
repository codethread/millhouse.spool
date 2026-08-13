# Millhouse Chime spool

`millhouse.spools.chime` turns meaningful Millstrand graph events into local
notifications. It evaluates workspace-owned rules against the current graph
and sends matching notices through a notifier command chosen by each user.

Chime ships no rules and no notifier. Shared workspace configuration decides
what deserves attention; personal configuration decides how a developer is
told. Chime keeps that binding, its rules, deduplication memory, batch memory,
and recent failures on the active runtime for the weaver lifetime.

## 1. Activation

Approve `millhouse.spools/chime` through the [repository family entry](../../README.md#consumption),
then activate it from trusted startup configuration after syncing approved
roots:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/module! runtime :chime
  {:ns 'millhouse.spools.chime
   :spools ['millhouse.spools/chime]
   :required? true})
```

Activation installs the graph-event handler, the synchronous mutation barrier,
and the lifecycle-managed rule view. It publishes no rule and binds no
notifier by itself.

## 2. Author rules in workspace modules

Use inert `defrule` to define a handler declaration, then select it with `use-rule!`; `defrule!` defines and selects in one form. Selection publishes an owner-partitioned declaration; source evaluation does not run the rule. The generated API documents the declaration shape and focused authoring example.

Each rule receives a context containing `:event`, the candidate `:strand`, and
one `:ready-ids` set computed for the scan. Return nil when there is nothing to
announce, or a notification map with a non-blank `:title` and optional string
`:body`. Chime scans the whole graph, so closing one strand can notify about a
different strand that just became ready. Batch events and their fanout share a
`:batch/id`; only the first event for that batch scans.

An owner-complete refresh retracts omitted declarations. A declaration that is
already matching is used as the initial seen baseline, so restarting a weaver
does not replay durable conditions. A mutation that commits after registration
is ordered after that baseline and can notify normally.

Trusted REPL code and tests can use `register!` and `unregister!` for direct
runtime-local rules. These seams do not edit a rule Var or a module-owned
declaration.

## 3. Bind a personal notifier

Bind a plain `{:argv [...]}` map in gitignored local startup configuration.
Chime starts that command for each notification, appends the title as its final
argument, and writes the body to standard input. The command runs with the
user's local authority, so its path and arguments must be trusted.

The binding is not durable: set it again after every weaver startup or config
reload. If a rule fires while no notifier is bound, Chime records a
`:notifier-missing` failure instead of dropping the notification. Process and
rule failures are likewise retained by `recent-failures`; event-handler
failures remain on Millstrand's event failure surface.

Chime marks a `[rule strand]` pair seen only after the notifier process starts.
It does not notify again while the rule keeps matching, clears the mark when
the rule stops matching, and can be re-armed with `reset-seen!` for tests or
interactive configuration.

## 4. Millstrand state and APIs

| Surface | Identity | Consumer contract |
| --- | --- | --- |
| Rule authoring | `use-rule!` → `:millhouse.spools.chime/rules` | Publishes selected owner-partitioned declarations; `defrule` is inert and `defrule!` combines definition and selection. |
| Event handler | `:chime/engine` | Scans graph mutations for matching rules. |
| Registration barrier | `:chime/registration-barrier` | Orders graph commits after an in-progress rule baseline. |
| Direct runtime seam | `register!` / `unregister!` | Adds or removes a trusted, runtime-local rule without changing module declarations. |
| clj-kondo export | `resources/clj-kondo.exports/millhouse.spools/chime/` | Models `defrule`, `defrule!`, and `use-rule!`; consumers must expose this root's `resources` path. |
