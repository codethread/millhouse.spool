(ns millhouse.spools.land.workflow-test
  "Exercise ordinary landing transitions and card actions in disposable runtimes."
  (:require [clojure.test :refer [deftest is]]
            [millhouse.spools.land.card-actions :as card-actions]
            [millhouse.spools.kanban :as kanban]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.test-support :as test-support :refer [with-runtime]]))

(def ^:dynamic *fail-card-write*
  "When true, reject lifecycle batches that update a kanban card."
  false)

(defn reject-card-write
  "Reject a card update while the deterministic failure fixture is enabled."
  [ctx]
  (when (and *fail-card-write*
             (= "true" (attr-get (:strand/after ctx) :kanban/card)))
    (throw (ex-info "Injected card write failure" {}))))

(defn- register-land-routes!
  []
  (workflow/register-workflow! :land-abort 'millhouse.spools.land/land-abort)
  (workflow/register-workflow! :land-merge 'millhouse.spools.land/land-merge))

(defn- card-fixture
  [rt]
  (let [root (test-support/temp-dir "millstrand-land-workflow")
        _ (test-support/run-git! root "init" "-b" "main")
        card (:id (:card (kanban/add! rt "Landing fixture" {})))
        _ (kanban/claim! rt card {"--owner" "test-agent"
                                  "--branch" "feature/land-test"
                                  "--worktree" (.getPath root)})]
    {:root root
     :card card
     :params {:feature "land fixture"
              :branch "feature/land-test"
              :worktree (.getPath root)
              :card card
              :reviewer "reviewer"}}))

(defn- card-lane
  [rt id]
  (attr-get (weaver/show rt id) :kanban/lane))

(defn- start-land!
  [run-id params]
  (workflow/start! run-id
                   @(requiring-resolve 'millhouse.spools.land/land)
                   params))

(defn- complete-ready!
  ([run-id]
   (complete-ready! run-id {}))
  ([run-id attributes]
   (workflow/complete! run-id {:by "test-agent" :attributes attributes})))

(def ^:private reviewed-base
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private reviewed-head
  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(def ^:private review-evidence
  {:reviewer "reviewer"
   :base reviewed-base
   :head reviewed-head
   :p1-p2 "none"
   :summary "Reviewed immutable range; no P1/P2 findings."})

(defn- reach-review-resolution!
  [rt run-id card]
  (card-actions/review! rt {:card card})
  (complete-ready! run-id)
  (is (= "in_review" (card-lane rt card)))
  (is (= "Validate the pushed HEAD before review"
         (:title (first (workflow/ready run-id)))))
  (complete-ready! run-id)
  (let [agent (first (workflow/ready run-id))
        strand (weaver/show rt (:id agent))]
    (is (= "agent" (:gate agent)))
    (is (= "reviewer" (attr-get strand :harness/alias)))
    (is (re-find #"origin/main" (attr-get strand :harness/prompt)))
    (complete-ready! run-id {"harness/result" "No P1/P2 findings."}))
  (workflow/ready-checkpoint run-id))

(defn- reach-signoff!
  [rt run-id card]
  (complete-ready! run-id)
  (let [resolution (reach-review-resolution! rt run-id card)]
    (is (= "resolve-review" (:checkpoint resolution)))
    (workflow/choose! run-id :accepted review-evidence)
    (is (= review-evidence
           (attr-get (weaver/show rt (:id resolution)) :workflow/outcome-input))))
  (workflow/ready-checkpoint run-id))

(deftest standalone-review-runs-one-agent-before-coordinator-resolution
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow
                                    'millhouse.spools.workflow)
      (let [{:keys [root card params]} (card-fixture rt)
            run-id "standalone-review"]
        (try
          (workflow/start! run-id
                           @(requiring-resolve 'millhouse.spools.land/review)
                           params)
          (let [resolution (reach-review-resolution! rt run-id card)]
            (is (= "resolve-review" (:checkpoint resolution)))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Value does not satisfy"
                                  (workflow/choose! run-id :accepted {})))
            (is (= "resolve-review" (:checkpoint (workflow/ready-checkpoint run-id))))
            (workflow/choose! run-id :accepted review-evidence)
            (is (workflow/done? run-id)))
          (finally
            (test-support/delete-tree! root)))))))

(deftest landing-requires-one-seat-review-for-a-branch-or-existing-pr
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow
                                    'millhouse.spools.workflow)
      (register-land-routes!)
      (let [{:keys [root card params]} (card-fixture rt)]
        (try
          (doseq [[run-id extra] [["land-branch" {}] ["land-existing-pr" {:pr-number 42}]]]
            (start-land! run-id (merge params extra))
            (is (= "Resolve and verify the pull request"
                   (:title (first (workflow/ready run-id)))))
            (is (= "signoff" (:checkpoint (reach-signoff! rt run-id card))))
            (is (= run-id (attr-get (workflow/current-root run-id) :workflow/run-id))))
          (finally
            (test-support/delete-tree! root)))))))

(deftest landing-cannot-omit-coordinator-review-resolution
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow
                                    'millhouse.spools.workflow)
      (register-land-routes!)
      (let [{:keys [root card params]} (card-fixture rt)
            run-id "missing-review-evidence"]
        (try
          (start-land! run-id params)
          (complete-ready! run-id)
          (let [resolution (reach-review-resolution! rt run-id card)]
            (is (= "resolve-review" (:checkpoint resolution)))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Value does not satisfy"
                                  (workflow/choose! run-id :accepted {})))
            (is (= "resolve-review" (:checkpoint (workflow/ready-checkpoint run-id)))))
          (finally
            (test-support/delete-tree! root)))))))

