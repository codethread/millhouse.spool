(ns millhouse.land.merge-queue-test
  "Exercise queue behavior through public operations in disposable runtimes."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.land.merge-queue :as queue]
            [millhouse.land.support :as support]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.execution :as execution]
            [millstrand.api.cli.alpha :as cli]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.test-support :as test-support :refer [with-runtime]]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(s/def ::branch (s/and string? (complement str/blank?)))
(s/def ::reason (s/and string? (complement str/blank?)))
(s/def ::abort-note (s/and string? (complement str/blank?)))
(s/def ::abort-params (s/keys :req-un [::branch ::reason]))
(s/def ::default-abort-params
  (s/keys :req-un [::branch ::reason ::abort-note]))

(workflow/defworkflow abort-fixture
  "Compile repository-owned abort bookkeeping for queue tests."
  {:entrypoints #{:continue} :param-spec ::abort-params}
  (workflow/workflow
   "Abort fixture"
   {:attributes {"workflow/family" "land"
                 "land/stage" "abort"
                 "land/abort-reason" (fn [{:keys [reason]}] reason)}}
   (workflow/step :record "Pause unfinished work" :self)))

(workflow/defworkflow abort-default-fixture
  "Compile repository abort defaults for queue tests."
  {:entrypoints #{:continue}
   :param-spec ::default-abort-params
   :defaults {:abort-note "defaulted"}}
  (workflow/workflow
   "Abort default fixture"
   {:attributes {"workflow/family" "land"
                 "land/stage" "abort"
                 "land/abort-note" (fn [{:keys [abort-note]}] abort-note)}}
   (workflow/step :record "Pause defaulted work" :self)))

(workflow/defworkflow abort-start-only-fixture
  "Compile an invalid start-only abort target for queue tests."
  {:entrypoints #{:start} :param-spec ::abort-params}
  (workflow/workflow
   "Start-only abort fixture"
   (workflow/step :record "Pause start-only work" :self)))

(def ^:private fixture-abort-definition
  "Fully qualified repository abort workflow used by queue fixtures."
  "millhouse.land.merge-queue-test/abort-fixture")

(def ^:private default-abort-definition
  "Fully qualified repository abort workflow with a required default."
  "millhouse.land.merge-queue-test/abort-default-fixture")

(def ^:private start-only-abort-definition
  "Fully qualified abort workflow missing the continuation entrypoint."
  "millhouse.land.merge-queue-test/abort-start-only-fixture")

(defn- start-run!
  ([id]
   (start-run! id fixture-abort-definition {}))
  ([id abort-definition context]
   (workflow/start!
    id
    (workflow/workflow
     "Landing fixture"
     {:attributes {"workflow/family" "land"
                   "land/abort-definition" abort-definition}}
     (workflow/gate :turn "Await turn" :merge-turn)
     (workflow/step :work "Protected work" :self :depends-on [:turn])
     (workflow/gate :release "Release turn" :merge-release :depends-on [:work])
     (workflow/step :tidy "Housekeeping" :self :depends-on [:release]))
    (merge {:branch id} context))))

(defn- start-repair-run!
  [id]
  (workflow/start!
   id
   (workflow/workflow
    "Landing repair fixture"
    {:attributes {"workflow/family" "land"
                  "land/version" 4
                  "land/stage" "merge"
                  "land/abort-definition" fixture-abort-definition}}
    (workflow/gate :turn "Await turn" :merge-turn)
    (workflow/gate :prepare "Prepare merge" :shell
                   :depends-on [:turn]
                   :attributes {"shell/argv" ["sh" "-c" "prepare" "land-prepare"]})
    (workflow/gate :merge "Merge PR" :shell
                   :depends-on [:prepare]
                   :attributes {"shell/argv" ["sh" "-c" "merge" "land-merge"
                                              "42" "Subject" "Body" id "squash"]
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
   (workflow/workflow
    "Repository landing fixture"
    {:attributes {"workflow/family" "land"
                  "land/version" 4
                  "land/stage" "merge"
                  "land/abort-definition" fixture-abort-definition}}
    (workflow/gate :turn "Await turn" :merge-turn)
    (workflow/gate :prepare "Prepare merge" :shell
                   :depends-on [:turn]
                   :attributes {"shell/argv" ["sh" "-c" "prepare" "land-prepare"]})
    (workflow/gate :merge "Merge PR" :shell
                   :depends-on [:prepare]
                   :attributes {"shell/argv" ["sh" "-c" "merge" "land-merge"
                                              "42" "Subject" "Body" id "squash"]
                                "land/irreversible" true})
    (workflow/gate :pull "Pull main" :shell
                   :depends-on [:merge]
                   :attributes {"shell/argv" ["sh" "-c" "pull" "land-pull"]})
    (workflow/gate :release "Release turn" :merge-release
                   :depends-on [:pull])
    (workflow/gate :cleanup "Cleanup" :shell
                   :depends-on [:release]
                   :attributes {"shell/argv" ["sh" "-c" "cleanup" "land-cleanup"]})
    (support/card-gate :finish-card "Finish card" [:cleanup]
                       "millhouse.land.card-actions/finish-card!"))
   {:branch id :pr-number 42}))

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
                        'millhouse.land.merge-queue-test/reject-marked-close)
  (weaver/update! rt (:id gate) {:attributes {:test/reject-close true}}))

(defn- allow-close! [rt gate]
  (weaver/update! rt (:id gate) {:attributes {:test/reject-close nil}}))

(defn- activate-worker-cli!
  [rt]
  (test-support/activate-spool! rt :test/workflow 'millhouse.workflow)
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
        (is (= "withdrawn" (:outcome (queue/withdraw! rt (:id entry) "Scope changed" "fixture-owner"))))
        (is (= "first" (get-in (queue/status rt) [:lock :run-id])))
        (is (not= previous (:id (workflow/current-root "second"))))
        (is (= "Scope changed" (attr-get (workflow/current-root "second") :land/abort-reason)))
        (is (= "Pause unfinished work" (:title (first (workflow/ready "second")))))
        (is (= "withdrawn" (:outcome (queue/withdraw! rt (:id entry) "Repeated request" "fixture-owner"))))))))

(deftest repository-abort-applies-defaults-and-rejects-invalid-context
  (with-runtime
    (fn [rt _]
      (start-run! "defaulted-abort" default-abort-definition {})
      (let [entry (queue/join! rt "defaulted-abort")]
        (queue/withdraw! rt (:id entry) "Use repository defaults" "fixture-owner")
        (is (= "defaulted"
               (attr-get (workflow/current-root "defaulted-abort")
                         :land/abort-note))))
      (start-run! "invalid-abort" default-abort-definition {:abort-note ""})
      (let [entry (queue/join! rt "invalid-abort")
            root (workflow/current-root "invalid-abort")]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"rejected landing context"
             (queue/withdraw! rt (:id entry) "Reject invalid params" "fixture-owner")))
        (is (= "active" (:state (weaver/show rt (:id entry)))))
        (is (= (:id root) (:id (workflow/current-root "invalid-abort"))))))))

