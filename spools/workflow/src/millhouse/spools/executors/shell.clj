(ns millhouse.spools.executors.shell
  "Fulfil workflow `:shell` gates by running their command off the event thread.

  The shell executor watches workflow runs for ready gates whose waiter is
  `:shell`, reserves a durable attempt, and launches the gate's `shell/argv`
  directly (no implicit shell) through Mill-owned process custody. It closes the
  gate through `millhouse.spools.workflow/complete!` on a zero exit. A non-zero
  exit, timeout, spawn error, or invalid argv stamps a loud, distinct
  `gate/error` and leaves the gate ready and stamped rather than masquerading as
  a completed run. Terminal custody facts are committed to the matching attempt
  before acknowledgement, and module-owned reconciliation repairs the in-flight
  view after Weaver replacement. It is a subagent-executor sibling minus
  everything agent-run-specific: the failure detail lives on the gate itself, so
  there is no separate run strand, no `delegates` edge, and no session/harness
  vocabulary. Request validation and the durable coordinator surfaces are
  described on the public executor and query Vars below."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.process.alpha :as process]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.spool.alpha :refer [fail! attr-get require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant]
           [java.util.concurrent Executors ExecutorService ThreadFactory TimeUnit]))

(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned :strand/superseded})

(def ^:private output-tail-bytes
  "Fixed cap on captured combined stdout+stderr: the shell executor retains only
  the last N bytes so a runaway child cannot exhaust weaver heap
  (`PLAN-ShellGates-001.R3`)."
  (* 16 1024))

(def ^:private custody-owner
  "Stable Mill process-custody owner for all shell attempts."
  :millhouse/shell-executor)

(def ^:private process-poll-ms 100)

(def ^:private timeout-deadline-attribute "shell/timeout-deadline")
(def ^:private timeout-intent-attribute "shell/timeout-intent")
(def ^:private timeout-handler
  'millhouse.spools.executors.shell/timeout-wake)

(def ^:dynamic *runtime*
  "Runtime captured for asynchronous shell-executor worker threads."
  nil)

(defn- rt []
  (or *runtime* (current/runtime)))

(def ^:private state-version
  "Shape version for the shell executor's runtime spool-state map. Bump whenever
  `new-state`'s key set changes: spool-state survives module refresh, so a
  post-upgrade refresh would otherwise reuse a preserved map missing the new key.
  The `state-shape-matches-declared-version` test guards against silent drift."
  1)

(defn- daemon-thread-factory ^ThreadFactory [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (swap! counter inc)))
          (.setDaemon true))))))

(defn- new-state []
  (let [^ExecutorService workers (Executors/newCachedThreadPool (daemon-thread-factory "shell-worker"))]
    {:scan-monitor (Object.)
     :worker-executor workers
     :close-fn (fn []
                 (.shutdownNow workers)
                 (when-not (.awaitTermination workers 1000 TimeUnit/MILLISECONDS)
                   (fail! "Shell executor worker pool did not stop" {})))}))

(defn- state []
  (runtime/spool-state (rt) ::state {:version state-version} new-state))

(defn- scan-monitor [] (:scan-monitor (state)))

(defn- worker-executor ^ExecutorService []
  (or (:worker-executor (state))
      (fail! "Shell executor worker pool is missing from spool state" {})))

(defn- attr [strand k]
  (attr-get strand k))

(defn- stamped?
  "True when attribute `k` is present on `gate`, false when the key is absent.

  Absence is the only cleared state: a coordinator re-arms a gate by removing
  `gate/error` / `shell/running` with a trusted nil patch (or the CLI
  `strand update <gate-id> --attributes '{\"gate/error\":null}'` JSON-null merge).
  A blank string is present data and does not re-arm the gate (epic 9emyu)."
  [gate k]
  (some? (attr gate k)))

(defn- stamp! [id attributes]
  (weaver/update! (rt) id {:attributes attributes}))

;; ---------------------------------------------------------------------------
;; Gate attribute contract

(defn non-blank-string?
  "Return true when `value` is a non-blank string.

  The shell request spec uses this predicate for the optional `shell/cwd`
  attribute."
  [value]
  (and (string? value) (not (str/blank? value))))

(s/def :shell/argv (s/coll-of string? :kind sequential? :min-count 1))
(s/def :shell/cwd non-blank-string?)
(s/def :shell/timeout-secs pos-int?)
(s/def ::request
  (s/keys :req [:shell/argv] :opt [:shell/cwd :shell/timeout-secs]))