(deftest approved-signoff-routes-to-automatic-merge-turn
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow
                                    'millhouse.spools.workflow)
      (register-land-routes!)
      (let [{:keys [root card params]} (card-fixture rt)
            run-id "land-approved"
            _ (start-land! run-id params)]
        (try
          (is (= "signoff" (:checkpoint (reach-signoff! rt run-id card))))
          (let [ready (:ready (workflow/choose!
                               run-id :approved
                               {:pr-number 42 :subject "Land fixture"
                                :body "Land the reviewed fixture."}))]
            (is (= "Join the queue and await the merge turn"
                   (:title (first ready))))
            (is (= "merge-turn" (:gate (first ready))))
            (is (= run-id (:run-id (first ready))))
            (is (nil? (workflow/ready-checkpoint run-id)))
            (complete-ready! run-id)
            (complete-ready! run-id)
            (complete-ready! run-id)
            (complete-ready! run-id)
            (complete-ready! run-id)
            (let [cleanup (first (workflow/ready run-id))]
              (is (= "Remove the landed branch and worktree" (:title cleanup)))
              (is (= (.getCanonicalPath root)
                     (attr-get (weaver/show rt (:id cleanup)) :shell/cwd)))))
          (finally
            (test-support/delete-tree! root)))))))

(deftest abort-keeps-an-explicit-retryable-card-gate-after-write-failure
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/spools-workflow
                                    'millhouse.spools.workflow)
      (register-land-routes!)
      (let [{:keys [root card params]} (card-fixture rt)
            run-id "land-aborted"
            _ (start-land! run-id params)]
        (try
          (reach-signoff! rt run-id card)
          (let [ready (:ready (workflow/choose! run-id :abort
                                                {:reason "Needs a larger change."}))
                abort-root (workflow/current-root run-id)]
            (is (= "Return the card to claimed" (:title (first ready))))
            (is (= "code" (:gate (first ready))))
            (hooks/register-hook! rt :test/card-write
                                  #{:strand/update-before-commit}
                                  'millhouse.spools.land.workflow-test/reject-card-write)
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Lifecycle hook failed"
                                  (binding [*fail-card-write* true]
                                    (card-actions/rework! rt {:card card}))))
            (is (= (:id abort-root) (:id (workflow/current-root run-id))))
            (is (= "in_review" (card-lane rt card)))
            (is (= "Return the card to claimed"
                   (:title (first (workflow/ready run-id)))))
            (card-actions/rework! rt {:card card})
            (is (= "claimed" (card-lane rt card)))
            (is (= "in_review" (do (weaver/update! rt card {:attributes {:kanban/lane "in_review"}})
                                   (card-lane rt card)))))
          (finally
            (test-support/delete-tree! root)))))))

