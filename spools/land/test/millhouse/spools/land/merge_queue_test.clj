(ns millhouse.spools.land.merge-queue-test
  "Exercise queue behavior through public operations in disposable runtimes."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [millhouse.spools.executors.shell :as shell]
            [millhouse.spools.land.merge-queue :as queue]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.process.alpha :as process]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.test-support :as test-support :refer [with-runtime]]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.io File]
           [java.util.concurrent CountDownLatch Executor Executors TimeUnit]))

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

(def ^:private branch-head "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def ^:private merge-commit "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn- start-repair-run!
  [id]
  (workflow/start!
   id
   (workflow/workflow
    "Landing repair fixture"
    {:attributes {"workflow/family" "land"
                  "land/version" 3
                  "land/stage" "merge"}}
    (workflow/gate :turn "Await turn" :merge-turn)
    (workflow/gate :prepare "Prepare merge" :shell
                   :depends-on [:turn]
                   :attributes {"shell/argv" ["sh" "-c" "prepare" "land-prepare"]})
    (workflow/gate :merge "Merge PR" :shell
                   :depends-on [:prepare]
                   :attributes {"shell/argv" ["sh" "-c" "merge" "land-merge"
                                              "42" "Subject" "Body" id]
                                "land/irreversible" true})
    (workflow/gate :pull "Pull main" :shell
                   :depends-on [:merge]
                   :attributes {"shell/argv" ["sh" "-c" "pull" "land-pull"]})
    (workflow/gate :release "Release turn" :merge-release
                   :depends-on [:pull])
    (workflow/gate :cleanup "Cleanup" :shell
                   :depends-on [:release]
                   :attributes {"shell/argv" ["sh" "-c" "cleanup" "land-cleanup"]}))
   {:branch id :pr-number 42}))

(defn- start-land-merge-run!
  [id]
  (workflow/start!
   id
   @(requiring-resolve 'millhouse.spools.land/land-merge)
   {:feature id
    :branch id
    :worktree (System/getProperty "user.dir")
    :subject (str "Land " id)
    :body (str "Land " id)
    :pr-number 42}))

(defn- ready-gate
  [run-id waiter]
  (first (workflow/ready-gates run-id waiter)))

(defn- completion-rejected?
  [f]
  (let [error (try (f) nil (catch clojure.lang.ExceptionInfo e e))]
    (and (= "Lifecycle hook failed" (ex-message error))
         (= "land/queue-gate-completion-forbidden"
            (:hook/cause-code (ex-data error))))))

(defn- install-guard!
  [rt]
  (queue/open-completion-guard! {:runtime rt}))

(defn- close-guard!
  [rt]
  (queue/close-completion-guard! {:runtime rt}))

(defn- queue-snapshot
  [rt run-id gate-id]
  (let [root (workflow/current-root run-id)]
    {:root (weaver/show rt (:id root))
     :run (graph/subgraph rt [(:id root)] {:type "parent-of"})
     :gate (weaver/show rt gate-id)
     :ready (workflow/ready run-id)
     :queue (queue/status rt)}))

(defn- generic-completion-attacks
  [rt run-id gate-id]
  [["complete! with spoofed actor and disguised waiter"
    #(workflow/complete! run-id
                         {:step gate-id
                          :by-identity "merge-turn"
                          :attributes {"workflow/gate" "human"
                                       "land/queue-completion" "grant"}
                          :context {:spoofed true}})]
   ["complete! with executor provenance but without queue custody"
    #(workflow/complete! run-id
                         {:step gate-id
                          :executor "merge-turn"})]
   ["advance! with spoofed actor and disguised waiter"
    #(workflow/advance! run-id
                        {:step gate-id
                         :by-identity "merge-release"
                         :attributes {"workflow/gate" "code"}})]
   ["worker complete request"
    #(workflow/run-complete!
      {:run-id run-id :step gate-id :by-identity "merge-turn"
       :attributes {"workflow/gate" "shell"}
       :context {:worker-spoofed true}})]
   ["worker next request"
    #(workflow/run-next! {:run-id run-id :step gate-id :by-identity "merge-release"})]
   ["workflow complete CLI delegation"
    #(weaver/op! rt :workflow
                 ["complete" run-id "--step" gate-id "--by-identity" "merge-turn"
                  "--attributes" (json/write-str {"workflow/gate" "human"})])]
   ["workflow next CLI delegation"
    #(weaver/op! rt :workflow
                 ["next" run-id "--step" gate-id "--by-identity" "merge-release"])]])