(s/def ::id string?)
(s/def ::gate-view (s/keys :req-un [::id]))
(s/def ::gate string?)
(s/def ::error any?)
(s/def ::stall-detail (s/nilable (s/keys :req-un [::gate ::error])))

(defn- require-request!
  "Validate the gate's `shell/*` request attributes against `::request` before
  any process spawns, failing loudly (TEN-003) with the shared explain
  vocabulary so the stamped `gate/error` names the spec and the failed keys."
  [gate]
  (let [request (cond-> {:shell/argv (attr gate :shell/argv)}
                  (stamped? gate :shell/cwd)
                  (assoc :shell/cwd (attr gate :shell/cwd))
                  (stamped? gate :shell/timeout-secs)
                  (assoc :shell/timeout-secs (attr gate :shell/timeout-secs)))]
    (when-not (s/valid? ::request request)
      (fail! "shell gate request must satisfy shell/argv, shell/cwd, and shell/timeout-secs"
             {:gate (:id gate) :value request :spec ::request
              :explain (s/explain-str ::request request)}))))

(defn- parse-argv
  "Return the gate's `shell/argv` as a validated `List<String>`, or fail loudly
  (TEN-003) so no process spawns. Missing, non-array, empty, or non-string-element
  argv is a hard error stamped onto `gate/error`."
  [gate]
  (let [argv (attr gate :shell/argv)]
    (when-not (s/valid? :shell/argv argv)
      (fail! "shell/argv must be a non-empty JSON array of strings"
             {:gate (:id gate) :value argv :spec :shell/argv
              :explain (s/explain-str :shell/argv argv)}))
    (vec argv)))

(defn- parse-timeout
  "Return the gate's `shell/timeout-secs` as a positive long, nil when absent, or
  fail loudly on a non-positive/non-integer value — the shell executor never
  silently clamps."
  [gate]
  (let [v (attr gate :shell/timeout-secs)]
    (cond
      (nil? v) nil
      (s/valid? :shell/timeout-secs v) (long v)
      :else (fail! "shell/timeout-secs must be a positive integer"
                   {:gate (:id gate) :value v :spec :shell/timeout-secs
                    :explain (s/explain-str :shell/timeout-secs v)}))))

(defn- parse-cwd
  "Return the optional `shell/cwd` string, or fail loudly on malformed values."
  [gate]
  (let [v (attr gate :shell/cwd)]
    (cond
      (nil? v) nil
      (s/valid? :shell/cwd v) v
      :else (fail! "shell/cwd must be a non-blank string"
                   {:gate (:id gate) :value v :spec :shell/cwd
                    :explain (s/explain-str :shell/cwd v)}))))

(defn- timeout-key [attempt-id]
  (str "shell-timeout/" attempt-id))

(defn- deadline-string [^Instant deadline]
  (str deadline))

(defn- parse-deadline [value]
  (when (string? value)
    (try
      (Instant/parse value)
      (catch java.time.format.DateTimeParseException _
        nil))))

(defn- timeout-deadline
  [runtime timeout-secs]
  (when timeout-secs
    (.plusSeconds ^Instant (runtime/now runtime) (long timeout-secs))))

(defn- timeout-wake-pending?
  [runtime attempt-id]
  (some #(= (timeout-key attempt-id) (:key %))
        (scheduler/pending runtime)))

(defn- arm-timeout!
  "Persist and arm the timeout for one durable attempt at its original bound."
  [runtime attempt-id handle deadline]
  (when (and handle deadline)
    (scheduler/schedule!
     runtime
     {:key (timeout-key attempt-id)
      :wake-at deadline
      :handler timeout-handler
      :payload {:attempt-id attempt-id :handle handle}})))

(defn- cancel-timeout!
  [runtime attempt-id]
  (when (timeout-wake-pending? runtime attempt-id)
    (scheduler/cancel! runtime (timeout-key attempt-id))))

;; ---------------------------------------------------------------------------
;; Process execution (worker thread only)

