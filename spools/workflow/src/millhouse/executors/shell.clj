(ns millhouse.executors.shell
  "Shell adapter for the common Workflow execution lifecycle.

  Mill owns processes and retained terminal evidence. Stable owner/key adoption
  never relaunches missing custody; only the common driver delivers and retries."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.process.alpha :as process]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.workflow.execution :as execution]
            [millhouse.workflow.internal.execution.data :as data])
  (:import [java.nio.charset StandardCharsets]))

(def ^:private output-tail-bytes (* 16 1024))
(def ^:private custody-owner :millhouse/shell-executor)

(s/def :shell/argv (s/coll-of string? :kind sequential? :min-count 1))
(s/def :shell/cwd data/nonblank?)
(s/def :shell/timeout-secs pos-int?)
(s/def ::request (s/keys :req [:shell/argv] :opt [:shell/cwd :shell/timeout-secs]))
(s/def ::result (s/and map? data/json?))

(defn request
  "Project argv, cwd and timeout from the complete captured gate image."
  [{:keys [gate]}]
  (into {:shell/argv (attr-get gate :shell/argv)}
        (keep (fn [key] (when-some [value (attr-get gate key)] [key value])))
        [:shell/cwd :shell/timeout-secs]))

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
      (let [start (loop [index (- (alength bytes) output-tail-bytes)]
                    ;; The re-encoded string is valid UTF-8. Skip continuation
                    ;; bytes at the cut so decoding cannot expand the byte cap.
                    (if (= 128 (bit-and 192 (aget bytes index)))
                      (recur (inc index)) index))]
        (String. bytes (int start) (int (- (alength bytes) start)) StandardCharsets/UTF_8)))))

(defn- unknown [reference code message]
  {:status :unknown :reference reference
   :reason {:code code :message message :data {}}})

(defn- observation [record]
  (let [{:keys [handle phase cancellation launch-failure exit]} record]
    (cond
      (= :uncertain (:stop cancellation))
      (unknown handle "shell/uncertain-stop" "Shell cancellation is uncertain; retain custody")

      (not= :terminal phase)
      {:status :pending :phase :running :reference handle}

      :else
      (let [outcome (cond cancellation :cancelled launch-failure :failed
                          (zero? (:code exit)) :succeeded :else :failed)
            message (cond cancellation (str "shell command cancelled: " (:reason cancellation))
                          launch-failure (str "shell command failed to launch: " (:message launch-failure))
                          (not= :succeeded outcome) (str "shell command exited " (:code exit)))]
        {:status :terminal :outcome outcome :settlement :settled :reference handle
         :value {:exit-code (:code exit) :output (custody-output (:output record))}
         :error (when message {:code (str "shell/" (name outcome)) :message message :data {}})
         :evidence {"handle" handle "key" (:key record)}}))))

(defn- owned-record [rt {:keys [attempt-id reference]}]
  (let [matches (filterv #(= attempt-id (:key %)) (process/list-owned rt custody-owner))]
    (when (> (count matches) 1)
      (throw (ex-info "Multiple custody records match the attempt" {:attempt-id attempt-id})))
    (when-let [record (first matches)]
      (when (and reference (not= reference (:handle record)))
        (throw (ex-info "Retained custody handle changed" {:attempt-id attempt-id :reference reference})))
      record)))

(defn start!
  "Launch shell-free argv using the stable attempt key and Mill owner."
  [rt {:keys [attempt-id request]}]
  (observation
   (process/launch! rt custody-owner attempt-id
                    {:argv (vec (:shell/argv request))
                     :cwd (.getAbsolutePath (io/file (or (:shell/cwd request) ".")))
                     :env {}})))

(defn observe!
  "Adopt only the exact owner/key, including a lost launch response.

  Missing custody, including a new Mill lifetime, is unknown, not permission
  to launch. Transport failures propagate to the common unknown observation."
  [rt {:keys [reference] :as context}]
  (if-let [record (owned-record rt context)]
    (observation record)
    (unknown reference "shell/custody-missing" "Shell custody is missing; settlement is unknown")))

(defn stop!
  "Request cancellation of the exact owned handle, then observe settlement."
  [rt {:keys [reference] :as context}]
  (if-let [record (owned-record rt context)]
    (do (when-not (= :terminal (:phase record))
          (process/cancel! rt custody-owner (:handle record)))
        (observe! rt (assoc context :reference (:handle record))))
    (unknown reference "shell/custody-missing" "Shell custody is missing; cancellation is unknown")))

(defn acknowledge!
  "Acknowledge exact retained terminal evidence after durable common delivery.

  Missing evidence cannot confirm an acknowledgement whose response was lost."
  [rt context]
  (let [record (owned-record rt context)]
    (when-not (and record (= :terminal (:phase record))
                   (not= :uncertain (get-in record [:cancellation :stop])))
      (throw (ex-info "Terminal Shell custody acknowledgement is unknown" {})))
    (process/acknowledge! rt custody-owner (:handle record))
    {:status :acknowledged}))

(def executor
  "Inert Shell descriptor; select shell-engine to activate common execution."
  {:waiter :shell :revision "shell-v1"
   :request 'millhouse.executors.shell/request :request-spec ::request :result-spec ::result
   :start 'millhouse.executors.shell/start! :observe 'millhouse.executors.shell/observe!
   :stop 'millhouse.executors.shell/stop! :acknowledge 'millhouse.executors.shell/acknowledge!})

(defn open-shell-engine!
  "Select Shell after refusing unresolved legacy execution evidence."
  [{:keys [runtime]}]
  (when-let [gate (first (weaver/list runtime
                                      [:and [:= :state "active"] [:= [:attr "workflow/gate"] "shell"]
                                       [:or [:exists [:attr "shell/running"]]
                                        [:exists [:attr "shell/attempt-id"]]
                                        [:exists [:attr "shell/custody-handle"]]
                                        [:and [:exists [:attr "gate/error"]]
                                         [:not [:exists [:attr "execution/current"]]]]]] {}))]
    (throw (ex-info "Drain legacy Shell execution before cutover" {:gate-id (:id gate)})))
  (execution/open! runtime executor))

(defn close-shell-engine!
  "Remove admission, preserving Mill-owned commands on planned Weaver shutdown.

  Module removal records stop intent instead; it never guesses settlement."
  [{:keys [runtime resource] :as context}]
  (execution/close! runtime resource (:effect/phase context)))

(lifecycle/defresource shell-engine
  "Select the common Shell driver; Mill retains process custody across shutdown."
  {:open 'millhouse.executors.shell/open-shell-engine!
   :close 'millhouse.executors.shell/close-shell-engine!})
