(ns millhouse.spools.auto-run-recovery
  "Register an authorized, already accepted delivery-worker continuation."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.assignment :as assignment]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(s/def ::text (s/and string? (complement str/blank?)))
(s/def ::card ::text)
(s/def ::worker ::text)
(s/def ::expected-current-worker ::text)
(s/def ::reason ::text)
(s/def ::by-identity ::text)
(s/def ::registration
  (s/keys :req-un [::card ::worker ::expected-current-worker ::reason ::by-identity]))

(defn- published? [run]
  (= "true" (attr-get run :harness/published)))

(defn- accepted-children [rt id]
  (->> (concat (graph/incoming-edges rt [id] "continues")
               (graph/incoming-edges rt [id] "resumes"))
       (map :from_strand_id)
       distinct
       (map #(harnesses/run rt %))
       (filter published?)))

(defn- delivery-strands [rt card]
  (let [run-id (attr-get card :auto-run/workflow-run-id)
        root (when run-id
               (current/with-runtime rt (workflow/current-root run-id)))]
    (when-not (and root (= "active" (:state root)))
      (fail! "Recovery requires an active recorded delivery root" {:card (:id card)}))
    (:strands (graph/subgraph rt [(:id root)]))))

(defn- finisher-target [card-id strands]
  (let [finishers (filter #(= "finisher" (attr-get % :auto-run/role)) strands)]
    (when-not (and (= 1 (count finishers))
                   (= card-id (attr-get (first finishers) :auto-run/card)))
      (fail! "Expected one matching finisher custody target" {:card card-id}))
    (first finishers)))

(defn- require-unfrozen! [rt card strands]
  (let [target (finisher-target (:id card) strands)
        request-id (str "auto-land-finisher/" (:id target))
        accepted (filter #(and (published? %)
                               (or (= (:id target) (attr-get % :harness/target))
                                   (= request-id (attr-get % :harness/request-id))))
                         (weaver/list rt [:= [:attr "harness/run"] "true"] {}))]
    (when (or (seq accepted)
              (attr-get target :auto-run/worker-run-id)
              (attr-get target :auto-run/finisher-run-id))
      (fail! "Landing handoff is frozen; reconcile the retained finisher instead"
             {:card (:id card) :accepted (mapv :id accepted)}))))

(defn- require-link! [rt card strands predecessor worker]
  (let [target (attr-get worker :harness/target)
        target-strand (some #(when (= target (:id %)) %) strands)
        predecessor-id (:id predecessor)
        relation (cond
                   (= predecessor-id (attr-get worker :harness/after)) "continues"
                   (= predecessor-id (attr-get worker :harness/resumes)) "resumes")]
    (when-not (and (published? predecessor) (published? worker)
                   relation
                   (some #(= predecessor-id (:to_strand_id %))
                         (graph/outgoing-edges rt [(:id worker)] relation)))
      (fail! "Recovery path must follow accepted continuation links"
             {:predecessor predecessor-id :worker (:id worker)}))
    (when-not (and (= "true" (attr-get predecessor :harness/settled))
                   (contains? #{"stopped" "failed"} (attr-get predecessor :harness/status)))
      (fail! "Recovery predecessor has not settled" {:predecessor predecessor-id}))
    (when-not (and (= target (attr-get predecessor :harness/target))
                   (or (= target (:id card))
                       (and (= "handoff-worker" (attr-get target-strand :auto-run/role))
                            (= (:id card) (attr-get target-strand :auto-run/card))))
                   (some? (attr-get predecessor :harness/logical-id))
                   (= (attr-get predecessor :harness/logical-id)
                      (attr-get worker :harness/logical-id))
                   (= (attr-get predecessor :harness/root-targets)
                      (attr-get worker :harness/root-targets))
                   (= (attr-get card :auto-run/worktree)
                      (attr-get predecessor :harness/cwd)
                      (attr-get worker :harness/cwd)))
      (fail! "Recovery must retain delivery task, logical root and worktree ownership"
             {:card (:id card) :worker (:id worker) :target target}))))

(defn- require-lineage! [rt card strands predecessor worker]
  (loop [cursor predecessor path []]
    (when (some #{(:id cursor)} path)
      (fail! "Recovery lineage contains a cycle" {:path path :run (:id cursor)}))
    (let [children (vec (accepted-children rt (:id cursor)))
          path (conj path (:id cursor))]
      (if (= (:id cursor) (:id worker))
        (do
          (when (seq children)
            (fail! "Recovery worker is not the accepted continuation head"
                   {:worker (:id worker) :children (mapv :id children)}))
          path)
        (do
          (when-not (= 1 (count children))
            (fail! "Recovery requires a unique accepted continuation path"
                   {:path path :children (mapv :id children)}))
          (require-link! rt card strands cursor (first children))
          (recur (first children) path))))))

(defn verify-worker!
  "Verify successful current-worker settlement and the accepted finisher custody.

  Return durable run IDs for the code gate's evidence. A stopped process, stale
  worker receipt, wrong finisher target or changed canonical cwd fails loudly."
  [rt card-id]
  (let [card (weaver/show rt card-id)
        strands (delivery-strands rt card)
        target (finisher-target card-id strands)
        worker-id (attr-get target :auto-run/worker-run-id)
        finisher-id (attr-get target :auto-run/finisher-run-id)
        worker (harnesses/run rt worker-id)
        worker-target (attr-get worker :harness/target)
        worker-step (some #(when (= worker-target (:id %)) %) strands)
        finisher (harnesses/run rt finisher-id)]
    (when-not (and (not= worker-id finisher-id)
                   (or (= card-id worker-target)
                       (and (= "handoff-worker" (attr-get worker-step :auto-run/role))
                            (= card-id (attr-get worker-step :auto-run/card))))
                   (= worker-id (attr-get card :auto-run/run-id))
                   (published? worker) (published? finisher)
                   (empty? (accepted-children rt worker-id))
                   (assignment/target-ready? rt (:id target))
                   (= (:id target) (attr-get finisher :harness/target))
                   (= (str "auto-land-finisher/" (:id target))
                      (attr-get finisher :harness/request-id))
                   (some? (attr-get target :auto-run/canonical-root))
                   (= (attr-get target :auto-run/canonical-root)
                      (attr-get finisher :harness/cwd))
                   (= "true" (attr-get worker :harness/settled))
                   (= "stopped" (attr-get worker :harness/status))
                   (= "completed" (attr-get worker :harness/substatus))
                   (some? (attr-get worker :harness/exit-code))
                   (zero? (attr-get worker :harness/exit-code)))
      (fail! "Current worker has not settled successfully under frozen finisher custody"
             {:card card-id :worker worker-id :finisher finisher-id}))
    {:card card-id :worker worker-id :finisher finisher-id :verified true}))

(defn verify-worker
  "Code-executor callback recording verified settlement evidence."
  [{:keys [card]}]
  (verify-worker! (current/runtime) card))

(defn register-worker!
  "Register an accepted current worker after explicit coordinator authorization.

  Require the expected prior receipt, a unique published continuation path,
  every predecessor settled, unchanged task/root/worktree and no frozen handoff.
  Exact request replay is a no-op, including after subsequent finisher acceptance.
  Persist the reason and actor with the new receipt in one card update. This does
  not launch, retry, approve, claim, clear blockers or mutate Harnesses lineage.

  Requires Harnesses' public call-with-run-publication-lock boundary. It serializes
  these checks with run publication, not arbitrary raw graph edits. Actor and
  reason record provenance; callers must obtain real recovery authorization."
  [rt request]
  (require-valid! ::registration request "Invalid recovery-worker registration")
  ((or (runtime/resolve-var rt 'ct.spools.harnesses/call-with-run-publication-lock)
       (fail! "Recovery registration requires Harnesses call-with-run-publication-lock"
              {:required-api 'ct.spools.harnesses/call-with-run-publication-lock}))
   rt
   (fn []
     (let [{:keys [card worker expected-current-worker]} request
           card-strand (weaver/show rt card)
           receipt (attr-get card-strand :auto-run/run-id)
           recorded (attr-get card-strand :auto-run/recovery-registration)
           replay? (and (= worker receipt) (= request recorded))]
       (if replay?
         {:card card :worker worker :result "already-registered"}
         (do
           (when-not (and (= "active" (:state card-strand))
                          (= "true" (attr-get card-strand :kanban/card))
                          (= "feature" (attr-get card-strand :kanban/type))
                          (= "assigned" (attr-get card-strand :auto-run/status))
                          (= expected-current-worker receipt)
                          (not= worker expected-current-worker))
             (fail! "Recovery registration does not match the current delivery receipt"
                    {:card card :expected expected-current-worker :actual receipt}))
           (let [strands (delivery-strands rt card-strand)
                 predecessor (harnesses/run rt expected-current-worker)
                 accepted (harnesses/run rt worker)
                 path (require-lineage! rt card-strand strands predecessor accepted)]
             (require-unfrozen! rt card-strand strands)
             (weaver/update! rt card
                             {:attributes {:auto-run/run-id worker
                                           :auto-run/recovery-registration request
                                           :auto-run/recovery-path path}})
             {:card card :worker worker :result "registered"})))))))