(defn- assert-generic-completion-rejected!
  [rt run-id gate-id]
  (let [before (queue-snapshot rt run-id gate-id)]
    (doseq [[label attack] (generic-completion-attacks rt run-id gate-id)]
      (testing label
        (is (completion-rejected? attack))
        (is (= before (queue-snapshot rt run-id gate-id)))))))

(defn- turn-repair-request
  [root gate]
  {:kind :skipped-turn
   :by-identity "repair-operator"
   :reason "Restore a pre-guard skipped queue turn"
   :evidence {:root-id (:id root)
              :gate-id (:id gate)
              :irreversible-work "not-started"}})

(defn- release-repair-request
  [root release entry lock]
  {:kind :skipped-release
   :by-identity "repair-operator"
   :reason "Settle a pre-guard skipped queue release"
   :evidence {:root-id (:id root)
              :gate-id (:id release)
              :entry-id (:id entry)
              :lock-id (:id lock)
              :pr-number 42
              :pr-state "MERGED"
              :base-branch "main"
              :pr-head branch-head
              :merge-commit merge-commit
              :canonical-main merge-commit}})

(defn- complete-shell!
  [run-id output]
  (let [gate (first (workflow/ready run-id))]
    (workflow/complete! run-id
                        {:step (:id gate)
                         :executor "shell"
                         :attributes {"shell/exit-code" 0
                                      "shell/output" output}})
    gate))

(defn- seed-skipped-release!
  [rt run-id]
  (let [root (workflow/current-root run-id)
        entry (queue/join! rt run-id)]
    (queue/grant! rt run-id)
    (complete-shell! run-id (str "land prepare: validated " run-id " at " branch-head))
    (complete-shell! run-id "merge submitted and PR verified MERGED")
    (complete-shell! run-id (str "Fast-forward\n " merge-commit))
    (let [release (ready-gate run-id "merge-release")
          lock-id (get-in (queue/status rt) [:lock :id])]
      (workflow/complete! run-id {:step (:id release) :executor "merge-release"})
      {:root root
       :entry entry
       :lock (weaver/show rt lock-id)
       :release release})))

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

