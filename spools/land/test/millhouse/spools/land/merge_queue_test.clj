(ns millhouse.spools.land.merge-queue-test
  "Exercise queue behavior through public operations in disposable runtimes."
  (:require [clojure.test :refer [deftest is]]
            [millhouse.spools.land.merge-queue :as queue]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.test-support :refer [with-runtime]]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- start-run! [id]
  (workflow/start!
   id
   (workflow/workflow
    "Landing fixture" {:attributes {"workflow/family" "land"}}
    (workflow/gate :turn "Await turn" :merge-turn)
    (workflow/step :work "Protected work" :self :depends-on [:turn])
    (workflow/gate :release "Release turn" :merge-release :depends-on [:work])
    (workflow/step :tidy "Housekeeping" :self :depends-on [:release]))
   {:branch id}))

(defn reject-marked-close
  "Reject a marked workflow close through the existing lifecycle-hook seam."
  [ctx]
  (when (some (fn [{:keys [after]}]
                (and (= "closed" (:state after))
                     (true? (attr-get after :test/reject-close))))
              (:batch/updated ctx))
    (throw (ex-info "Injected completion failure" {}))))

(defn- reject-close! [rt gate]
  (hooks/register-hook! rt :test/reject-close #{:batch/apply-before-commit}
                        'millhouse.spools.land.merge-queue-test/reject-marked-close)
  (weaver/update! rt (:id gate) {:attributes {:test/reject-close true}}))

(defn- allow-close! [rt gate]
  (weaver/update! rt (:id gate) {:attributes {:test/reject-close nil}}))

(deftest fifo-retains-position-through-failure-and-timeout
  (with-runtime
    (fn [rt _]
      (start-run! "first")
      (start-run! "second")
      (let [a (queue/join! rt "first")
            b (queue/join! rt "second")]
        (is (= (:id a) (:id (queue/join! rt "first"))))
        (is (nil? (queue/grant! rt "second")))
        (queue/grant! rt "first")
        (let [work (first (workflow/ready "first"))]
          (weaver/update! rt (:id work) {:attributes {:gate/error "checks failed"}})
          (is (nil? (queue/grant! rt "second")))
          (let [waiting (queue/await-turn rt (:id b) 0)]
            (is (:timeout waiting))
            (is (= 1 (:position waiting)))
            (is (= "checks failed" (get-in waiting [:ahead 0 :frontier 0 :error])))))
        (is (= [(:id a) (:id b)] (mapv :id (:entries (queue/status rt)))))
        (is (= "first" (get-in (queue/status rt) [:lock :run-id])))))))

(deftest release-allows-the-next-run-before-housekeeping-completes
  (with-runtime
    (fn [rt _]
      (start-run! "first")
      (start-run! "second")
      (let [a (queue/join! rt "first")]
        (queue/join! rt "second")
        (queue/grant! rt "first")
        (workflow/complete! "first")
        (queue/release! rt "first")
        (is (= "merged" (:outcome (queue/status rt (:id a)))))
        (is (= "Housekeeping" (:title (first (workflow/ready "first")))))
        (is (not (workflow/done? "first")))
        (queue/grant! rt "second")
        (is (= "second" (get-in (queue/status rt) [:lock :run-id])))))))

(deftest failed-grant-completion-retains-the-turn-for-retry
  (with-runtime
    (fn [rt _]
      (start-run! "first")
      (let [entry (queue/join! rt "first")
            gate (first (workflow/ready "first"))]
        (reject-close! rt gate)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Lifecycle hook failed"
                              (queue/grant! rt "first")))
        (is (:holds-lock (queue/status rt (:id entry))))
        (is (= (:id gate) (:id (first (workflow/ready "first")))))
        (allow-close! rt gate)
        (queue/grant! rt "first")
        (is (= "Protected work" (:title (first (workflow/ready "first")))))))))

(deftest release-retry-does-not-release-the-next-owners-lock
  (with-runtime
    (fn [rt _]
      (start-run! "first")
      (start-run! "second")
      (queue/join! rt "first")
      (queue/join! rt "second")
      (queue/grant! rt "first")
      (workflow/complete! "first")
      (let [release (first (workflow/ready "first"))]
        (reject-close! rt release)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Lifecycle hook failed"
                              (queue/release! rt "first")))
        (queue/grant! rt "second")
        (allow-close! rt release)
        (queue/release! rt "first")
        (is (= "second" (get-in (queue/status rt) [:lock :run-id])))
        (is (= "Housekeeping" (:title (first (workflow/ready "first")))))))))

