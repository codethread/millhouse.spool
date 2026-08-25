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
(require '[millhouse.spools.workflow :as workflow])

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

**Composition.** Discover the gate through the named query, fix the function
or poured request, then remove `gate/error` with a nil attribute patch. The next
graph scan retries the code gate and only then releases the dependent shell
gate.

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.weaver.alpha :as weaver])

(def runtime (current/runtime))

(weaver/list-query runtime 'stalled-code-gates {})

;; After fixing `my.release/write-manifest` or the request data:
(weaver/update! runtime gate-id
               {:attributes {"gate/error" nil}})
```

**Why this shape.** The named query is the coordinator's durable discovery
surface, while the nil patch is the explicit retry signal. A blank string is
still present data and leaves the gate stalled. The code executor resolves the
current Var on retry, so a repaired definition can recover an already-poured
gate without rewriting its parameters.
