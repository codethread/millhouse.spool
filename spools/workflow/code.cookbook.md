# Code executor cookbook

These recipes compose the code executor with workflow gates, the shell
executor, and coordinator mutations. Read the [contract](./README.md) for the
guarantees and the [generated API](./code.api.md) for public function details.

## Prepare in process, verify out of process

**Situation.** A trusted Clojure function should prepare an artifact, but a
separate process should perform the final filesystem check before promotion.

**Composition.** Chain a `:code` gate and a `:shell` gate with `depends-on`.
The code function runs only after the build step is complete. The shell
executor sees the artifact only after the code gate closes, so the two
authorities remain independently observable in the workflow.

```clojure
(require '[millhouse.workflow :as workflow])

(defn write-manifest [{:keys [path contents]}]
  (spit path contents)
  {:path path})

(def release
  (workflow/workflow
    "Prepare and verify release"
    (workflow/step :build "Build inputs" :self)
    (workflow/gate :prepare "Write the release manifest" :code
                   :depends-on [:build]
                   :attributes
                   {"code/fn" "my.release/write-manifest"
                    "code/params" {"path" "target/release.manifest"
                                   "contents" "release-42\n"}
                    "code/timeout-secs" 30})
    (workflow/gate :verify "Manifest is non-empty" :shell
                   :depends-on [:prepare]
                   :attributes
                   {"shell/argv" ["test" "-s" "target/release.manifest"]
                    "shell/cwd" "/path/to/worktree"
                    "shell/timeout-secs" 30})
    (workflow/step :publish "Publish release" :self
                   :depends-on [:verify])))

(workflow/start! "release-42" release {})
(workflow/complete! "release-42") ; complete :build
```

**Why this shape.** The code gate is appropriate for a trusted, small
in-process transformation; the shell gate is the better boundary for a
process-level check. Each executor owns its own outcome, and a failed check
stalls the exact gate that needs attention rather than hiding the failure in a
side channel.

## Recover a failed preparation before verification

**Situation.** The preparation function was temporarily broken or received
bad input. The code gate is still ready with `gate/error`, so verification and
publishing must remain blocked until a coordinator repairs the cause.

**Composition.** Inspect the current attempt, fix the callback or request, then
explicitly authorize one new attempt. Deleting `gate/error` is forbidden.

```clojure
(require '[millhouse.workflow.execution :as execution]
         '[millstrand.api.weaver.alpha :as weaver])

(def failed (execution/inspect runtime {:run-id "release-42" :step gate-id}))

;; Correct ordinary request data, then explicitly retry the settled failure.
(weaver/update! runtime gate-id
  {:attributes {"code/params" {"path" "target/release.manifest"
                               "contents" "release-42\n"}}})
(execution/retry! runtime
  {:run-id "release-42" :step gate-id :expected-attempt (:attempt-id failed)
   :request-id "manifest-repair-1" :reason "Corrected manifest input"
   :by-identity "release-coordinator"})
```

Exact request replay returns the original action, not another attempt. A changed
payload using the same key refuses. The new attempt freezes corrected input and
resolves the callable through the runtime classloader when accepted. Nil is a
successful value in the common result envelope, not a missing result.

See [managed execution](execution.md) for the eight-worker busy-admission policy,
deadlines that include capacity waiting, stop versus actual settlement, and
retirement required before routed abandonment. Neither timeout nor interruption
automatically retries a gate.
