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
  "Notify when a delegated run has failed or exhausted its attempts."
  [{:keys [strand]}]
  (let [phase (get-in strand [:attributes "agent-run/phase"])]
    (when (contains? #{"failed" "exhausted"} phase)
      {:title (str "Agent run " phase ": " (:title strand))
       :body (str "Strand " (:id strand) " entered agent-run/phase " phase
                  (when-let [error (get-in strand [:attributes "agent-run/error"])]
                    (str "\n\n" error)))})))

(chime/register! :agent-failure 'my.rules/agent-failed)
```

**Why this shape.** Match durable strand attributes rather than one event
shape: the same rule works whether the state was set on creation or on a later
update. Chime deduplicates each `[rule strand]` while it matches, clears the
mark when the condition stops matching, and baselines conditions already true
when the rule is registered.

The repository's [attention rules](https://github.com/codethread/millstrand/blob/aed95c22bbdb1fe5a916886e8ebda787d370173d/.millstrand/notifications/attention.clj)
and the `registered-rules-fire-end-to-end` and `dedup-and-reset-seen` tests in
[`test/millhouse/chime_test.clj`](./test/millhouse/chime_test.clj) are the
load-bearing examples.

## Notify when an interactive agent session is ready

**Situation.** An `agent delegate --interactive` run is live, and a human needs
the attach hint without polling `strand agent ps`.

**Composition.** Combine durable agent-run attributes with the agent-run
summary and a Chime rule. The notifier remains a personal binding; the rule is
shared workspace policy.

```clojure
(ns my.rules
  "Workspace attention rules."
  (:require [clojure.string :as str]
            [ct.spools.agent-run :as agent-run]
            [millhouse.spools.chime :as chime]))

(defn interactive-session-running
  "Notify when an interactive agent-run session is ready for its human."
  [{:keys [strand]}]
  (let [attrs (:attributes strand)]
    (when (and (= "true" (get attrs "agent-run/run"))
               (= "interactive" (get attrs "agent-run/mode"))
               (= "running" (get attrs "agent-run/phase")))
      (let [summary (some #(when (= (:id %) (:id strand)) %)
                          (agent-run/runs {:active true}))
            attach (:attach summary)]
        {:title (str "Interactive session ready: " (:title strand))
         :body (str "Run " (:id strand) " is waiting for a human."
                    (when-let [served (:for summary)]
                      (str "\nServes: " served))
                    (if (str/blank? attach)
                      "\nAttach: no backend attach hint is configured for this run."
                      (str "\nAttach: " attach)))}))))

(chime/register! :interactive-session-running
                 'my.rules/interactive-session-running)
```

**Why this shape.** Agent-run and Chime stay decoupled: one publishes durable
run state and summaries, while the other evaluates notification policy. A run
already in progress when the rule is registered is baselined; one that starts
later notifies once while it remains running. The attach text comes from the
same summary surface exposed by `strand agent ps`, and the rule reports when no
backend attach hint exists.

The [agent-run summary contract](https://github.com/codethread/agent-harness.spool/blob/d28bfb35b5fc1891a7a318e06886aa446722241d/delegation/README.md)
documents the `mode`, `backend`, `session`, and `attach` fields.

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
