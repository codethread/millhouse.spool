(ns millhouse.devflow-kanban-adapter-test
  "Tests the kanban adapter root: its registered catalogue additions and the
  kanban-bound decompose variant. Kanban itself and devflow each own their
  behavior; this suite covers only the binding."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millhouse.devflow-kanban-adapter :as adapter]
            ;; The adapter no longer requires the kanban namespace itself, so
            ;; the test world loads it for the :millhouse/kanban module
            ;; activation below (a real world gets it as an approved spool root).
            [millhouse.identity]
            [millhouse.kanban :as kanban]
            [millstrand.api.patterns.alpha :as patterns]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millhouse.workflow :as workflow]
            [millstrand.test.alpha :as t]))

(defn- adapter-manifest []
  (let [adapter-root (t/spool-checkout-root "millhouse/devflow_kanban_adapter.clj")]
    (edn/read-string (slurp (io/file adapter-root "deps.edn")))))

(deftest adapter-publishes-devflow-as-a-peer-dependency
  (let [{:keys [deps aliases]} (adapter-manifest)]
    (is (not (contains? deps 'millhouse/devflow))
        "the published adapter must not leak its development checkout as a local dependency")
    (is (= {:local/root ".."}
           (get-in aliases [:test :extra-deps 'millhouse/devflow]))
        "adapter tests retain the local development bridge to the sibling Devflow root")))

(defn- activate! [rt]
  (doseq [[key config] [[:millhouse/workflow {:ns 'millhouse.workflow}]
                        [:millhouse/identity {:ns 'millhouse.identity}]
                        [:devflow {:ns 'millhouse.devflow
                                   :after [:millhouse/workflow]}]
                        [:millhouse/kanban {:ns 'millhouse.kanban}]
                        [:devflow-kanban-adapter {:ns 'millhouse.devflow-kanban-adapter
                                          :after [:millhouse/workflow :devflow :millhouse/kanban]}]]]
    (let [result (runtime/module! rt key config)
          status (get-in result [:modules key :status])]
      (when-not (contains? #{:applied :unchanged} status)
        (throw (ex-info "Module activation failed" {:module key :result result})))))
  rt)

(defn- with-runtime [f]
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (activate! (:runtime ctx))]
      (current/with-runtime rt
        (f rt)))))

(deftest adapter-accretes-its-definitions-beside-devflow
  (with-runtime
    (fn [_]
      (let [names (set (keys (workflow/workflows)))]
        (is (contains? names :author-kanban-cards))
        (is (contains? names :decompose-kanban))
        (is (contains? names :decompose)
            "devflow's own stage catalogue stays intact beside the adapter"))
      (is (= #{:call} (:entrypoints (workflow/resolve-workflow :author-kanban-cards))))
      (is (= #{:continue :call}
             (:entrypoints (workflow/resolve-workflow :decompose-kanban)))))))

(deftest repoint-rejects-invalid-boundary-data-without-a-world
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid repoint-decompose! input"
                        (adapter/repoint-decompose! {})))
  (let [error (try
                (adapter/repoint-decompose! {:runtime nil :unexpected true})
                (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= [:runtime] (:allowed (ex-data error))))
    (is (= [:runtime :unexpected] (:received (ex-data error))))
    (is (str/includes? (.getMessage error) "allowed keys")))
  (doseq [[context received] [[nil nil] [{} []]
                              [{{:not-a-keyword "metadata"} nil}
                               [{:not-a-keyword "metadata"}]]]]
    (let [error (try
                  (adapter/repoint-decompose-seed! context)
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= {:required-keys [:runtime]
              :metadata {:keys :keyword :values :any}}
             (:allowed (ex-data error))))
      (is (= received (:received (ex-data error)))))))

(deftest repoint-seed-installs-the-kanban-binding
  (with-runtime
    (fn [rt]
      (let [context {:runtime rt :module/key :adapter :effect/id :seed
                     :opaque {:preserved? true}}]
        (is (s/valid? ::adapter/repoint-seed-context context))
        (is (= {:repointed :decompose}
               (adapter/repoint-decompose-seed! context))))
      (is (= 'millhouse.devflow-kanban-adapter/decompose-kanban
             (:definition (workflow/resolve-workflow :decompose)))))))

(deftest publication-receipts-survive-resume-and-return-the-exact-review-set
  (with-runtime
    (fn [rt]
      (workflow/start! "published" #'adapter/decompose-kanban
                       {:feature "published" :card-reviewer "reviewer"
                        :card-set-reviewer "set-reviewer"})
      (is (= ["author-card-strands" "author-kanban-cards"]
             (:workflows (workflow/ready-step "published"))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (workflow/defer! "published" :author-kanban-cards {:feature "published"})))
      (is (= "author-cards" (:defer (workflow/ready-step "published"))))
      (workflow/defer! "published" :author-kanban-cards
                       {:feature "published" :repository "repo" :mainline "main"
                        :merged-revision "abc123" :proposal-path "proposal.md"
                        :merge-evidence "merge-record"})
      (let [draft-step (workflow/ready-step "published")
            draft {:reference "draft-42" :repository "repo"
                   :proposal-path "proposal.md" :merged-revision "abc123"}]
        (is (= "implementation cards" (:artifact draft-step)))
        (workflow/complete! "published" {:attributes {"devflow/breakdown-draft" draft}})
        (is (= draft (attr-get (weaver/show rt (:id draft-step)) :devflow/breakdown-draft)))
        (let [epic-step (workflow/ready-step "published")
              epic-id (get-in (kanban/add! rt "Published epic"
                                          {"--type" "epic" "--source" "draft-42"}) [:card :id])
              epic-receipt {:id epic-id :draft "draft-42"}]
          (is (= "Publish or recover the kanban epic for published" (:title epic-step)))
          ;; Simulate interruption after external creation and receipt storage,
          ;; before workflow completion. Resumption reads, not republishes.
          (weaver/update! rt (:id epic-step) {:attributes {"devflow/epic-receipt" epic-receipt}})
          (is (= (:id epic-step) (:id (workflow/ready-step "published"))))
          (workflow/complete! "published"
                              {:attributes {"devflow/epic-receipt"
                                            (attr-get (weaver/show rt (:id epic-step))
                                                      :devflow/epic-receipt)}})
          (let [publication-step (workflow/ready-step "published")
                result (patterns/weave! rt :kanban-batch
                                        {:items [{:key "a" :title "A" :body "draft-42 A"}
                                                 {:key "b" :title "B" :body "draft-42 B"
                                                  :depends-on ["a"]}]})
                a (get-in result [:refs "a"])
                b (get-in result [:refs "b"])
                receipt {:draft "draft-42" :refs (:refs result)
                         :edges [[b a]]}]
            (is (= "Publish or recover kanban feature cards and dependencies for published"
                   (:title publication-step)))
            (weaver/update! rt (:id publication-step)
                            {:attributes {"devflow/card-publication" receipt}})
            (is (= (:id publication-step) (:id (workflow/ready-step "published"))))
            (is (= (assoc receipt :refs {:a a :b b})
                   (attr-get (weaver/show rt (:id publication-step))
                                    :devflow/card-publication)))
            (weaver/update! rt epic-id {:edges [{:type "parent-of" :to a}
                                              {:type "parent-of" :to b}]})
            (is (= #{[b a "depends-on"]}
                   (set (map (juxt :from_strand_id :to_strand_id :edge_type)
                             (:edges (graph/subgraph rt [b] {:type "depends-on"}))))))
            (workflow/complete! "published" {:attributes {"devflow/card-publication" receipt}})
            (let [review-step (workflow/ready-step "published")
                  refs (mapv #(select-keys (weaver/show rt %) [:id :title]) [a b])]
              (is (= "Record the exact kanban review set for published" (:title review-step)))
              (workflow/complete! "published" {:attributes {"devflow/review-set" refs}})
              (is (= "handoff-card-review" (:checkpoint (workflow/ready-step "published"))))
              (is (thrown? clojure.lang.ExceptionInfo
                           (workflow/choose! "published" :review {:cards []})))
              (workflow/choose! "published" :review
                                {:cards (attr-get (weaver/show rt (:id review-step))
                                                  :devflow/review-set)})
              (is (= #{a b}
                     (set (map #(attr-get (weaver/show rt (:id %)) :devflow/card)
                               (workflow/ready-gates "published")))))
              (is (not (contains? (set (map :id refs)) epic-id))))))))))

(defn -main [& _]
  (let [summary (clojure.test/run-tests 'millhouse.devflow-kanban-adapter-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
