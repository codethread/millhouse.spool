(ns millhouse.workflow-composition-test
  "Test runtime-selected returning composition and its safety boundaries."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.test-support :as test-support :refer [with-embedded-runtime]]
            [millhouse.workflow :as workflow]))

(workflow/defworkflow registry-second-stage
  "Provide the second registry stage."
  {:entrypoints #{:continue}}
  (workflow/workflow "Registry second" (workflow/step :do-second "Do second" :self)))

(s/def ::reviewer string?)

(s/def ::feature string?)

(s/def ::devflow-params (s/keys :req-un [::feature ::reviewer]))

(workflow/defworkflow defer-devflow
  "Plan and build a feature."
  {:entrypoints #{:start :call}
   :param-spec ::devflow-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Plan and build " feature))
   (workflow/step :inspect
                  (fn [{:keys [feature reviewer]}]
                    (str "Inspect " feature " for " reviewer))
                  :self)))

(workflow/defworkflow defer-spike
  "Reduce uncertainty before committing."
  {:entrypoints #{:start :call}}
  (workflow/workflow
   "Spike"
   (workflow/step :probe "Probe the unknown" :self)))

(workflow/defworkflow defer-continue-only
  "A route-only routine that no defer may select."
  {:entrypoints #{:continue}}
  (workflow/workflow
   "Continue only"
   (workflow/step :inner "Inner" :self)))

(s/def ::defer-scope string?)

(s/def ::defer-target-params (s/keys :req-un [::defer-scope]))

(workflow/defworkflow defer-two-step-target
  "A call-capable routine with an entry and an exit."
  {:entrypoints #{:call}
   :param-spec ::defer-target-params
   :defaults {:defer-scope "default"}}
  (workflow/workflow
   (fn [{:keys [defer-scope]}] (str "Deliver " defer-scope))
   (workflow/step :plan (fn [{:keys [defer-scope]}] (str "Plan " defer-scope)) :self)
   (workflow/step :ship "Ship it" :self :depends-on [:plan])))

(workflow/defworkflow defer-fanout-target
  "A call-capable routine with a fan-out and colliding step ids."
  {:entrypoints #{:call}}
  (workflow/workflow
   "Fanout"
   (workflow/step :a "Target a" :self)
   (workflow/step :left "Target left" :self :depends-on [:a])
   (workflow/step :right "Target right" :self :depends-on [:a])
   (workflow/step :c "Target c" :self :depends-on [:left :right])))

(workflow/defworkflow defer-empty-target
  "A call-capable routine that materializes no steps."
  {:entrypoints #{:call}}
  (workflow/workflow "Empty"))

(def ^:private card-template
  "The unregistered template a spool publishes: it names its selection point
  without naming anyone else's workflow, and carries on afterwards."
  (workflow/workflow
   "Track a card"
   {:entrypoints #{:start :call}}
   (workflow/step :prepare "Prepare the card" :self)
   (workflow/defer :perform-work "Choose how this work will be performed"
                   :depends-on [:prepare])
   (workflow/step :record "Record the result" :self :depends-on [:perform-work])))

(defn- bound-card
  "Return the template bound to `targets`, as user code with both spools would."
  [targets]
  (workflow/bind-defers card-template {:perform-work targets}))

(def ^:private tracked-card (bound-card #{:wt-devflow :wt-spike}))

(def ^:private defer-caller
  (workflow/workflow
   "Caller"
   {:entrypoints #{:start}}
   (workflow/call :sub :wt-card {})))

(defn- defer-sandwich
  "step a -> defer -> step c, the shape the whole feature exists for."
  [targets]
  (workflow/bind-defers
   (workflow/workflow
    "Sandwich"
    (workflow/step :a "Step a" :self)
    (workflow/defer :perform-work "Choose work" :depends-on [:a])
    (workflow/step :c "Step c" :self :depends-on [:perform-work]))
   {:perform-work targets}))

(defn- final-defer-workflow
  "A defer as the last declared step, beside parallel sibling work."
  [targets]
  (workflow/bind-defers
   (workflow/workflow
    "Final defer"
    (workflow/step :sibling "Sibling work" :self)
    (workflow/defer :perform-work "Choose work"))
   {:perform-work targets}))

(defn- register-defer-targets! []
  (workflow/register-workflow! :wt-devflow 'millhouse.workflow-composition-test/defer-devflow)
  (workflow/register-workflow! :wt-spike 'millhouse.workflow-composition-test/defer-spike))

(defn- start-at-defer!
  "Start `run-id` on the bound card and complete its first step, leaving the
  defer as the whole ready frontier."
  [run-id]
  (workflow/start! run-id tracked-card {})
  (workflow/complete! run-id)
  (workflow/ready-step run-id))

(defn- ready-titles [run-id]
  (mapv :title (workflow/ready run-id)))

(defn- complete-ready! [run-id title]
  (let [step (first (filter #(= title (:title %)) (workflow/ready run-id)))]
    (workflow/complete! run-id {:step (:id step)})))

(deftest a-call-procedure-may-declare-a-defer
  ;; PROP-Dfr-001.S1 removes the old restriction: a procedure join returns, and
  ;; so does a defer, so the two compose instead of contradicting.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (register-defer-targets!)
      (workflow/register-workflow! :wt-card 'millhouse.workflow-composition-test/tracked-card)
      (is (= :wt-caller (workflow/register-workflow!
                         :wt-caller 'millhouse.workflow-composition-test/defer-caller)))
      (workflow/start! "nested-call" #'defer-caller {})
      (workflow/complete! "nested-call")
      (let [defer (workflow/ready-step "nested-call")]
        (is (= "defer" (:role defer)))
        (workflow/defer! "nested-call" :wt-spike))
      (is (= ["Probe the unknown"] (ready-titles "nested-call")))
      (workflow/complete! "nested-call")
      (is (= ["Record the result"] (ready-titles "nested-call"))
          "the enclosing procedure resumes past the defer it contained")
      (is (true? (:done (workflow/complete! "nested-call")))))))

(deftest defer-returns-to-the-declaring-workflow
  ;; PROP-Dfr-001.G1/S3. This is the feature: a routine chosen at run time, run
  ;; inside the caller's own molecule, with the caller's next step waiting on it.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (let [root-id (do (workflow/start! "sandwich" (defer-sandwich #{:wt-two-step}) {})
                        (:id (workflow/current-root "sandwich")))]
        (is (= ["Step a"] (ready-titles "sandwich")))
        (let [after-a (workflow/complete! "sandwich")
              pending (first (:ready after-a))]
          (is (= "defer" (:role pending)))
          (is (= ["wt-two-step"] (:workflows pending)))
          (let [filled (workflow/defer! "sandwich" :wt-two-step
                                        {:defer-scope "the thing"} {:by-identity "worker-1"})
                join (weaver/show rt (:id pending))]
            (is (= ["Plan the thing"] (mapv :title (:ready filled)))
                "the expansion is ready; step c is not")
            (is (= "procedure" (get-in join [:attributes :workflow/role]))
                "CC3: the defer became an ordinary procedure join in the same batch")
            (is (= "active" (:state join)))
            (testing "CC4: the expansion hangs under the caller's own root, not a new one"
              (is (= root-id (:id (workflow/current-root "sandwich"))))
              (is (some #(= "Plan the thing" (:title %))
                        (:strands (graph/subgraph rt [root-id])))))
            (testing "CC6: the fill record"
              (let [attrs (:attributes join)]
                (is (= "wt-two-step" (:workflow/deferred-workflow attrs)))
                (is (= "millhouse.workflow-composition-test/defer-two-step-target"
                       (:workflow/deferred-definition attrs)))
                (is (re-matches #"[0-9a-f]{16}" (:workflow/deferred-fingerprint attrs)))
                (is (= {:defer-scope "the thing"} (:workflow/deferred-params attrs)))
                (is (= "worker-1" (:identity/by-identity attrs)))
                (is (nil? (:workflow/outcome attrs))
                    "a filled defer is procedure bookkeeping, not an outcome")))
            (workflow/complete! "sandwich")
            (let [after-ship (workflow/complete! "sandwich")
                  closed-join (weaver/show rt (:id pending))]
              (is (= ["Step c"] (mapv :title (:ready after-ship)))
                  "step c becomes ready only once the expansion's exits close")
              (is (= "closed" (:state closed-join))
                  "the join auto-closed through the existing cascade")
              (is (= "worker-1" (get-in closed-join [:attributes :identity/by-identity]))
                  "auto-close preserves the deliberate defer actor")
              (is (= "engine" (get-in closed-join [:attributes :workflow/executor]))
                  "engine provenance does not overwrite actor evidence"))
            (is (true? (:done (workflow/complete! "sandwich"))))
            (let [molecules (workflow/run-history "sandwich")
                  events (mapcat :events molecules)]
              (is (= [root-id] (mapv (comp :id :root) molecules)))
              (is (= #{:step-closed} (set (map :type events))))
              (is (not-any? #(= (:id pending) (:id %)) events)
                  "the filled join is bookkeeping, not a worker history event")
              (is (= #{"Step a" "Plan the thing" "Ship it" "Step c"}
                     (set (map :title events)))))))))))

(deftest a-final-defer-returns-without-abandoning-parallel-siblings
  ;; PROP-Dfr-001.S4/G5: tail position is not an ownership transfer. The root
  ;; stays, its context stays, and the run finishes only when the siblings do.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (workflow/start! "final" (final-defer-workflow #{:wt-two-step}) {:label "caller"})
      (let [root-id (:id (workflow/current-root "final"))
            pending (first (filter #(= "defer" (:role %)) (workflow/ready "final")))
            ;; a sibling step is ready beside the defer, so trusted Clojure names
            ;; the strand it means rather than inferring the sole ready item
            filled (workflow/defer! "final" :wt-two-step {} {:step (:id pending)})]
        (is (= #{"Sibling work" "Plan default"} (set (mapv :title (:ready filled)))))
        (is (false? (:done filled)))
        (complete-ready! "final" "Plan default")
        (let [after-ship (complete-ready! "final" "Ship it")]
          (is (= "closed" (:state (weaver/show rt (:id pending))))
              "the join closes normally after its selected routine exits")
          (is (false? (:done after-ship))
              "the parallel sibling was never abandoned by filling a final defer")
          (is (= ["Sibling work"] (mapv :title (:ready after-ship)))))
        (let [after-sibling (complete-ready! "final" "Sibling work")
              molecules (workflow/run-history "final")]
          (is (true? (:done after-sibling)))
          (is (= [root-id] (mapv (comp :id :root) molecules))
              "one molecule throughout: no root transfer"))
        (is (= {:label "caller"}
               (get-in (weaver/show rt root-id) [:attributes :workflow/context]))
            "the declaring root's context is never replaced by the target's")))))

(deftest defer-isolates-the-target-from-caller-params
  ;; DELTA-Dfr-001.CC3: the publishing spool never saw the filling spool, so its
  ;; context must not reach the target.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (workflow/start! "isolated" (defer-sandwich #{:wt-two-step})
                       {:defer-scope "caller value"})
      (workflow/complete! "isolated")
      (is (= ["Plan default"] (mapv :title (:ready (workflow/defer! "isolated" :wt-two-step))))
          "an omitted param falls to the target's own default, never the caller's key"))))

(deftest defer-resolves-its-target-live-and-fails-with-the-defer-still-ready
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (register-defer-targets!)
      (testing "a name outside the poured allowlist is refused before resolution"
        (let [defer-id (:id (start-at-defer! "live-1"))
              thrown (try (workflow/defer! "live-1" :wt-elsewhere {})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/defer-target-not-allowed (:reason (ex-data thrown))))
          (is (= ["wt-devflow" "wt-spike"] (:allowed (ex-data thrown))))
          (is (= "active" (:state (weaver/show rt defer-id))))))
      (testing "a compatible repoint runs the replacement"
        (workflow/register-workflow! :wt-spike 'millhouse.workflow-composition-test/defer-devflow)
        (workflow/defer! "live-1" :wt-spike {:feature "repointed"})
        (is (= ["Inspect repointed for agent"] (ready-titles "live-1")))
        (workflow/register-workflow! :wt-spike 'millhouse.workflow-composition-test/defer-spike))
      (testing "removal, a lost :call, and rejected params all leave the defer ready"
        (let [defer-id (:id (start-at-defer! "live-2"))
              check (fn [thrown reason]
                      (is (= reason (:reason (ex-data thrown))))
                      (is (= "active" (:state (weaver/show rt defer-id)))
                          "nothing closed, so the worker can retry")
                      (is (= "defer" (get-in (weaver/show rt defer-id)
                                             [:attributes :workflow/role]))
                          "and nothing was rewritten into a join"))]
          (check (try (workflow/defer! "live-2" :wt-devflow {:feature 42})
                      (catch clojure.lang.ExceptionInfo e e))
                 :workflow/params-invalid)
          (workflow/register-workflow! :wt-devflow
                                       'millhouse.workflow-composition-test/defer-continue-only)
          (check (try (workflow/defer! "live-2" :wt-devflow {})
                      (catch clojure.lang.ExceptionInfo e e))
                 :workflow/entrypoint-unsupported)
          (workflow/unregister-workflow! :wt-devflow)
          (check (try (workflow/defer! "live-2" :wt-devflow {})
                      (catch clojure.lang.ExceptionInfo e e))
                 :workflow/definition-unregistered))))))

(deftest defer-request-spec-rejects-malformed-shapes
  ;; Shape is a pure contract; live target/param refusal above retains the
  ;; runtime evidence that rejected fills leave the selection point untouched.
  (doseq [patch [{:run-id ""}
                 {:workflow "wt-spike"}
                 {:params [1 2]}
                 {:by-identity ""}]]
    (is (not (s/valid? :millhouse.workflow/defer-request
                       (merge {:run-id "req-1" :workflow :wt-spike} patch))))))

(deftest a-ready-defer-is-neither-completed-nor-advanced
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (register-defer-targets!)
      (let [defer-id (:id (start-at-defer! "role-1"))]
        (let [complete-error (try (workflow/complete! "role-1")
                                  (catch clojure.lang.ExceptionInfo e e))
              advance-error (try (workflow/advance! "role-1")
                                 (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/step-is-defer (:reason (ex-data complete-error))))
          (is (re-find #"defer!" (ex-message complete-error)))
          (is (= :workflow/ready-next-absent (:reason (ex-data advance-error))))
          (is (re-find #"workflow defer" (:guidance (ex-data advance-error))))
          (is (= [defer-id] (mapv :id (:ready (ex-data advance-error)))))
          (is (= "active" (:state (weaver/show rt defer-id)))))
        (testing "and choose! refuses it as a non-checkpoint"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"not a checkpoint"
                                (workflow/choose! "role-1" :whatever))))))))

(deftest a-ready-defer-asks-for-attention-as-a-defer-not-as-work
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (register-defer-targets!)
      (start-at-defer! "await-1")
      (let [state (workflow/await! "await-1" {:timeout-secs 5 :poll-ms 10})]
        (is (= :defer (:reason state))
            "a defer is a decision, so it must not surface as a :self step to do")
        (is (= "perform-work" (:defer (:detail state))))
        (is (= ["wt-devflow" "wt-spike"] (:workflows (:detail state))))))))

(deftest a-pending-defer-blocks-done-and-never-cascades-shut
  ;; PROP-Dfr-001.S11: cascade-join-ids closes only procedure joins. If an
  ;; unfilled defer used that role, completing its sibling would close it over
  ;; an empty dependency set.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (let [result (workflow/start! "pending" (final-defer-workflow #{:wt-two-step}) {})
            defer (first (filter #(= "defer" (:role %)) (:ready result)))]
        (is (= "perform-work" (:defer defer)))
        (is (= ["wt-two-step"] (:workflows defer)))
        (is (false? (:done result)))
        (let [after-sibling (complete-ready! "pending" "Sibling work")]
          (is (= "active" (:state (weaver/show rt (:id defer)))))
          (is (false? (:done after-sibling))
              "an unfilled defer keeps the run unfinished"))
        (is (not-any? #(= (:id defer) (:id %))
                      (mapcat :events (workflow/run-history "pending")))
            "an unfilled defer emits no history event")))))

(deftest defer-refuses-a-malformed-persisted-path
  ;; Persisted attributes are an I/O boundary. Missing lineage must fail before
  ;; mutation instead of becoming an empty ancestry that lets a cycle through.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (doseq [[run-id bad-path] [["bad-path-shape" {}]
                                 ["bad-path-entry" [{"definition" nil}]]]]
        (workflow/start! run-id (defer-sandwich #{:wt-two-step}) {})
        (workflow/complete! run-id)
        (let [pending (workflow/ready-step run-id)]
          (weaver/update! rt (:id pending) {:attributes {"workflow/defer-path" bad-path}})
          (let [thrown (try (workflow/defer! run-id :wt-two-step)
                            (catch clojure.lang.ExceptionInfo e e))]
            (is (= :workflow/defer-path-invalid (:reason (ex-data thrown))))
            (is (contains? (ex-data thrown) :path))
            (is (= "active" (:state (weaver/show rt (:id pending))))
                "a malformed path fails before the fill mutates the defer")))))))

(workflow/defworkflow defer-self-target
  "A routine whose own defer may select it, which must be refused."
  {:entrypoints #{:start :call}}
  (workflow/bind-defers
   (workflow/workflow "Self" (workflow/defer :again "Choose again"))
   {:again #{:wt-self}}))

(workflow/defworkflow defer-nested-inner
  "A routine whose own defer must not be able to select it again."
  {:entrypoints #{:start :call}}
  (workflow/bind-defers
   (workflow/workflow "Inner" (workflow/defer :again "Choose again"))
   {:again #{:wt-nested-inner}}))

(workflow/defworkflow defer-nested-outer
  "A defer target that fixed-calls a routine declaring its own defer."
  {:entrypoints #{:call}}
  (workflow/workflow "Outer" (workflow/call :inner :wt-nested-inner {})))

(workflow/defworkflow defer-cycle-a
  "A routine whose defer selects the routine that may select it back."
  {:entrypoints #{:start :call}}
  (workflow/bind-defers
   (workflow/workflow "Cycle A" (workflow/defer :pick "Pick B"))
   {:pick #{:wt-cycle-b}}))

(workflow/defworkflow defer-cycle-b
  "The routine A selects, whose own defer may select A again or something new."
  {:entrypoints #{:call}}
  (workflow/bind-defers
   (workflow/workflow "Cycle B" (workflow/defer :pick-back "Pick again"))
   {:pick-back #{:wt-cycle-a :wt-two-step}}))

(deftest defer-refuses-a-direct-cycle-and-permits-siblings
  ;; DELTA-Dfr-001.CC5: the path is the lexical ancestry of one defer, so a
  ;; self-selection is refused while two siblings may both pick the same target.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (workflow/register-workflow! :wt-self 'millhouse.workflow-composition-test/defer-self-target)
      (testing "a defer may not select the definition it is declared in"
        (workflow/start! "cyclic" #'defer-self-target {})
        (let [pending (workflow/ready-step "cyclic")
              thrown (try (workflow/defer! "cyclic" :wt-self)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/defer-cyclic (:reason (ex-data thrown))))
          (is (= "active" (:state (weaver/show rt (:id pending))))
              "nothing mutated: the point is still fillable with something else")))
      (testing "two sibling defers may both select the same target"
        (let [definition (workflow/bind-defers
                          (workflow/workflow
                           "Two points"
                           (workflow/defer :first-pick "First")
                           (workflow/defer :second-pick "Second"))
                          {:first-pick #{:wt-two-step} :second-pick #{:wt-two-step}})
              result (workflow/start! "siblings" definition {})
              ids (mapv :id (filter #(= "defer" (:role %)) (:ready result)))]
          (workflow/defer! "siblings" :wt-two-step {} {:step (first ids)})
          (workflow/defer! "siblings" :wt-two-step {} {:step (second ids)})
          (is (every? #(= "procedure" (get-in (weaver/show rt %) [:attributes :workflow/role])) ids)
              "neither sibling is in the other's ancestry, so neither is a cycle"))))))

(deftest a-poured-expansion-keeps-its-fixed-call-ancestry
  ;; DELTA-Dfr-001.CC5. A defers to B; B fixed-calls C; C declares a defer. C
  ;; must be in that defer's path, or it can select itself.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (workflow/register-workflow! :wt-nested-inner
                                   'millhouse.workflow-composition-test/defer-nested-inner)
      (workflow/register-workflow! :wt-nested-outer
                                   'millhouse.workflow-composition-test/defer-nested-outer)
      (workflow/start! "nested" (defer-sandwich #{:wt-nested-outer}) {})
      (workflow/complete! "nested")
      (workflow/defer! "nested" :wt-nested-outer)
      (let [inner (first (filter #(= "defer" (:role %)) (workflow/ready "nested")))
            thrown (try (workflow/defer! "nested" :wt-nested-inner {} {:step (:id inner)})
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/defer-cyclic (:reason (ex-data thrown)))
            "the poured path kept the fixed-call ancestor, so selecting it is a cycle")))))

(deftest defer-refuses-a-nested-cycle-and-permits-acyclic-nesting
  ;; PROP-Dfr-001.S5: A -> B -> A fails at the second fill, because filling a
  ;; defer extends the path with the selected target's fingerprint.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      ;; mutual references cannot both be staged at once, so B is registered as a
      ;; plain callable first and repointed to its real definition afterwards
      (workflow/register-workflow! :wt-cycle-b
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (workflow/register-workflow! :wt-cycle-a 'millhouse.workflow-composition-test/defer-cycle-a)
      (workflow/register-workflow! :wt-cycle-b 'millhouse.workflow-composition-test/defer-cycle-b)
      (workflow/start! "cycles" #'defer-cycle-a {})
      (workflow/defer! "cycles" :wt-cycle-b)
      (let [inner (first (filter #(= "defer" (:role %)) (workflow/ready "cycles")))]
        (is (= "pick-back" (:defer inner)))
        (let [thrown (try (workflow/defer! "cycles" :wt-cycle-a {} {:step (:id inner)})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/defer-cyclic (:reason (ex-data thrown)))
              "A is already in this defer's ancestry")
          (is (= 2 (count (:path (ex-data thrown))))))
        (testing "an acyclic nested selection still fills"
          (workflow/defer! "cycles" :wt-two-step {} {:step (:id inner)})
          (is (= ["Plan default"] (ready-titles "cycles"))))))))

(deftest defer-cycle-refusal-survives-stored-fingerprint-mismatch
  ;; A stored digest may differ from the live definition's digest. Symbol
  ;; ancestry must still reject A -> B -> A. This mutates persisted data in one
  ;; runtime; it does not prove restart or replacement-generation adoption.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (workflow/register-workflow! :wt-cycle-b
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (workflow/register-workflow! :wt-cycle-a 'millhouse.workflow-composition-test/defer-cycle-a)
      (workflow/register-workflow! :wt-cycle-b 'millhouse.workflow-composition-test/defer-cycle-b)
      (workflow/start! "regen" #'defer-cycle-a {})
      (workflow/defer! "regen" :wt-cycle-b)
      (let [inner (first (filter #(= "defer" (:role %)) (workflow/ready "regen")))
            path (get-in (weaver/show rt (:id inner)) [:attributes :workflow/defer-path])
            changed-path (mapv #(assoc % :fingerprint (str "0000000000000000" (:fingerprint %)))
                               path)]
        (is (= 2 (count path)))
        (is (every? :definition path)
            "every ancestry entry of a registered routine records its symbol")
        (weaver/update! rt (:id inner) {:attributes {"workflow/defer-path" changed-path}})
        (let [thrown (try (workflow/defer! "regen" :wt-cycle-a {} {:step (:id inner)})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/defer-cyclic (:reason (ex-data thrown)))
              "the definition symbol carries the ancestry when the digest cannot"))
        (testing "and a genuinely different routine still fills"
          (workflow/defer! "regen" :wt-two-step {} {:step (:id inner)})
          (is (= ["Plan default"] (ready-titles "regen"))))))))

(deftest defer-materializes-a-multi-step-expansion-with-colliding-ids
  ;; Prefixing is what keeps a target whose step ids are :a and :c disjoint from
  ;; a caller that already uses :a and :c, and the expansion's entry must inherit
  ;; the defer's own depends-on.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-fanout 'millhouse.workflow-composition-test/defer-fanout-target)
      (workflow/start! "fanout" (defer-sandwich #{:wt-fanout}) {})
      (workflow/complete! "fanout")
      (let [pending (workflow/ready-step "fanout")
            filled (workflow/defer! "fanout" :wt-fanout)
            root-id (:id (workflow/current-root "fanout"))
            strands (:strands (graph/subgraph rt [root-id]))
            titles (set (map :title strands))]
        (is (= ["Target a"] (mapv :title (:ready filled)))
            "only the expansion's entry is ready; the caller's own :a and :c are untouched")
        (is (every? titles ["Step a" "Step c" "Target a" "Target left" "Target right" "Target c"])
            "colliding ids materialize as distinct prefixed strands")
        (testing "the whole fan-out runs and returns to the caller"
          (workflow/complete! "fanout")
          (is (= #{"Target left" "Target right"} (set (ready-titles "fanout"))))
          (workflow/complete! "fanout" {:step (:id (first (workflow/ready "fanout")))})
          (workflow/complete! "fanout" {:step (:id (first (workflow/ready "fanout")))})
          (is (= ["Target c"] (ready-titles "fanout")))
          (let [after-c (workflow/complete! "fanout")]
            (is (= ["Step c"] (mapv :title (:ready after-c)))
                "step c waits for the whole expansion, then becomes ready")
            (is (= "closed" (:state (weaver/show rt (:id pending)))))))))))

(deftest defer-into-an-empty-target-does-not-stall-the-run
  ;; An empty or fully-conditioned-out target yields no exits, so the join must
  ;; still close rather than leaving an invisible active procedure forever.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-empty 'millhouse.workflow-composition-test/defer-empty-target)
      (workflow/start! "empty-target" (defer-sandwich #{:wt-empty}) {})
      (workflow/complete! "empty-target")
      (let [pending (workflow/ready-step "empty-target")
            filled (workflow/defer! "empty-target" :wt-empty)]
        (is (= "closed" (:state (weaver/show rt (:id pending))))
            "a join with no expansion to wait for closes immediately")
        (is (= ["Step c"] (mapv :title (:ready filled)))
            "the declaring workflow continues instead of stalling"))
      (testing "and a final empty defer finishes the run in the fill batch"
        (workflow/start! "empty-final"
                         (workflow/bind-defers
                          (workflow/workflow "Only a defer"
                                             (workflow/defer :perform-work "Choose"))
                          {:perform-work #{:wt-empty}})
                         {})
        (is (true? (:done (workflow/defer! "empty-final" :wt-empty))))))))

(deftest a-filled-defer-cannot-be-filled-again
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (workflow/start! "double" (defer-sandwich #{:wt-two-step}) {})
      (workflow/complete! "double")
      (let [pending-id (:id (workflow/ready-step "double"))]
        (workflow/defer! "double" :wt-two-step)
        (testing "a filled defer is no longer ready, so naming it is not-ready"
          (let [thrown (try (workflow/defer! "double" :wt-two-step {} {:step pending-id})
                            (catch clojure.lang.ExceptionInfo e e))]
            (is (re-find #"not ready" (ex-message thrown)))
            (is (= pending-id (:step (ex-data thrown))))))
        (testing "naming a ready strand of another role is the step-not-defer failure"
          (let [ready-step-id (:id (workflow/ready-step "double"))
                thrown (try (workflow/defer! "double" :wt-two-step {} {:step ready-step-id})
                            (catch clojure.lang.ExceptionInfo e e))]
            (is (= :workflow/step-not-defer (:reason (ex-data thrown))))))))))

(defn reject-defer-batch-hook
  "Reject the defer batch to exercise transaction rollback."
  [ctx]
  (throw (ex-info "defer batch rejected" {:code "policy/rejected" :ctx ctx})))

(deftest a-failing-defer-apply-commits-nothing
  ;; The fill is one batch. A rejected apply must leave the defer ready and pour
  ;; no part of the expansion, not a half-materialized run to unpick by hand.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (workflow/start! "atomic" (defer-sandwich #{:wt-two-step}) {})
      (workflow/complete! "atomic")
      (let [pending (workflow/ready-step "atomic")
            root-id (:id (workflow/current-root "atomic"))
            before (count (:strands (graph/subgraph rt [root-id])))]
        (hooks/register-hook! rt :reject-defer #{:batch/apply-before-commit}
                              'millhouse.workflow-composition-test/reject-defer-batch-hook {})
        (let [thrown (try (workflow/defer! "atomic" :wt-two-step)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? (ex-data thrown)) "the rejected apply surfaces as a failure"))
        (is (= before (count (:strands (graph/subgraph rt [root-id]))))
            "no expansion strand was poured")
        (let [still (weaver/show rt (:id pending))]
          (is (= "active" (:state still)))
          (is (= "defer" (get-in still [:attributes :workflow/role]))
              "the point was not converted to a join by the failed batch"))))))

(deftest a-checkpoint-cutover-closes-an-unfilled-sibling-defer
  ;; closeable-roles is every strand the engine poured under an abandoned root.
  ;; Omitting defer would leave a pending selection point active beneath a root
  ;; that has already been replaced — and it must not become a history event.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-two-step
                                   'millhouse.workflow-composition-test/defer-two-step-target)
      (workflow/register-workflow! :wt-second
                                   'millhouse.workflow-composition-test/registry-second-stage)
      (let [definition (workflow/bind-defers
                        (workflow/workflow
                         "Router with a pending defer"
                         (workflow/checkpoint :go "Go"
                                              :kind :agent
                                              :choices [{:key :advance :label "Advance"
                                                         :next :wt-second}])
                         (workflow/defer :perform-work "Choose work"))
                        {:perform-work #{:wt-two-step}})
            result (workflow/start! "defer-cutover" definition {})
            defer-id (:id (first (filter #(= "defer" (:role %)) (:ready result))))
            go-id (:id (first (filter #(= "checkpoint" (:role %)) (:ready result))))]
        (is (= "active" (:state (weaver/show rt defer-id))))
        ;; the selector is required because a pending defer is ready beside the
        ;; checkpoint, and trusted choose! resolves the sole ready step by id
        ;; rather than filtering by role — the CLI is where roles partition.
        (workflow/choose! "defer-cutover" :advance {} {:step go-id})
        (is (= "closed" (:state (weaver/show rt defer-id)))
            "the route's cutover force-closes the pending defer with the old root")
        (is (not-any? #(= defer-id (:id %))
                      (mapcat :events (workflow/run-history "defer-cutover")))
            "a force-closed defer was never acted on, so history omits it")))))

(deftest concurrent-defer-fills-serialize-under-the-run-guard
  ;; Both fills resolve their frontier inside the guard, so one wins and the
  ;; other re-resolves against the frontier it left rather than filling twice.
  ;; Keep file storage for this concurrent-caller proof.
  (with-embedded-runtime {:storage :sqlite-file}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (register-defer-targets!)
      (start-at-defer! "race-1")
      (let [attempts (mapv (fn [target]
                             (future
                               (current/with-runtime rt
                                 (try {:ok (workflow/defer! "race-1" target
                                                            {:feature "raced"})}
                                      (catch clojure.lang.ExceptionInfo e {:err e})))))
                           [:wt-devflow :wt-spike])
            ;; bounded: a regression that deadlocks the guard must fail this test
            ;; rather than hang the suite, so the deref gives up and cancels
            results (mapv (fn [attempt]
                            (let [result (deref attempt (test-support/await-budget-ms) ::timeout)]
                              (when (= ::timeout result)
                                (future-cancel attempt))
                              result))
                          attempts)
            winners (filter :ok results)
            losers (filter :err results)]
        (is (not-any? #(= ::timeout %) results)
            "both fills returned; a timeout here means the guard deadlocked")
        (is (= 1 (count winners)) "exactly one fill pours")
        (is (= 1 (count losers)))
        (is (contains? #{:workflow/step-not-defer :workflow/params-invalid}
                       (:reason (ex-data (:err (first losers)))))
            "the loser re-resolved and found the winner's frontier, not its own point")
        (is (some? (workflow/current-root "race-1"))
            "one active root, never two under one run id")))))