(deftest repository-abort-requires-the-continuation-entrypoint
  (with-runtime
    (fn [rt _]
      (start-run! "start-only-abort" start-only-abort-definition {})
      (let [entry (queue/join! rt "start-only-abort")
            root (workflow/current-root "start-only-abort")]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"does not declare continue entry"
             (queue/withdraw! rt (:id entry) "Reject start-only abort" "fixture-owner")))
        (is (= "active" (:state (weaver/show rt (:id entry)))))
        (is (= (:id root) (:id (workflow/current-root "start-only-abort"))))))))

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
                              (queue/withdraw! rt (:id entry) "Repair elsewhere" "fixture-owner")))
        (is (= (:id root) (:id (workflow/current-root "first"))))
        (is (:holds-lock (queue/status rt (:id entry))))
        (is (nil? (queue/grant! rt "second")))
        (allow-close! rt entry)
        (queue/withdraw! rt (:id entry) "Repair elsewhere" "fixture-owner")
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
                              (queue/withdraw! rt (:id entry) "Stop" "fixture-owner")))
        (is (:holds-lock (queue/status rt (:id entry))))
        (is (= (:id root) (:id (workflow/current-root "first"))))
        (let [frozen (:freeze (execution/run-view rt "first"))]
          (execution/resume-run! rt "first" (execution/retire! rt frozen)))
        (queue/release! rt "first")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"completed merge"
                              (queue/withdraw! rt (:id entry) "Stop" "fixture-owner")))))))

(deftest completion-guard-allows-atomic-withdrawal
  (with-runtime
    (fn [rt _]
      (start-run! "withdraw-guarded")
      (let [entry (queue/join! rt "withdraw-guarded")]
        (install-guard! rt)
        (try
          (queue/grant! rt "withdraw-guarded")
          (is (= "withdrawn"
                 (:outcome (queue/withdraw! rt (:id entry) "Guarded withdrawal" "fixture-owner"))))
          (is (= "Pause unfinished work"
                 (:title (first (workflow/ready "withdraw-guarded")))))
          (finally
            (close-guard! rt)))))))

(deftest merge-queue-repair-cli-uses-only-canonical-actor-flag
  ;; workflow-test covers published dispatch; argument shape needs no world.
  (let [declaration (test-alpha/collect-module-forms
                     :test/land-cli 'millhouse.land.merge-queue-test
                     #(millstrand/use-op! queue/merge-queue))
        args (get-in declaration [:contribution :ops :entries "merge-queue" :arg-spec])
        flags (get-in args [:subcommands "repair" :flags])]
    (is (contains? flags :by-identity))
    (is (not (contains? flags :by)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unknown flag --by"
         (cli/parse args ["repair" "run-1" "--kind" "preparation"
                          "--by" "operator" "--reason" "evidence"
                          "--evidence" "{}"])))))

(deftest land-activation-protects-and-scans-persisted-queue-gates
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :test/workflow 'millhouse.workflow)
      (start-repair-run! "persisted-gate")
      (let [turn (ready-gate "persisted-gate" "merge-turn")]
        (test-support/activate-spool! rt :test/land 'millhouse.land.spool
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

(deftest managed-code-withdrawal-retires-and-abandons-atomically
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :workflow 'millhouse.workflow)
      (test-support/activate-spool! rt :managed-code 'millhouse.test-modules.code-executor :after [:workflow])
      (start-land-merge-run! "managed-withdrawal")
      (let [entry (queue/join! rt "managed-withdrawal")
            root-id (:id (workflow/current-root "managed-withdrawal"))]
        (install-guard! rt)
        (try
          (queue/grant! rt "managed-withdrawal")
          (is (= "withdrawn" (:outcome (queue/withdraw! rt (:id entry) "Retire" "fixture-owner"))))
          (is (= "closed" (:state (weaver/show rt root-id))))
          (is (not= root-id (:id (workflow/current-root "managed-withdrawal"))))
          (is (nil? (:lock (queue/status rt))))
          (finally (close-guard! rt)))))))