(defn- drain-tail!
  "Fully drain `in`, returning the last `limit` bytes decoded as UTF-8. A ring
  buffer caps retention at `limit`, so a child that writes without bound cannot
  exhaust heap; the whole stream is never buffered."
  ^String [^java.io.InputStream in ^long limit]
  (let [^bytes ring (byte-array limit)
        ^bytes chunk (byte-array 8192)]
    (loop [total 0]
      (let [n (.read in chunk 0 (alength chunk))]
        (if (neg? n)
          (let [kept (int (min total limit))
                start (int (mod (- total kept) limit))
                ^bytes out (byte-array kept)
                first-run (int (min kept (- limit start)))]
            (System/arraycopy ring start out 0 first-run)
            (when (< first-run kept)
              (System/arraycopy ring 0 out first-run (- kept first-run)))
            (String. out StandardCharsets/UTF_8))
          (let [p (int (mod total limit))
                head (int (min n (- limit p)))]
            ;; A single read returns at most 8192 bytes < limit, so the write
            ;; wraps the ring at most once.
            (System/arraycopy chunk 0 ring p head)
            (when (< head n)
              (System/arraycopy chunk head ring 0 (- n head)))
            (recur (+ total (long n)))))))))

;; ---------------------------------------------------------------------------
;; Terminal outcomes (worker thread only)

(defn- pass!
  "Close the gate on a zero exit through ordinary workflow vocabulary, recording
  the shell outcome in the same batch. Stamping the exit code, bounded output, and
  cleared claim as `complete!` `:attributes` closes the gate and records its
  outcome atomically, so no observer ever sees a closed gate without its
  `shell/exit-code`/`shell/output`, and leaving the ready frontier atomically
  stops any concurrent scan re-dispatching the check.

  The whole outcome is this executor's own `shell/*` vocabulary: the engine keeps
  no prose field a reader would have to consult instead of the exit code."
  [run-id gate-id attempt-id custody-handle exit output]
  (workflow/complete! run-id
                      {:step gate-id :by "shell"
                       :attributes (cond-> {"shell/running" nil
                                            "shell/attempt-id" attempt-id
                                            "shell/custody-handle" custody-handle
                                            "shell/exit-code" exit}
                                     (some? output) (assoc "shell/output" output))}))

(defn- custody-output
  "Read a bounded stdout-then-stderr tail from retained Mill output.

  Mill retains stdout and stderr separately, so their deterministic projection
  here is stream order followed by one combined 16 KiB tail. Any unreadable
  reference throws; callers must retain the custody fact as evidence."
  [{:keys [stdout-ref stderr-ref]}]
  (let [read-ref (fn [path]
                   (with-open [input (io/input-stream (io/file path))]
                     (drain-tail! input output-tail-bytes)))
        output (str (read-ref stdout-ref) (read-ref stderr-ref))
        ^bytes bytes (.getBytes output StandardCharsets/UTF_8)]
    (if (<= (alength bytes) output-tail-bytes)
      output
      (String. ^bytes bytes (int (- (alength bytes) output-tail-bytes))
               (int output-tail-bytes) ^java.nio.charset.Charset StandardCharsets/UTF_8))))

(defn- process-terminal?
  [record]
  (= :terminal (:phase record)))

(declare cancel-attempt-at-timeout!)

(defn- await-custody-terminal!
  "Poll one retained process record until Mill reports its terminal fact.

  The process itself is owned by Mill; this wait is only a convenience for the
  current generation. If Weaver replacement interrupts this worker, the durable
  attempt and Mill record remain for `reconcile-shell-attempts!`."
  [runtime gate-id attempt-id handle timeout-secs]
  (let [deadline (when timeout-secs
                   (+ (System/nanoTime)
                      (* (long timeout-secs) 1000000000)))]
    (loop [record (process/get runtime handle)
           cancelled? false]
      (if (process-terminal? record)
        {:record record :timed-out? cancelled?}
        (if (and deadline (not cancelled?)
                 (>= (System/nanoTime) deadline))
          (do
            (cancel-attempt-at-timeout! runtime gate-id attempt-id handle)
            (recur (process/get runtime handle) true))
          (do
            (Thread/sleep (long process-poll-ms))
            (recur (process/get runtime handle) cancelled?)))))))

(defn- attempt-gate
  [gate-id attempt-id custody-handle]
  (let [gate (weaver/show (rt) gate-id)]
    (when (and (= attempt-id (attr gate :shell/attempt-id))
               (= custody-handle (attr gate :shell/custody-handle)))
      gate)))

(defn- stamp-attempt!
  "Stamp only while the durable attempt/handle pair is still current."
  [gate-id attempt-id custody-handle attributes]
  (when (attempt-gate gate-id attempt-id custody-handle)
    (stamp! gate-id attributes)))

(defn- clear-attempt!
  "Clear only the attempt identity that owns `custody-handle`."
  [gate-id attempt-id custody-handle]
  (boolean
   (stamp-attempt! gate-id attempt-id custody-handle
                   {"shell/custody-handle" nil
                    "shell/attempt-id" nil
                    "shell/running" nil})))