(defn- activate-worker-cli!
  [rt]
  (test-support/activate-spool! rt :test/workflow 'millhouse.spools.workflow)
  (test-support/activate-spool! rt :test/workflow-cli
                                'millhouse.test-modules.workflow-cli
                                :after [:test/workflow]))

(deftest generic-completion-cannot-steal-another-runs-turn
  (with-runtime
    (fn [rt _]
      (activate-worker-cli! rt)
      (start-run! "first")
      (start-run! "second")
      (install-guard! rt)
      (try
        (queue/join! rt "first")
        (queue/join! rt "second")
        (queue/grant! rt "first")
        (let [turn (:id (ready-gate "second" "merge-turn"))]
          (assert-generic-completion-rejected! rt "second" turn)
          (is (= "Protected work" (:title (first (workflow/ready "first")))))
          (is (= "merge-turn" (:gate (first (workflow/ready "second")))))
          (is (= "first" (get-in (queue/status rt) [:lock :run-id]))))
        (finally
          (close-guard! rt))))))

(deftest generic-completion-cannot-skip-the-owners-release
  (with-runtime
    (fn [rt _]
      (activate-worker-cli! rt)
      (start-run! "owner")
      (install-guard! rt)
      (try
        (queue/join! rt "owner")
        (queue/grant! rt "owner")
        (workflow/complete! "owner")
        (let [release (:id (ready-gate "owner" "merge-release"))]
          (assert-generic-completion-rejected! rt "owner" release)
          (is (= "owner" (get-in (queue/status rt) [:lock :run-id])))
          (is (= "merge-release" (:gate (first (workflow/ready "owner")))))
          (queue/release! rt "owner")
          (is (= "Housekeeping" (:title (first (workflow/ready "owner"))))))
        (finally
          (close-guard! rt))))))

(deftest completion-guard-leaves-unrelated-gates-alone
  (with-runtime
    (fn [rt _]
      (workflow/start!
       "unrelated"
       (workflow/workflow
        "Unrelated gates"
        (workflow/gate :human "Human" :human)
        (workflow/gate :shell "Shell" :shell :depends-on [:human])
        (workflow/gate :code "Code" :code :depends-on [:shell]))
       {})
      (install-guard! rt)
      (try
        (doseq [actor ["human" "shell" "code"]]
          (let [gate (first (workflow/ready "unrelated"))]
            (workflow/complete! "unrelated" {:step (:id gate) :by-identity actor})))
        (is (workflow/done? "unrelated"))
        (finally
          (close-guard! rt))))))

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

(deftest completion-guard-allows-atomic-withdrawal
  (with-runtime
    (fn [rt _]
      (start-run! "withdraw-guarded")
      (let [entry (queue/join! rt "withdraw-guarded")]
        (install-guard! rt)
        (try
          (queue/grant! rt "withdraw-guarded")
          (is (= "withdrawn"
                 (:outcome (queue/withdraw! rt (:id entry) "Guarded withdrawal"))))
          (is (= "Return the card to claimed"
                 (:title (first (workflow/ready "withdraw-guarded")))))
          (finally
            (close-guard! rt)))))))

(deftest skipped-turn-repair-restores-the-exact-gate-and-reservation
  (with-runtime
    (fn [rt _]
      (start-repair-run! "repair-turn")
      (let [root (workflow/current-root "repair-turn")
            gate (ready-gate "repair-turn" "merge-turn")
            entry (queue/join! rt "repair-turn")
            sequence (attr-get entry :queue/sequence)
            request (turn-repair-request root gate)]
        (workflow/complete! "repair-turn" {:step (:id gate) :executor "merge-turn"})
        (install-guard! rt)
        (try
          (is (= (:id entry) (:entry-id (queue/repair! rt "repair-turn" request))))
          (is (= sequence (attr-get (weaver/show rt (:id entry)) :queue/sequence)))
          (is (= "active" (:state (weaver/show rt (:id gate)))))
          (queue/grant! rt "repair-turn")
          (is (= "Prepare merge" (:title (first (workflow/ready "repair-turn")))))
          (is (= (:id entry) (:entry-id (queue/repair! rt "repair-turn" request)))
              "the exact repeat is idempotent after normal grant resumes")
          (finally
            (close-guard! rt)))))))

(deftest skipped-turn-repair-rewinds-completed-prepare-behind-normal-grant
  (with-runtime
    (fn [rt _]
      (start-land-merge-run! "owner-a")
      (start-land-merge-run! "skipped-b")
      (queue/join! rt "owner-a")
      (let [root (workflow/current-root "skipped-b")
            turn (ready-gate "skipped-b" "merge-turn")
            entry (queue/join! rt "skipped-b")
            sequence (attr-get entry :queue/sequence)
            request (turn-repair-request root turn)]
        (queue/grant! rt "owner-a")
        (workflow/complete! "skipped-b" {:step (:id turn) :executor "merge-turn"})
        (let [prepare (complete-shell!
                       "skipped-b"
                       (str "land prepare: validated skipped-b at " branch-head))
              merge-gate (first (workflow/ready "skipped-b"))]
          (is (true? (attr-get (weaver/show rt (:id merge-gate)) :land/irreversible))
              "the actual Land topology exposes merge after the skipped turn and prepare")
          (install-guard! rt)
          (try
            (queue/repair! rt "skipped-b" request)
            (is (= "owner-a" (get-in (queue/status rt) [:lock :run-id])))
            (is (= sequence (attr-get (weaver/show rt (:id entry)) :queue/sequence)))
            (is (= [(:id turn)] (mapv :id (workflow/ready "skipped-b")))
                "repair restores an ownership-blocked frontier")
            (is (nil? (attr-get (weaver/show rt (:id merge-gate)) :gate/error))
                "the irreversible gate is topology-blocked after fences clear")
            (is (= "active" (:state (weaver/show rt (:id prepare))))
                "the out-of-turn prepare is rewound")
            (is (nil? (attr-get (weaver/show rt (:id prepare)) :shell/exit-code)))
            (is (nil? (queue/grant! rt "skipped-b"))
                "the successor cannot bypass the current owner")
            (is (= [(:id turn)] (mapv :id (workflow/ready "skipped-b"))))

            (complete-shell!
             "owner-a"
             (str "land prepare: validated owner-a at " branch-head))
            (complete-shell! "owner-a" "merge submitted")
            (complete-shell! "owner-a" (str "Fast-forward\n " merge-commit))
            (queue/release! rt "owner-a")
            (queue/grant! rt "skipped-b")
            (is (= [(:id prepare)] (mapv :id (workflow/ready "skipped-b")))
                "normal grant exposes only the rewound reversible preparation")
            (complete-shell!
             "skipped-b"
             (str "land prepare: validated skipped-b at " branch-head))
            (is (= (:id merge-gate) (:id (first (workflow/ready "skipped-b"))))
                "irreversible merge becomes ready only after grant and fresh preparation")
            (finally
              (close-guard! rt))))))))

(deftest skipped-turn-repair-refuses-a-stale-shell-ready-snapshot
  (with-runtime
    (fn [rt _]
      (start-land-merge-run! "race-owner-a")
      (start-land-merge-run! "race-skipped-b")
      (queue/join! rt "race-owner-a")
      (queue/join! rt "race-skipped-b")
      (let [root (workflow/current-root "race-skipped-b")
            turn (ready-gate "race-skipped-b" "merge-turn")
            request (turn-repair-request root turn)]
        (queue/grant! rt "race-owner-a")
        (workflow/complete! "race-skipped-b" {:step (:id turn) :executor "merge-turn"})
        (complete-shell!
         "race-skipped-b"
         (str "land prepare: validated race-skipped-b at " branch-head))
        (let [merge-gate (first (workflow/ready "race-skipped-b"))
              real-quiesce shell/quiesce-run!
              real-ready weaver/ready
              quiesced (CountDownLatch. 1)
              continue-repair (CountDownLatch. 1)
              stale-selected (CountDownLatch. 1)
              continue-scan (CountDownLatch. 1)
              scanner-thread (atom nil)
              launches (atom [])
              inline-executor
              (reify Executor
                (execute [_ task] (.run ^Runnable task)))]
          (install-guard! rt)
          (try
            (with-redefs-fn
              {#'shell/quiesce-run!
               (fn [run-id reason]
                 (let [result (real-quiesce run-id reason)]
                   (when (= "race-skipped-b" run-id)
                     (.countDown quiesced)
                     (when-not (.await continue-repair 30 TimeUnit/SECONDS)
                       (throw (ex-info "Repair interleaving was not released" {}))))
                   result))
               #'weaver/ready
               (fn [runtime query params]
                 (let [result (real-ready runtime query params)]
                   (when (and (identical? (Thread/currentThread) @scanner-thread)
                              (some #(= (:id merge-gate) (:id %)) result))
                     (.countDown stale-selected)
                     (when-not (.await continue-scan 30 TimeUnit/SECONDS)
                       (throw (ex-info "Shell scan interleaving was not released" {}))))
                   result))
               #'shell/worker-executor (constantly inline-executor)
               #'process/launch!
               (fn [& args]
                 (swap! launches conj args)
                 (throw (ex-info "Refused intercepted process launch" {})))}
              (fn []
                (let [repair (future (queue/repair! rt "race-skipped-b" request))]
                  (try
                    (is (.await quiesced 30 TimeUnit/SECONDS)
                        "repair completed real shell quiescence")
                    (let [scan (future
                                 (reset! scanner-thread (Thread/currentThread))
                                 (shell/scan!))]
                      (try
                        (is (.await stale-selected 30 TimeUnit/SECONDS)
                            "real shell scan paused with the stale merge-ready snapshot")
                        (.countDown continue-repair)
                        (is (not= ::timeout (deref repair 30000 ::timeout))
                            "repair completed while the stale scanner remained paused")
                        (is (= "race-owner-a"
                               (get-in (queue/status rt) [:lock :run-id])))
                        (is (= [(:id turn)]
                               (mapv :id (workflow/ready "race-skipped-b"))))
                        (finally
                          (.countDown continue-scan)))
                      (is (not= ::timeout (deref scan 30000 ::timeout)))
                      (is (seq @launches)
                          "the process boundary intercepted and refused real dispatch")
                      (is (not-any?
                           (fn [args]
                             (let [argv (:argv (last args))]
                               (and (= "land-merge" (nth argv 3 nil))
                                    (= "race-skipped-b" (last argv)))))
                           @launches)
                          "stale B merge dispatch was refused before process launch"))
                    (finally
                      (.countDown continue-repair)
                      (.countDown continue-scan))))))
            (finally
              (close-guard! rt))))))))

(deftest skipped-turn-repair-retires-cancelled-prepare-before-unfreezing
  (with-runtime
    (fn [rt _]
      (start-run! "inflight-owner-a")
      (start-land-merge-run! "inflight-skipped-b")
      (queue/join! rt "inflight-owner-a")
      (queue/join! rt "inflight-skipped-b")
      (let [root (workflow/current-root "inflight-skipped-b")
            turn (ready-gate "inflight-skipped-b" "merge-turn")
            request (turn-repair-request root turn)
            records (atom {})
            terminal-record (atom nil)
            delayed-result (atom nil)
            fresh-result (atom nil)
            reconciliation-count (atom 0)
            dispatch-finished (CountDownLatch. 1)
            first-task? (atom true)
            fresh-dispatch-finished (CountDownLatch. 1)
            fresh-task? (atom false)
            fresh-cancellation? (atom false)
            observer-paused (CountDownLatch. 1)
            continue-observer (CountDownLatch. 1)
            observer-finished (CountDownLatch. 1)
            fresh-observer-paused (CountDownLatch. 1)
            continue-fresh-observer (CountDownLatch. 1)
            fresh-observer-finished (CountDownLatch. 1)
            main-thread (Thread/currentThread)
            worker-pool (Executors/newFixedThreadPool 2)
            worker-executor
            (reify Executor
              (execute [_ task]
                (let [first? (compare-and-set! first-task? true false)
                      fresh? (compare-and-set! fresh-task? true false)]
                  (.execute worker-pool
                            ^Runnable
                            (fn []
                              (try
                                (.run ^Runnable task)
                                (finally
                                  (when first?
                                    (.countDown dispatch-finished))
                                  (when fresh?
                                    (.countDown fresh-dispatch-finished)))))))))
            terminal-reconcile @#'shell/terminal-reconcile!
            output-file (doto (File/createTempFile "land-prepare-output" ".txt")
                          (.deleteOnExit))
            error-file (doto (File/createTempFile "land-prepare-error" ".txt")
                         (.deleteOnExit))]
        (spit output-file "")
        (spit error-file "")
        (queue/grant! rt "inflight-owner-a")
        (workflow/complete! "inflight-skipped-b" {:step (:id turn) :executor "merge-turn"})
        (install-guard! rt)
        (try
          (with-redefs-fn
            {#'shell/worker-executor (constantly worker-executor)
             #'shell/terminal-reconcile!
             (fn [& args]
               (if (identical? main-thread (Thread/currentThread))
                 (apply terminal-reconcile args)
                 (let [fresh? (= 2 (swap! reconciliation-count inc))
                       paused (if fresh? fresh-observer-paused observer-paused)
                       continue (if fresh? continue-fresh-observer continue-observer)
                       finished (if fresh? fresh-observer-finished observer-finished)
                       result-atom (if fresh? fresh-result delayed-result)]
                   (.countDown paused)
                   (when-not (.await continue 5 TimeUnit/SECONDS)
                     (throw (ex-info "Timed out waiting to resume shell observer" {})))
                   (try
                     (let [result (apply terminal-reconcile args)]
                       (reset! result-atom result)
                       result)
                     (finally
                       (.countDown finished))))))
             #'process/launch!
             (fn [_runtime owner key _request]
               (let [record {:handle (str "inflight-" key)
                             :owner owner
                             :key key
                             :phase :running
                             :output {:stdout-ref (.getAbsolutePath output-file)
                                      :stderr-ref (.getAbsolutePath error-file)}}]
                 (swap! records assoc (:handle record) record)
                 record))
             #'process/list-owned (fn [_runtime _owner] (vec (vals @records)))
             #'process/get (fn [_runtime handle] (get @records handle))
             #'process/cancel!
             (fn [_runtime _owner handle]
               (let [record (assoc (get @records handle)
                                   :phase :terminal
                                   :cancellation {:reason "skipped-turn repair"})]
                 (reset! terminal-record record)
                 (swap! records assoc handle record)
                 (let [paused (if @fresh-cancellation?
                                fresh-observer-paused
                                observer-paused)]
                   (when-not (.await paused 5 TimeUnit/SECONDS)
                     (throw (ex-info "Shell observer did not reach terminal reconciliation"
                                     {}))))
                 record))
             #'process/acknowledge!
             (fn [_runtime _owner handle]
               (swap! records dissoc handle)
               {:acknowledged true :handle handle})}
            (fn []
              (shell/scan!)
              (is (.await dispatch-finished 5 TimeUnit/SECONDS)
                  "actual prepare dispatch finished claiming custody")
              (let [prepare (first (workflow/ready "inflight-skipped-b"))
                    attempt (first
                             (filter #(= (:id prepare) (:gate-id %))
                                     (shell/read-shell-attempts {:runtime rt})))]
                (is (some? (:custody-handle attempt))
                    "actual prepare dispatch is in flight before repair")
                (queue/repair! rt "inflight-skipped-b" request)
                (is (some? @terminal-record)
                    "real quiescence cancelled prepare into a terminal custody fact")
                (is (empty? @records)
                    "repair acknowledged settled custody before removing its fence")

                (.countDown continue-observer)
                (is (.await observer-finished 5 TimeUnit/SECONDS)
                    "the original observer completed delayed reconciliation")
                (is (= :stale @delayed-result)
                    "delayed terminal reconciliation sees retired custody")

                (workflow/complete! "inflight-owner-a")
                (queue/release! rt "inflight-owner-a")
                (queue/grant! rt "inflight-skipped-b")
                (let [current (weaver/show rt (:id prepare))]
                  (is (nil? (attr-get current :gate/error)))
                  (is (nil? (attr-get current :shell/attempt-id)))
                  (is (= (:id prepare)
                         (:id (first (workflow/ready "inflight-skipped-b"))))
                      "fresh preparation remains available after normal grant"))

                (reset! fresh-task? true)
                (shell/scan!)
                (is (.await fresh-dispatch-finished 5 TimeUnit/SECONDS)
                    "fresh preparation dispatch finished claiming custody")
                (let [fresh-attempt
                      (first
                       (filter #(= (:id prepare) (:gate-id %))
                               (shell/read-shell-attempts {:runtime rt})))]
                  (is (and (some? (:attempt-id fresh-attempt))
                           (not= (:attempt-id attempt)
                                 (:attempt-id fresh-attempt)))
                      "the shell executor can claim a fresh preparation attempt")
                  (reset! fresh-cancellation? true)
                  (let [stopped (shell/quiesce-run!
                                 "inflight-skipped-b"
                                 "Test teardown after verified fresh dispatch")]
                    (shell/retire-quiesced-attempts! rt stopped)
                    (is (empty? @records)
                        "test teardown acknowledges fresh custody")
                    (is (nil? (attr-get (weaver/show rt (:id prepare))
                                        :shell/attempt-id))
                        "test teardown retires the fresh attempt metadata"))
                  (.countDown continue-fresh-observer)
                  (is (.await fresh-observer-finished 5 TimeUnit/SECONDS)
                      "the fresh attempt observer completes before runtime teardown")
                  (is (= :stale @fresh-result)
                      "the retired fresh attempt has no late terminal work"))
                (.shutdown worker-pool)
                (is (.awaitTermination worker-pool 5 TimeUnit/SECONDS)
                    "all shell workers stop before the fixture runtime closes"))))
          (finally
            (.countDown continue-observer)
            (.countDown continue-fresh-observer)
            (.shutdownNow worker-pool)
            (close-guard! rt)))))))

(deftest skipped-turn-repair-admits-an-unreserved-run-at-the-tail
  (with-runtime
    (fn [rt _]
      (start-repair-run! "head")
      (start-repair-run! "unreserved")
      (queue/join! rt "head")
      (let [root (workflow/current-root "unreserved")
            gate (ready-gate "unreserved" "merge-turn")
            request (turn-repair-request root gate)]
        (workflow/complete! "unreserved" {:step (:id gate) :executor "merge-turn"})
        (install-guard! rt)
        (try
          (let [result (queue/repair! rt "unreserved" request)
                entry (weaver/show rt (:entry-id result))]
            (is (= 1 (attr-get entry :queue/sequence)))
            (is (= ["head" "unreserved"]
                   (mapv :run-id (:entries (queue/status rt))))))
          (finally
            (close-guard! rt)))))))

(deftest skipped-turn-repair-refuses-possible-irreversible-work-and-keeps-it-fenced
  (with-runtime
    (fn [rt _]
      (start-repair-run! "attempted-turn")
      (let [root (workflow/current-root "attempted-turn")
            turn (ready-gate "attempted-turn" "merge-turn")
            _ (queue/join! rt "attempted-turn")]
        (workflow/complete! "attempted-turn" {:step (:id turn) :executor "merge-turn"})
        ;; Select the unique irreversible shell gate rather than the first shell gate.
        (let [irreversible (first (filter #(true? (attr-get % :land/irreversible))
                                          (:strands (graph/subgraph
                                                     rt [(:id root)]
                                                     {:type "parent-of"}))))]
          (weaver/update! rt (:id irreversible) {:attributes {:shell/exit-code 1}})
          (install-guard! rt)
          (try
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Irreversible merge work may have started"
                                  (queue/repair! rt "attempted-turn"
                                                 (turn-repair-request root turn))))
            (is (= "closed" (:state (weaver/show rt (:id turn)))))
            (is (some? (attr-get (weaver/show rt (:id irreversible)) :gate/error))
                "quiescence retains a fence after refusal")
            (finally
              (close-guard! rt))))))))

(deftest skipped-release-repair-settles-active-root-and-is-successor-safe
  (with-runtime
    (fn [rt _]
      (start-repair-run! "repaired-release")
      (start-repair-run! "successor")
      (let [{:keys [root entry lock release]}
            (seed-skipped-release! rt "repaired-release")
            _ (queue/join! rt "successor")
            request (release-repair-request root release entry lock)]
        (install-guard! rt)
        (try
          (is (= (:id entry)
                 (:entry-id (queue/repair! rt "repaired-release" request))))
          (is (= "merged" (attr-get (weaver/show rt (:id entry)) :queue/outcome)))
          (is (= "closed" (:state (weaver/show rt (:id lock)))))
          (is (= "Cleanup" (:title (first (workflow/ready "repaired-release")))))
          (queue/grant! rt "successor")
          (let [successor-lock (get-in (queue/status rt) [:lock :id])]
            (is (= (:id entry)
                   (:entry-id (queue/repair! rt "repaired-release" request))))
            (is (= successor-lock (get-in (queue/status rt) [:lock :id]))
                "an idempotent repeat cannot touch the successor lock"))
          (finally
            (close-guard! rt)))))))

(deftest skipped-release-repair-supports-a-retained-closed-root
  (with-runtime
    (fn [rt _]
      (start-repair-run! "closed-release")
      (let [{:keys [root entry lock release]}
            (seed-skipped-release! rt "closed-release")
            request (release-repair-request root release entry lock)]
        (complete-shell! "closed-release" "cleanup complete")
        (is (nil? (workflow/current-root "closed-release")))
        (install-guard! rt)
        (try
          (queue/repair! rt "closed-release" request)
          (is (= "closed" (:state (weaver/show rt (:id root)))))
          (is (= "merged" (attr-get (weaver/show rt (:id entry)) :queue/outcome)))
          (finally
            (close-guard! rt)))))))

(deftest skipped-release-repair-refuses-mismatches-without-queue-writes
  (with-runtime
    (fn [rt _]
      (start-repair-run! "mismatch-release")
      (let [{:keys [root entry lock release]}
            (seed-skipped-release! rt "mismatch-release")
            request (release-repair-request root release entry lock)
            before {:entry (weaver/show rt (:id entry))
                    :lock (weaver/show rt (:id lock))}]
        (doseq [[label bad-request]
                [["root" (assoc-in request [:evidence :root-id] "missing-root")]
                 ["lock" (assoc-in request [:evidence :lock-id] "wrong-lock")]
                 ["evidence" (assoc-in request [:evidence :pr-head]
                                       "cccccccccccccccccccccccccccccccccccccccc")]]]
          (testing label
            (is (thrown? clojure.lang.ExceptionInfo
                         (queue/repair! rt "mismatch-release" bad-request)))
            (is (= before {:entry (weaver/show rt (:id entry))
                           :lock (weaver/show rt (:id lock))}))))))))

(deftest skipped-release-repair-refuses-deleted-root-and-ambiguous-ownership
  (with-runtime
    (fn [rt _]
      (start-repair-run! "deleted-release")
      (let [{:keys [root entry lock release]}
            (seed-skipped-release! rt "deleted-release")
            request (release-repair-request root release entry lock)]
        (workflow/burn! (:id root))
        (let [before {:entry (weaver/show rt (:id entry))
                      :lock (weaver/show rt (:id lock))}]
          (is (thrown? clojure.lang.ExceptionInfo
                       (queue/repair! rt "deleted-release" request)))
          (is (= before {:entry (weaver/show rt (:id entry))
                         :lock (weaver/show rt (:id lock))}))))))
  (with-runtime
    (fn [rt _]
      (start-repair-run! "ambiguous-release")
      (let [{:keys [root entry lock release]}
            (seed-skipped-release! rt "ambiguous-release")
            request (release-repair-request root release entry lock)
            duplicate (weaver/add!
                       rt
                       {:title "Ambiguous duplicate reservation"
                        :attributes {:kind "merge-queue-entry"
                                     :land/run-id "ambiguous-release"
                                     :queue/root (:id root)
                                     :queue/gate (attr-get entry :queue/gate)
                                     :queue/sequence 1
                                     :queue/queued-at "pre-fix corruption"}})
            before {:entry (weaver/show rt (:id entry))
                    :duplicate (weaver/show rt (:id duplicate))
                    :lock (weaver/show rt (:id lock))}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"multiple queue reservations"
                              (queue/repair! rt "ambiguous-release" request)))
        (is (= before {:entry (weaver/show rt (:id entry))
                       :duplicate (weaver/show rt (:id duplicate))
                       :lock (weaver/show rt (:id lock))}))))))

