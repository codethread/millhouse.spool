(ns millhouse.config.consumer-provenance-smoke
  "Exercise durable cross-spool attribution in a disposable consumer world."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millhouse.harnesses :as harnesses]
            [millhouse.harnesses.assignment :as assignment]
            [millhouse.identity :as identity]
            [millhouse.kanban :as kanban]
            [millhouse.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as t]))

(def ^:private project-root (.getCanonicalPath (io/file "../..")))

(def ^:private consumer-deps-edn
  (pr-str
   {:deps
    {'millhouse/config {:local/root (str project-root "/spools/config")}}}))

(def ^:private consumer-init
  "(require '[millstrand.api.current.alpha :as current]
            '[millstrand.api.runtime.alpha :as runtime])
   (let [rt (current/runtime)]
     (runtime/module! rt :identity
       {:ns 'millhouse.identity :required? true})
     (runtime/module! rt :workflow
       {:ns 'millhouse.workflow :required? true})
     (runtime/module! rt :kanban
       {:ns 'millhouse.kanban
        :after [:identity :workflow]
        :required? true})
     (runtime/module! rt :consumer/base
       {:file \"consumer_base.clj\"
        :after [:identity :workflow :kanban]
        :required? true})
     (runtime/module! rt :consumer/aliases
       {:ns 'millhouse.config.consumer-fixture
        :after [:consumer/base]
        :required? true}))")

(def ^:private consumer-files
  {"consumer_base.clj"
   "(ns consumer.base
      (:require [millhouse.harnesses :as harnesses]
                [millhouse.harnesses.assignment :as assignment]
                [millhouse.kanban :as kanban]
                [millstrand.api.lifecycle.alpha :as lifecycle]))
    (lifecycle/use-resource!
     harnesses/harness-core-runtime
     assignment/assignment-runtime
     kanban/kanban-runtime)"})

(defn- consumer-world-options [root]
  {:storage :sqlite-file
   :root root
   :deps-edn consumer-deps-edn
   :init-clj consumer-init
   :files consumer-files})

(defn- add-registered-identity!
  [rt friendly-id]
  (weaver/add! rt {:title friendly-id
                   :attributes {:identity/session "true"
                                :identity/id friendly-id
                                :identity/harness "consumer-test"
                                :identity/native-session-id
                                (str "consumer-test-" friendly-id)}}))

(defn- temporary-directory! [prefix]
  (let [path (java.io.File/createTempFile prefix "" (io/file "/tmp"))]
    (when-not (.delete path)
      (throw (ex-info "Cannot prepare temporary test directory" {:path path})))
    (when-not (.mkdir path)
      (throw (ex-info "Cannot create temporary test directory" {:path path})))
    path))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (when-not (.delete file)
      (throw (ex-info "Cannot remove temporary test file" {:path file})))))

(defn- incoming-ids [rt strand-id relation]
  (->> (graph/incoming-edges rt [strand-id] relation)
       (mapv :from_strand_id)
       sort
       vec))

(defn- outgoing-ids [rt strand-id relation]
  (->> (graph/outgoing-edges rt [strand-id] relation)
       (mapv :to_strand_id)
       sort
       vec))

(defn- context-value [run key]
  (let [context (attr-get run :harness/context)]
    (or (get context key) (get context (keyword key)))))

(deftest provenance-history-survives-reconciliation-and-consumer-restart
  (let [root (temporary-directory! "codethread-provenance-consumer-")
        evidence (atom nil)]
    (try
      (t/run-with-weaver-world
       (consumer-world-options root)
       (fn [{:keys [runtime]}]
         (harnesses/register-harness!
          runtime :consumer-fake
          {:modes #{:headless}
           :prepare 'millhouse.harnesses/create!
           :finish 'millhouse.harnesses/finish!})
         (let [card-result
               (kanban/add! runtime "Reported, unowned consumer feature"
                            {"--reported-by" "original-reporter"})
               card-id (get-in card-result [:card :id])
               note-result
               (kanban/note! runtime card-id "Awaiting a late actor registration."
                             {"--by-identity" "late-actor"})
               note-id (get-in note-result [:strand :id])
               unresolved
               (first (identity/inspect-attributions runtime [note-id]))
               unowned-view (kanban/card-view runtime card-id)
               first-claim
               (kanban/claim! runtime card-id
                              {"--owner" "owner-a-unresolved"
                               "--branch" "provenance/a-1"})
               second-claim
               (kanban/claim! runtime card-id
                              {"--owner" "owner-b-resolved"
                               "--by-identity" "handoff-actor"
                               "--branch" "provenance/b"})
               third-claim
               (kanban/claim! runtime card-id
                              {"--owner" "owner-a-unresolved"
                               "--branch" "provenance/a-2"})
               claims (kanban/ownership-history runtime card-id)
               task-id (get-in (kanban/task-add! runtime card-id "Delegate history" {})
                               [:task :id])
               delegated
               (assignment/assign!
                runtime {:harness :consumer-fake
                         :target task-id
                         :cwd "/tmp"
                         :by-identity "delegating-actor"
                         :request-id "consumer-provenance-delegation"})
               _ (harnesses/finish! runtime (:id delegated)
                                    {:status :done
                                     :exit-code 0
                                     :result "delegated task complete"})
               continued
               (assignment/assign!
                runtime {:harness :consumer-fake
                         :target task-id
                         :cwd "/tmp"
                         :after (:id delegated)
                         :request-id "consumer-provenance-continuation"})
               workflow-run-id "consumer-provenance-workflow"
               workflow-history
               (current/with-runtime runtime
                 (workflow/start!
                  workflow-run-id
                  (workflow/workflow
                   "Consumer provenance"
                   (workflow/step :actor "Actor-owned mutation" :self)
                   (workflow/gate :executor "Executor-owned mutation" :agent
                                  :depends-on [:actor]))
                  {})
                 (workflow/complete! workflow-run-id {:by-identity "workflow-actor"})
                 (workflow/complete! workflow-run-id
                                     {:executor "consumer-executor"
                                      :executor-run-id (:id continued)})
                 (workflow/run-history workflow-run-id))
               reporter (add-registered-identity! runtime "original-reporter")
               actor (add-registered-identity! runtime "late-actor")
               owner-b (add-registered-identity! runtime "owner-b-resolved")
               _ (t/await-quiescent! runtime)
               _ (identity/reconcile-attributions! runtime)
               repeated (identity/reconcile-attributions! runtime)
               view (kanban/card-view runtime card-id)]
           (testing "unowned reporting and unresolved attribution are durable"
             (is (nil? (get-in unowned-view [:ownership :current])))
             (is (= :unresolved (:status unresolved)))
             (is (= "late-actor" (:identity unresolved))))
           (testing "claims are ordered records rather than a scalar snapshot"
             (is (= "claimed" (:result first-claim)))
             (is (= "handed-off" (:result second-claim) (:result third-claim)))
             (is (= ["owner-a-unresolved" "owner-b-resolved" "owner-a-unresolved"]
                    (mapv :owner claims)))
             (is (= [1 2 3] (mapv :order claims)))
             (is (nil? (attr-get (weaver/show runtime card-id) :owner)))
             (is (= "owner-a-unresolved"
                    (get-in view [:ownership :current :owner])))
             (is (= [] (get-in view [:ownership :current
                                     :owner-identity-strand-ids])))
             (is (= [(:id owner-b)]
                    (incoming-ids runtime (get-in second-claim [:claim :id])
                                  "claimed"))))
           (testing "reporter and late actor reconcile without rewriting sources"
             (is (= [(:id reporter)] (incoming-ids runtime card-id "reported")))
             (is (= [(:id actor)] (incoming-ids runtime note-id "attributed")))
             (is (zero? (:writes repeated))))
           (testing "delegation, continuation, and workflow roles keep their edges"
             (is (= [task-id] (outgoing-ids runtime (:id delegated) "serves")))
             (is (= [card-id] (outgoing-ids runtime (:id delegated) "serves-root")))
             (is (= (:id delegated)
                    (context-value continued "assignment/after")))
             (is (= (attr-get delegated :harness/logical-id)
                    (attr-get continued :harness/logical-id)))
             (is (some #(= "workflow-actor" (:by-identity %))
                       (get-in workflow-history [0 :events])))
             (is (some #(= "consumer-executor" (:executor %))
                       (get-in workflow-history [0 :events])))
             (is (some #(= (:id continued) (:executor-run-id %))
                       (get-in workflow-history [0 :events])))
             (is (every? #(or (:by-identity %) (:executor %))
                         (get-in workflow-history [0 :events]))))
           (reset! evidence {:card-id card-id
                             :note-id note-id
                             :second-claim (get-in second-claim [:claim :id])
                             :reporter-id (:id reporter)
                             :actor-id (:id actor)
                             :owner-b-id (:id owner-b)
                             :delegated-id (:id delegated)
                             :continued-id (:id continued)
                             :workflow-run-id workflow-run-id}))))
      (t/run-with-weaver-world
       (consumer-world-options root)
       (fn [{:keys [runtime]}]
         (t/await-quiescent! runtime)
         (let [{:keys [card-id note-id second-claim reporter-id actor-id owner-b-id
                       delegated-id continued-id workflow-run-id]} @evidence
               view (kanban/card-view runtime card-id)
               attribution (first (identity/inspect-attributions runtime [note-id]))
               workflow-history
               (current/with-runtime runtime (workflow/run-history workflow-run-id))]
           (testing "restart reconstructs graph projections from source records"
             (is (= "original-reporter" (get-in view [:reporter :identity])))
             (is (= "owner-a-unresolved" (get-in view [:ownership :current :owner])))
             (is (= [] (get-in view [:ownership :current
                                     :owner-identity-strand-ids])))
             (is (= ["owner-a-unresolved" "owner-b-resolved" "owner-a-unresolved"]
                    (mapv :owner (get-in view [:ownership :history]))))
             (is (= :resolved (:status attribution)))
             (is (= [actor-id] (:linked-identity-strand-ids attribution)))
             (is (= [reporter-id] (incoming-ids runtime card-id "reported")))
             (is (= [owner-b-id] (incoming-ids runtime second-claim "claimed")))
             (is (= [card-id] (outgoing-ids runtime delegated-id "serves-root")))
             (is (= delegated-id
                    (context-value (weaver/show runtime continued-id)
                                   "assignment/after")))
             (is (some #(= continued-id (:executor-run-id %))
                       (get-in workflow-history [0 :events])))
             (is (zero? (:writes (identity/reconcile-attributions! runtime))))))))
      (finally
        (delete-tree! root)))))