(defn- fail-attempt!
  "Stamp an error only while the attempt/handle pair is still current."
  [gate-id attempt-id custody-handle detail exit output]
  (when (stamp-attempt! gate-id attempt-id custody-handle
                        (cond-> {"shell/running" nil "gate/error" detail}
                          (some? exit) (assoc "shell/exit-code" exit)
                          (some? output) (assoc "shell/output" output)))
    :stamped))

(defn- terminal-error
  [record timed-out?]
  (cond
    (:cancellation record) (if timed-out?
                             "shell command timed out"
                             (str "shell command cancelled: "
                                  (get-in record [:cancellation :reason])))
    (:launch-failure record) (str "shell command failed to launch: "
                                  (get-in record [:launch-failure :message]))
    (:exit record) (str "shell command exited " (get-in record [:exit :code]))
    :else (str "shell process terminal fact is malformed; expected one of "
               ":cancellation, :launch-failure, or :exit; observed "
               (pr-str (select-keys record
                                    [:key :handle :phase :cancellation
                                     :launch-failure :exit])))))

(defn- terminal-exit
  [record]
  (some-> record :exit :code))

(defn- terminal-commit!
  "Apply one terminal custody fact only to its still-current gate attempt.

  The gate identity and attempt/handle pair are checked immediately before the
  durable workflow write. A stale fact is owner-local and is never acknowledged,
  so a coordinator can repair the mismatch without advancing another attempt."
  [run-id gate-id attempt-id custody-handle record timed-out?]
  (if-let [gate (attempt-gate gate-id attempt-id custody-handle)]
    (if (= "closed" (:state gate))
      :already-committed
      (let [output (custody-output (:output record))]
        (if (and (:exit record) (zero? (terminal-exit record)))
          (pass! run-id gate-id attempt-id custody-handle 0 output)
          (fail-attempt! gate-id attempt-id custody-handle
                         (terminal-error record
                                         (or timed-out?
                                             (= "timed-out"
                                                (attr gate :shell/timeout-intent))))
                         (terminal-exit record) output))
        :committed))
    :stale))

(defn- persist-custody-handle!
  [gate-id attempt-id handle]
  (let [gate (weaver/show (rt) gate-id)]
    (when (and (= attempt-id (attr gate :shell/attempt-id))
               (nil? (attr gate :shell/custody-handle)))
      (stamp-attempt! gate-id attempt-id nil {"shell/custody-handle" handle}))))

(defn- terminal-reconcile!
  "Commit, acknowledge, and clear one terminal fact under the runtime lock."
  [runtime run-id gate-id attempt-id custody-handle record timed-out?]
  #_{:splint/disable [lint/locking-object]}
  (locking (scan-monitor)
    (let [commit (terminal-commit! run-id gate-id attempt-id custody-handle
                                   record timed-out?)]
      (case commit
        :stale :stale
        (do
          ;; Keep the durable timeout wake from racing a terminal commit. A
          ;; failed cancellation leaves the fact unacknowledged for the next
          ;; reconciliation, just like any other failed custody step.
          (cancel-timeout! runtime attempt-id)
          (process/acknowledge! runtime custody-owner custody-handle)
          (if (clear-attempt! gate-id attempt-id custody-handle)
            :acknowledged
            :stale))))))

(defn- mark-timeout!
  "Record timeout intent before asking Mill to cancel the retained process."
  [gate-id attempt-id custody-handle]
  (stamp-attempt! gate-id attempt-id custody-handle
                  {timeout-intent-attribute "timed-out"}))

(defn- cancel-attempt-at-timeout!
  [runtime gate-id attempt-id custody-handle]
  (when (and gate-id attempt-id custody-handle)
    (when (mark-timeout! gate-id attempt-id custody-handle)
      (process/cancel! runtime custody-owner custody-handle))))

(defn- enforce-timeout!
  "Rearm the original timeout after replacement, or cancel when it is due."
  [runtime attempt handle]
  (when-let [deadline (parse-deadline (:timeout-deadline attempt))]
    (if-not (.isAfter ^Instant deadline ^Instant (runtime/now runtime))
      (cancel-attempt-at-timeout! runtime (:gate-id attempt)
                                  (:attempt-id attempt) handle)
      (arm-timeout! runtime (:attempt-id attempt) handle deadline))))