(deftest selector-activation-resolves-the-card-callback-in-its-runtime
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :test/workflow
                                    'millhouse.spools.workflow)
      (test-support/activate-spool! rt :test/code
                                    'millhouse.test-modules.code-executor
                                    :after [:test/workflow])
      (test-support/activate-spool! rt :test/land
                                    'millhouse.spools.land.spool
                                    :after [:test/workflow :test/code])
      (let [{:keys [root card params]} (card-fixture rt)
            run-id "activated-land"]
        (try
          (workflow/start! run-id :land params)
          (complete-ready! run-id)
          (let [quality
                (test-support/poll-until
                 #(let [step (first (workflow/ready run-id))]
                    (when (= "Validate the pushed HEAD before review" (:title step))
                      step))
                 {:timeout-ms (test-support/await-budget-ms)
                  :on-timeout #(throw (ex-info "Land card callback did not resolve" {}))})]
            (is (= "shell" (:gate quality))))
          (is (= "in_review" (card-lane rt card)))
          (is (every? (set (keys (workflow/workflows)))
                      [:review :land :land-merge :land-abort]))
          (is (= {:entries [] :lock nil :operation "merge-queue status"}
                 (weaver/op! rt :merge-queue ["status"])))
          (finally
            (test-support/delete-tree! root)))))))

(deftest card-actions-are-idempotent-after-a-successful-write
  (with-runtime
    (fn [rt _]
      (let [{:keys [root card]} (card-fixture rt)]
        (try
          (is (nil? (card-actions/review! rt {:card card})))
          (is (= "in_review" (card-lane rt card)))
          (is (nil? (card-actions/review! rt {:card card})))
          (is (nil? (card-actions/rework! rt {:card card})))
          (is (= "claimed" (card-lane rt card)))
          (is (nil? (card-actions/rework! rt {:card card})))
          (is (nil? (card-actions/finish! rt {:card card})))
          (is (= "closed" (:state (weaver/show rt card))))
          (is (= "done" (attr-get (weaver/show rt card) :kanban/outcome)))
          (is (nil? (card-actions/finish! rt {:card card})))
          (finally
            (test-support/delete-tree! root)))))))

(defn- definition-step
  [definition id]
  (first (filter #(= id (:id %)) (:steps definition))))

(deftest merge-graph-releases-before-housekeeping-and-has-one-quality-path
  (let [definition @(requiring-resolve 'millhouse.spools.land/land-merge)
        ids (mapv :id (:steps definition))]
    (is (= [:take-turn :prepare-merge :merge-pr :pull-main :release-turn
            :remove-branch-worktree :tidy-resources :finish-card]
           ids))
    (is (nil? (definition-step definition :quality)))
    (is (= [:pull-main] (:depends-on (definition-step definition :release-turn))))
    (is (= [:release-turn]
           (:depends-on (definition-step definition :remove-branch-worktree))))
    (is (= [:tidy-resources]
           (:depends-on (definition-step definition :finish-card))))
    (let [prepare (definition-step definition :prepare-merge)
          prepare-argv ((get-in prepare [:attributes "shell/argv"])
                        {:branch "feature/land-test"})
          merge-step (definition-step definition :merge-pr)
          merge-argv ((get-in merge-step [:attributes "shell/argv"])
                      {:pr-number 42 :subject "Subject" :body "Body"
                       :branch "feature/land-test"})]
      (is (re-find #"land quality gate" (last prepare-argv))
          "prepare-merge carries the single frozen quality validation path")
      (is (= "feature/land-test" (last merge-argv))
          "merge-pr freezes the expected PR head branch"))))