(deftest skipped-release-settlement-failure-has-no-partial-queue-write
  (with-runtime
    (fn [rt _]
      (start-repair-run! "failed-settlement")
      (let [{:keys [root entry lock release]}
            (seed-skipped-release! rt "failed-settlement")
            request (release-repair-request root release entry lock)]
        (reject-close! rt entry)
        (let [before {:entry (weaver/show rt (:id entry))
                      :lock (weaver/show rt (:id lock))}]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Lifecycle hook failed"
                                (queue/repair! rt "failed-settlement" request)))
          (is (= before {:entry (weaver/show rt (:id entry))
                         :lock (weaver/show rt (:id lock))})))))))

(deftest merge-queue-repair-cli-uses-only-canonical-actor-flag
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :test/workflow 'millhouse.spools.workflow)
      (test-support/activate-spool! rt :test/land 'millhouse.spools.land.spool
                                    :after [:test/workflow])
      (let [flags (get-in (weaver/resolve-op rt 'merge-queue)
                          [:arg-spec :subcommands "repair" :flags])]
        (is (contains? flags :by-identity))
        (is (not (contains? flags :by)))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Unknown flag --by"
             (weaver/op! rt :merge-queue
                         ["repair" "run-1" "--kind" "skipped-turn"
                          "--by" "operator" "--reason" "evidence"
                          "--evidence" "{}"])))))))

(deftest land-activation-protects-and-scans-persisted-queue-gates
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :test/workflow 'millhouse.spools.workflow)
      (start-repair-run! "persisted-gate")
      (let [turn (ready-gate "persisted-gate" "merge-turn")]
        (test-support/activate-spool! rt :test/land 'millhouse.spools.land.spool
                                      :after [:test/workflow])
        (test-alpha/await-quiescent! rt)
        (is (= "Prepare merge" (:title (first (workflow/ready "persisted-gate"))))
            "the scanner granted a gate poured before Land activation")
        (is (= "grant"
               (attr-get (weaver/show rt (:id turn)) :land/queue-completion)))
        (is (some #(= :land/queue-gate-completion (:key %)) (hooks/hooks rt))
            "the guard resource was installed before the dependent scanner")))))

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