(defn- run-gate!
  "Launch one claimed shell attempt through Mill custody and reconcile its fact."
  [runtime run-id gate-id attempt-id]
  (try
    (let [gate (weaver/show (rt) gate-id)
          _ (require-request! gate)
          raw-argv (parse-argv gate)
          timeout-secs (parse-timeout gate)
          deadline (or (parse-deadline (attr gate :shell/timeout-deadline))
                       (timeout-deadline runtime timeout-secs))
          argv raw-argv
          cwd (some-> (or (parse-cwd gate) ".") io/file .getAbsolutePath)
          process-record (process/launch! runtime custody-owner attempt-id
                                          {:argv argv :cwd cwd :env {}})
          custody-handle (:handle process-record)]
      (persist-custody-handle! gate-id attempt-id custody-handle)
      (when (and deadline (nil? (attr gate :shell/timeout-deadline)))
        (stamp-attempt! gate-id attempt-id custody-handle
                        {timeout-deadline-attribute (deadline-string deadline)}))
      (arm-timeout! runtime attempt-id custody-handle deadline)
      (let [{:keys [record timed-out?]}
            (if (process-terminal? process-record)
              {:record process-record :timed-out? false}
              (await-custody-terminal! runtime gate-id attempt-id custody-handle
                                       timeout-secs))]
        (terminal-reconcile! runtime run-id gate-id attempt-id custody-handle
                             record timed-out?)))
    (catch InterruptedException _
      ;; Keep the claim across the launch-to-handle seam. Reconciliation can
      ;; match a retained Mill fact by the attempt key even when this worker
      ;; never persisted the opaque handle.
      nil)
    (catch Throwable t
      (when-not (contains? #{"process/control-unavailable" "process/stale-weaver"}
                           (:code (ex-data t)))
        (let [gate (weaver/show (rt) gate-id)]
          (when (= "active" (:state gate))
            (fail-attempt! gate-id attempt-id (attr gate :shell/custody-handle)
                           (str (ex-message t) (some->> (ex-data t) (str " "))) nil nil)))))))

;; ---------------------------------------------------------------------------
;; Event-driven scan

(defn- claim-and-dispatch!
  "Idempotently claim a ready, un-errored, un-claimed `:shell` gate by stamping a
  `shell/running` marker before dispatch, then submit the actual process run to
  the worker pool. The event thread never blocks on a child process.

  The gate is re-read fresh (not trusted from the ready snapshot, which a
  concurrent close can outrace) and must still be `active`: `pass!` clears the
  claim and closes the gate in one atomic batch, and `fail-gate!` clears the
  claim while stamping `gate/error` — so every claim-clearing transition also
  either closes the gate or stamps an error, and this guard blocks re-dispatch
  in all three cases."
  [runtime run-id gate-view]
  (let [gate (weaver/show (rt) (:id gate-view))]
    (when (and (= "active" (:state gate))
               (not (stamped? gate :gate/error))
               (not (stamped? gate :shell/running))
               (not (stamped? gate :shell/custody-handle)))
      (let [attempt-id (str (java.util.UUID/randomUUID))]
        (let [timeout-secs (let [value (attr gate :shell/timeout-secs)]
                             (when (s/valid? :shell/timeout-secs value)
                               (long value)))
              deadline (timeout-deadline runtime timeout-secs)]
          (stamp! (:id gate)
                  (cond-> {"shell/running" attempt-id
                           "shell/attempt-id" attempt-id}
                    deadline (assoc timeout-deadline-attribute
                                    (deadline-string deadline)))))
        (.execute (worker-executor)
                  ^Runnable (fn []
                              (current/with-runtime runtime
                                (binding [*runtime* runtime]
                                  (run-gate! runtime run-id (:id gate) attempt-id)))))))))

(defn scan!
  "Dispatch every ready `:shell` gate not already claimed or errored.

  Enumerates ready gates purely through the workflow surface and serializes on a
  runtime-owned monitor so concurrent scans cannot double-launch a gate. Each
  accepted gate receives a `shell/running` claim before its process is submitted
  to the worker pool; the event thread never waits for the child. Scans run on
  relevant graph changes and once during handler activation."
  []
  (let [runtime (rt)]
    (binding [*runtime* runtime]
      ;; scan-monitor returns the runtime-owned (Object.) monitor; the rule only
      ;; recognises bare-symbol locks and can't see the stable Object behind it.
      #_{:splint/disable [lint/locking-object]}
      (locking (scan-monitor)
        (doseq [root (workflow/active-runs)
                :let [run-id (attr root :workflow/run-id)]
                step (workflow/ready run-id)
                :when (= "shell" (:gate step))]
          (claim-and-dispatch! runtime run-id step))
        {:scanned true}))))

