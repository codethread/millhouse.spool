# Millstrand Chime spool — Cookbook

Composition recipes for `millhouse.spools.chime`: combine graph rules,
readiness, and local notification delivery into a useful attention surface.

The [contract](./README.md) describes guarantees. The [generated API](./chime.api.md)
contains signatures, focused calls, and exact return details. These recipes
show combinations with other workspace surfaces rather than single-function
usage.

## Notify on an attribute transition

**Situation.** A strand enters a state a human should see, such as a delegated
run becoming `failed`.

**Composition.** Keep the policy in a workspace rule, register it from shared
startup configuration, and let Chime handle scanning and deduplication.

```clojure
(ns my.rules
  "Workspace attention rules."
  (:require [millhouse.spools.chime :as chime]))

(defn agent-failed
  "Notify when a tracked harness run has failed."
  [{:keys [strand]}]
  (let [status (get-in strand [:attributes "harness/status"])]
    (when (= "failed" status)
      {:title (str "Harness run failed: " (:title strand))
       :body (str "Strand " (:id strand) " entered harness/status failed"
                  (when-let [error (get-in strand [:attributes "harness/error"])]
                    (str "\n\n" error)))})))

(chime/register! :agent-failure 'my.rules/agent-failed)
```

**Why this shape.** Match durable strand attributes rather than one event
shape: the same rule works whether the state was set on creation or on a later
update. Chime deduplicates each `[rule strand]` while it matches, clears the
mark when the condition stops matching, and baselines conditions already true
when the rule is registered.

The repository's [attention rules](https://github.com/codethread/millstrand/blob/3bbe5dc15359975a8e8203ef47b3a7514177e75b/.millstrand/notifications/attention.clj)
and the `registered-rules-fire-end-to-end` and `dedup-and-reset-seen` tests in
[`test/millhouse/chime_test.clj`](./test/millhouse/chime_test.clj) are the
load-bearing examples.

## Notify when an interactive agent session is ready

**Situation.** A `strand agent run <alias> --interactive` run is live, and a
human needs to know that the session is ready without polling `strand agent
runs`.

**Composition.** Match durable Harnesses attributes with a Chime rule. The
notifier remains a personal binding; the rule is shared workspace policy.

```clojure
(ns my.rules
  "Workspace attention rules."
  (:require [millhouse.spools.chime :as chime]))

(defn interactive-session-running
  "Notify when an interactive harness session is ready for its human."
  [{:keys [strand]}]
  (let [attrs (:attributes strand)]
    (when (and (= "true" (get attrs "harness/run"))
               (= "interactive" (get attrs "harness/mode"))
               (= "running" (get attrs "harness/status")))
      {:title (str "Interactive session ready: " (:title strand))
       :body (str "Run " (:id strand) " is waiting for a human."
                  "\nInspect it with `strand agent show " (:id strand) "`."
                  (when-let [session (get attrs "harness/session-id")]
                    (str "\nSession: " session)))})))

(chime/register! :interactive-session-running
                 'my.rules/interactive-session-running)
```

**Why this shape.** Agent-run and Chime stay decoupled: one publishes durable
run state and summaries, while the other evaluates notification policy. A run
already in progress when the rule is registered is baselined; one that starts
later notifies once while it remains running. The run ID and native session ID
come from the same durable Harnesses strand exposed by `strand agent show`.

The Harnesses [agent inspection contract](https://github.com/codethread/harnesses.spool/blob/9548390ce621461ba0a289859fe9b0af963f5805/README.md)
documents the `strand agent show` inspection surface.

## Notify about a strand made ready by another mutation

**Situation.** Closing a blocker makes a dependent human checkpoint ready, but
the event itself names only the blocker.

**Composition.** Use Chime's whole-graph scan and shared `:ready-ids` context
with the checkpoint's durable attributes.

```clojure
(defn checkpoint-ready
  "Notify when a human checkpoint becomes ready to decide."
  [{:keys [strand ready-ids]}]
  (when (and (= "active" (:state strand))
             (= "checkpoint" (get-in strand [:attributes "workflow/role"]))
             (= "human" (get-in strand [:attributes "workflow/checkpoint-kind"]))
             (contains? ready-ids (:id strand)))
    {:title (str "HITL checkpoint ready: " (:title strand))
     :body (str "Checkpoint " (:id strand) " is ready for human attention.")}))

(chime/register! :hitl-checkpoint-ready 'my.rules/checkpoint-ready)
```

**Why this shape.** Chime evaluates every current strand after each relevant
mutation, so a rule can describe the strand worth notifying about rather than
the strand that woke the scan. `:ready-ids` is computed once and shared across
rules, avoiding a separate readiness query per rule. This also supports rules
for parked work: combine readiness with a pending state and an age threshold to
notify about silence rather than a missing mutation.

The `ready-rule-fires-born-ready-and-when-unblocked` test in
[`test/millhouse/chime_test.clj`](./test/millhouse/chime_test.clj) covers both
born-ready and later-unblocked strands.

## Diagnose a quiet notification surface

**Situation.** A rule should have fired, but no notification arrived.

**Composition.** Inspect the notifier binding and recent failures, then clear
deduplication memory only when testing a still-matching rule.

```clojure
(chime/notifier)         ; nil means no notifier is bound
(chime/recent-failures)  ; :notifier-missing, :process, and :rule entries
(chime/reset-seen!)      ; re-arm matching rules without unregistering them
```

**Why this shape.** A missing notifier is recorded loudly, and a notifier
process or rule exception is retained for the weaver lifetime. Chime marks a
rule as seen only after the notifier process starts, so a missing or failing
process does not swallow the alert. `reset-seen!` clears deduplication and
batch-scan memory; it does not change rule registration or notifier binding.

The `missing-notifier-is-recorded-loudly`, `rule-failures-are-recorded`, and
`dedup-and-reset-seen` tests pin this diagnostic contract.