(deftest concurrent-repeated-joins-reserve-one-position-per-run
  (with-runtime
    (fn [rt _]
      (start-run! "first")
      (start-run! "second")
      (let [ready (CountDownLatch. 4)
            go (CountDownLatch. 1)
            requests (mapv (fn [id]
                             (future
                               (.countDown ready)
                               (when-not (.await go 10 TimeUnit/SECONDS)
                                 (throw (ex-info "Join fixture was not released" {})))
                               (queue/join! rt id)))
                           ["first" "second" "first" "second"])]
        (try
          (is (.await ready 10 TimeUnit/SECONDS))
          (finally (.countDown go)))
        (let [results (mapv #(deref % 30000 ::timeout) requests)]
          (is (not-any? #{::timeout} results))
          (is (= 2 (count (set (map :id results))))))
        (let [entries (:entries (queue/status rt))]
          (is (= #{"first" "second"} (set (map :run-id entries))))
          (is (= [0 1] (mapv :sequence entries))))))))

(deftest explicit-runtime-queue-operations-do-not-mutate-the-ambient-world
  (with-runtime
    (fn [runtime-a _]
      (current/with-runtime runtime-a
        (start-run! "isolated"))
      (with-runtime
        (fn [runtime-b _]
          ;; The ambient binding is runtime-b while the explicit queue target is
          ;; runtime-a. An accidental current/runtime lookup would mutate B.
          (queue/join! runtime-a "isolated")
          (is (= ["isolated"]
                 (mapv :run-id (:entries (queue/status runtime-a)))))
          (is (empty? (:entries (queue/status runtime-b)))))))))

(deftest queue-scanner-grants-only-the-head-and-reports-errors-on-the-gate
  (with-runtime
    (fn [rt _]
      (start-run! "first")
      (start-run! "second")
      (queue/join! rt "first")
      (queue/join! rt "second")
      (let [gate (first (workflow/ready "first"))]
        (reject-close! rt gate)
        (queue/scan! rt)
        (is (re-find #"Injected completion" (attr-get (weaver/show rt (:id gate)) :gate/error)))
        (is (= "merge-turn" (:gate (first (workflow/ready "second")))))
        (allow-close! rt gate)
        (weaver/update! rt (:id gate) {:attributes {:gate/error nil}})
        (queue/scan! rt)
        (is (= "Protected work" (:title (first (workflow/ready "first")))))
        (is (= 2 (count (:entries (queue/status rt)))))))))

(deftest withdrawing-a-waiter-keeps-the-head-lock-and-replaces-only-its-own-run
  (with-runtime
    (fn [rt _]
      (start-run! "first")
      (start-run! "second")
      (queue/join! rt "first")
      (let [entry (queue/join! rt "second")
            previous (:id (workflow/current-root "second"))]
        (queue/grant! rt "first")
        (is (= "withdrawn" (:outcome (queue/withdraw! rt (:id entry) "Scope changed"))))
        (is (= "first" (get-in (queue/status rt) [:lock :run-id])))
        (is (not= previous (:id (workflow/current-root "second"))))
        (is (= "Scope changed" (attr-get (workflow/current-root "second") :land/abort-reason)))
        (is (= "Return the card to claimed" (:title (first (workflow/ready "second")))))
        (is (= "withdrawn" (:outcome (queue/withdraw! rt (:id entry) "Repeated request"))))))))

(deftest failed-abort-cutover-keeps-the-turn-until-a-successful-retry
  (with-runtime
    (fn [rt _]
      (start-run! "first")
      (start-run! "second")
      (let [entry (queue/join! rt "first")
            root (workflow/current-root "first")]
        (queue/join! rt "second")
        (queue/grant! rt "first")
        (reject-close! rt entry)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Lifecycle hook failed"
                              (queue/withdraw! rt (:id entry) "Repair elsewhere")))
        (is (= (:id root) (:id (workflow/current-root "first"))))
        (is (:holds-lock (queue/status rt (:id entry))))
        (is (nil? (queue/grant! rt "second")))
        (allow-close! rt entry)
        (queue/withdraw! rt (:id entry) "Repair elsewhere")
        (queue/grant! rt "second")
        (is (= "second" (get-in (queue/status rt) [:lock :run-id])))))))

(deftest withdrawal-cannot-relabel-an-already-submitted-merge-as-aborted
  (with-runtime
    (fn [rt _]
      (start-run! "first")
      (let [entry (queue/join! rt "first")
            root (workflow/current-root "first")]
        (queue/grant! rt "first")
        (weaver/update! rt (:id (first (workflow/ready "first")))
                        {:attributes {:land/irreversible true}})
        (workflow/complete! "first")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Merge may already have been submitted"
                              (queue/withdraw! rt (:id entry) "Stop")))
        (is (:holds-lock (queue/status rt (:id entry))))
        (is (= (:id root) (:id (workflow/current-root "first"))))
        (queue/release! rt "first")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"completed merge"
                              (queue/withdraw! rt (:id entry) "Stop")))))))

(deftest queue-handler-automatically-advances-ready-turns
  (with-runtime
    (fn [rt _]
      (queue/open-handler! {:runtime rt})
      (try
        (start-run! "first")
        (test-alpha/await-quiescent! rt)
        (start-run! "second")
        (test-alpha/await-quiescent! rt)
        (is (= "first" (get-in (queue/status rt) [:lock :run-id])))
        (is (= "Protected work" (:title (first (workflow/ready "first")))))
        (is (= "merge-turn" (:gate (first (workflow/ready "second")))))
        (workflow/complete! "first")
        (test-alpha/await-quiescent! rt)
        (is (= "Housekeeping" (:title (first (workflow/ready "first")))))
        (is (= "second" (get-in (queue/status rt) [:lock :run-id])))
        (is (= "Protected work" (:title (first (workflow/ready "second")))))
        (finally
          (queue/close-handler! {:runtime rt}))))))