(defn on-event
  "Weaver event handler: graph changes may make a `:shell` gate ready."
  [_event]
  (scan!))

;; ---------------------------------------------------------------------------
;; Owner declarations and resource reconciliation

(defn- shell-gates
  [runtime]
  (weaver/list runtime [:= [:attr "workflow/gate"] "shell"] {}))

(defn read-shell-attempts
  "Return durable shell attempts owned by this spool.

  Closed gates remain in this view until their custody handle is acknowledged;
  that lets a later reconciliation finish an interrupted terminal commit without
  replaying the shell command."
  [{:keys [runtime]}]
  (mapv (fn [gate]
          {:gate-id (:id gate)
           :run-id (attr gate :workflow/run-id)
           :state (:state gate)
           :attempt-id (attr gate :shell/attempt-id)
           :custody-handle (attr gate :shell/custody-handle)
           :timeout-deadline (attr gate :shell/timeout-deadline)
           :timeout-intent (attr gate :shell/timeout-intent)
           :error (attr gate :gate/error)})
        (filter #(some? (attr % :shell/attempt-id))
                (shell-gates runtime))))

(defn timeout-wake
  "Cancel a shell attempt when its durable absolute timeout is due.

  The wake is deliberately keyed by attempt identity. A terminal commit cancels
  it; a stale delivery re-reads the gate and therefore cannot cancel a newer
  attempt that reused the workflow gate."
  [{:keys [runtime payload]}]
  (let [attempt-id (:attempt-id payload)
        attempt (some #(when (= attempt-id (:attempt-id %)) %)
                      (read-shell-attempts {:runtime runtime}))]
    (when (and attempt
               (= "active" (:state attempt))
               (= (:handle payload) (:custody-handle attempt))
               (parse-deadline (:timeout-deadline attempt)))
      (cancel-attempt-at-timeout! runtime (:gate-id attempt) attempt-id
                                  (:custody-handle attempt))))
  nil)

(defn read-shell-custody
  "Return custody records, or an explicit deferred result when Mill custody is
  temporarily unavailable during Weaver replacement.

  Other listing failures remain loud: an empty durable-attempt set is not a
  reason to reinterpret an unavailable custody read as a successful empty
  listing."
  [{:keys [runtime]}]
  (try
    (process/list-owned runtime custody-owner)
    (catch Throwable throwable
      ;; During a fresh Weaver probe (and the first refresh around a planned
      ;; replacement) Mill may not yet admit this runtime to its process-control
      ;; socket. Keep the durable attempt for the next generation; do not turn
      ;; a transport seam into a false custody acknowledgement.
      (let [code (:code (ex-data throwable))]
        (if (contains? #{"process/control-unavailable" "process/stale-weaver"}
                       code)
          {:status :deferred
           :reason (keyword (str/replace code #"/" "-"))
           :facts []}
          (throw throwable))))))

(defn- owner-local-failure!
  [attempt detail]
  (when-let [gate-id (:gate-id attempt)]
    #_{:splint/disable [lint/locking-object]}
    (locking (scan-monitor)
      (fail-attempt! gate-id (:attempt-id attempt) (:custody-handle attempt)
                     detail nil nil)))
  {:attempt-id (:attempt-id attempt)
   :gate-id (:gate-id attempt)
   :error detail})

(defn- reconcile-fact!
  [runtime attempt fact]
  (let [handle (:handle fact)
        expected (:custody-handle attempt)]
    (cond
      (and expected (not= expected handle))
      (owner-local-failure!
       attempt
       (str "shell custody handle mismatch for attempt " (:attempt-id attempt)))

      (not (process-terminal? fact))
      (do
        (when-not expected
          #_{:splint/disable [lint/locking-object]}
          (locking (scan-monitor)
            (stamp-attempt! (:gate-id attempt) (:attempt-id attempt) nil
                            {"shell/custody-handle" handle})))
        (enforce-timeout! runtime attempt handle)
        {:attempt-id (:attempt-id attempt) :phase (:phase fact)})

      :else
      (try
        (let [commit (terminal-reconcile! runtime (:run-id attempt) (:gate-id attempt)
                                          (:attempt-id attempt) handle fact
                                          (= "timed-out" (:timeout-intent attempt)))]
          (if (= :stale commit)
            {:attempt-id (:attempt-id attempt)
             :stale true
             :error (str "stale shell custody fact for attempt "
                         (:attempt-id attempt))}
            {:attempt-id (:attempt-id attempt)
             :phase :terminal
             :acknowledged true}))
        (catch Throwable throwable
          (owner-local-failure!
           attempt
           (str "shell custody fact could not be reconciled for attempt "
                (:attempt-id attempt) ": " (ex-message throwable))))))))

(defn- deferred-custody-read?
  [actual]
  (and (map? actual)
       (contains? #{:deferred :unknown} (:status actual))))

(defn- custody-facts
  [actual]
  (if (map? actual)
    (vec (or (:facts actual) (:records actual) []))
    actual))

(defn apply-shell-attempts!
  "Reconcile durable shell attempts with retained Mill custody facts.

  Missing, stale, and mismatched facts are owner-local errors. They are returned
  in the reconcile summary and, when a gate still exists, stamped on that gate;
  the reconciler does not invent a replacement attempt or acknowledge evidence
  it cannot correlate."
  [{:keys [runtime] :as context}]
  (let [desired (:desired context)
        actual (:actual context)
        deferred? (deferred-custody-read? actual)
        actual (custody-facts actual)
        by-key (group-by :key actual)
        desired-keys (set (keep :attempt-id desired))
        results (if deferred?
                  (mapv (fn [attempt]
                          {:attempt-id (:attempt-id attempt)
                           :deferred true})
                        desired)
                  (mapv (fn [attempt]
                          (let [facts (get by-key (:attempt-id attempt))]
                            (cond
                              (nil? (:attempt-id attempt))
                              {:gate-id (:gate-id attempt) :ignored true}

                              (empty? facts)
                              (if (= "closed" (:state attempt))
                                #_{:splint/disable [lint/locking-object]}
                                (if (locking (scan-monitor)
                                      (clear-attempt! (:gate-id attempt)
                                                      (:attempt-id attempt)
                                                      (:custody-handle attempt)))
                                  {:attempt-id (:attempt-id attempt)
                                   :recovered :closed-without-custody-fact}
                                  {:attempt-id (:attempt-id attempt)
                                   :stale true})
                                (owner-local-failure!
                                 attempt
                                 (str "missing shell custody fact for attempt "
                                      (:attempt-id attempt))))

                              (> (count facts) 1)
                              (owner-local-failure!
                               attempt
                               (str "multiple shell custody facts for attempt "
                                    (:attempt-id attempt)))

                              :else
                              (reconcile-fact! runtime attempt (first facts)))))
                        desired))
        orphan-errors (if deferred?
                        []
                        (mapv (fn [fact]
                                {:attempt-id (:key fact)
                                 :handle (:handle fact)
                                 :error "shell custody fact has no matching durable attempt"})
                              (remove #(contains? desired-keys (:key %)) actual)))]
    {:reconciled :shell-attempts
     :status (if deferred? :deferred :applied)
     :attempts results
     :errors (vec (concat (filter :error results) orphan-errors))}))

(defn remove-shell-attempts!
  "Report removal of the shell reconciliation effect without guessing cleanup."
  [_context]
  {:removed :shell-attempts})

(lifecycle/defreconcile shell-attempts
  "Reconcile Mill-owned shell attempts with durable workflow gates."
  {:read-desired 'millhouse.spools.executors.shell/read-shell-attempts
   :read-actual 'millhouse.spools.executors.shell/read-shell-custody
   :apply 'millhouse.spools.executors.shell/apply-shell-attempts!
   :on-removed 'millhouse.spools.executors.shell/remove-shell-attempts!
   :after #{:shell-handler}})

(workflow/defexecutor shell
  "Return durable stall detail for a ready `:shell` gate view, or nil.

  The executor accepts a gate with `workflow/gate` equal to `\"shell\"` and a
  request matching `::request`: `shell/argv` is a non-empty sequential value of
  strings, while `shell/cwd` and `shell/timeout-secs` are optional non-blank and
  positive-integer values. The command is passed directly to `ProcessBuilder`,
  so shell syntax must be explicit in the argv, for example:

  ```clojure
  (workflow/gate :verify \"Run tests\" :shell
                 :attributes {\"shell/argv\" [\"clojure\" \"-M:test\"]
                              \"shell/cwd\" \"/workspace/app\"
                              \"shell/timeout-secs\" 600})
  ```

  A zero exit closes the gate through `workflow/complete!` with `:by \"shell\"`
  and records `shell/exit-code` plus the bounded 16 KiB combined stdout/stderr
  tail in `shell/output`. A non-zero exit, timeout, spawn error, or invalid
  request leaves the gate ready with `gate/error`; process failures also record
  the exit code and output. Mill owns the process tree and terminal fact. Invalid
  requests spawn no process. The executor skips a gate while `gate/error`,
  `shell/running`, or an unacknowledged `shell/custody-handle` is present.

  For a stalled ready gate this function returns
  `{:gate gate-id :error detail}`. Remove the `gate/error` attribute (and any
  stale `shell/running` claim after a crash) to re-arm the next scan; a blank
  string is still present data."
  {:request-spec ::request}
  [gate-view]
  (require-valid! ::gate-view gate-view "Invalid shell gate view")
  (let [gate (weaver/show (rt) (:id gate-view))
        result (when (stamped? gate :gate/error)
                 {:gate (:id gate) :error (attr gate :gate/error)})]
    (require-valid! ::stall-detail result "Invalid shell gate stall detail")))

(millstrand/defquery stalled-shell-gates
  "Return active shell gates carrying a durable error stamp.

  The query is the persistence-side companion to `shell-stalled?`:

  ```clojure
  (weaver/list-query runtime 'stalled-shell-gates {})
  ```

  Recovery removes the error key rather than replacing it with a blank string;
  a trusted nil patch re-arms a ready gate for the next event-driven scan:

  ```clojure
  (weaver/update! runtime gate-id
                  {:attributes {\"gate/error\" nil
                                \"shell/running\" nil}})
  ```

  Rewrite request attributes in the same update when fixing the underlying
  command or working directory."
  {}
  [:and [:= :state "active"]
   [:= [:attr "workflow/gate"] "shell"]
   [:exists [:attr "gate/error"]]])

(defn- register-shell-handler!
  "Register the graph-change event handler that drives shell-gate scans."
  [runtime]
  (events/register-handler! runtime :shell/engine event-types
                            'millhouse.spools.executors.shell/on-event
                            {:spool "shell"}))

(s/def ::runtime some?)
(s/def ::resource map?)
(s/def ::open-context (s/keys :req-un [::runtime]))
(s/def ::close-context (s/keys :req-un [::resource]))
(s/def ::handler-close-context (s/keys :req-un [::runtime]))
(s/def ::pool-handle
  #(= #{:scan-monitor :worker-executor :close-fn} (set (keys %))))
(s/def ::registered #{:shell/engine})
(s/def ::unregistered #{:shell/engine})
(s/def ::closed #{:shell-pool})

(defn open-shell-pool!
  "Open the runtime-lifetime shell worker pool."
  [ctx]
  (require-valid! ::open-context ctx "Invalid shell pool open context")
  (let [runtime (:runtime ctx)
        result (current/with-runtime runtime
                 (binding [*runtime* runtime]
                   (state)))]
    (require-valid! ::pool-handle result "Invalid shell pool handle")))

(defn close-shell-pool!
  "Close the runtime-lifetime shell worker pool."
  [ctx]
  (require-valid! ::close-context ctx "Invalid shell pool close context")
  ((:close-fn (:resource ctx)))
  (require-valid! (s/keys :req-un [::closed])
                  {:closed :shell-pool}
                  "Invalid shell pool close result"))

(defn open-shell-handler!
  "Register shell scanning and run the initial scan."
  [ctx]
  (require-valid! ::open-context ctx "Invalid shell handler open context")
  (let [runtime (:runtime ctx)
        result (current/with-runtime runtime
                 (binding [*runtime* runtime]
                   (register-shell-handler! runtime)
                   (scan!)
                   {:registered :shell/engine}))]
    (require-valid! (s/keys :req-un [::registered])
                    result
                    "Invalid shell handler open result")))

(defn close-shell-handler!
  "Unregister shell scanning when the module is removed."
  [ctx]
  (require-valid! ::handler-close-context ctx "Invalid shell handler close context")
  (events/unregister-handler! (:runtime ctx) :shell/engine)
  (require-valid! (s/keys :req-un [::unregistered])
                  {:unregistered :shell/engine}
                  "Invalid shell handler close result"))

(lifecycle/defresource shell-pool
  "Own the shell worker pool for the lifetime of the runtime."
  {:open 'millhouse.spools.executors.shell/open-shell-pool!
   :close 'millhouse.spools.executors.shell/close-shell-pool!
   :scope :runtime})

(lifecycle/defresource shell-handler
  "Own the shell event handler for the lifetime of the module."
  {:open 'millhouse.spools.executors.shell/open-shell-handler!
   :close 'millhouse.spools.executors.shell/close-shell-handler!
   :after #{:shell-pool}})
