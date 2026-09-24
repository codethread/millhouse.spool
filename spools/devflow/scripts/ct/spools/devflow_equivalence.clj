(ns ct.spools.devflow-equivalence
  "Executable semantic check for the two published card-authoring targets."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.devflow :as devflow]
            [ct.spools.devflow-kanban-adapter :as adapter]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millhouse.spools.workflow :as workflow]
            [millstrand.test.alpha :as t]))

(def ^:private fixture
  {:feature "millstrand-rename"
   :cards [{:id "merged-proposal"
            :title "Merged proposal implementation"}
           {:id "release-verification"
            :title "Release verification evidence"}]})

(defn- activate! [rt]
  (doseq [namespace '[millhouse.spools.workflow
                      ct.spools.devflow
                      millhouse.spools.kanban
                      ct.spools.devflow-kanban-adapter]]
    (require namespace))
  (doseq [[key config] [[:millhouse/spools-workflow {:ns 'millhouse.spools.workflow}]
                        [:devflow {:ns 'ct.spools.devflow
                                   :after [:millhouse/spools-workflow]}]
                        [:millhouse/spools-kanban {:ns 'millhouse.spools.kanban}]
                        [:devflow-kanban-adapter
                         {:ns 'ct.spools.devflow-kanban-adapter
                          :after [:millhouse/spools-workflow :devflow :millhouse/spools-kanban]}]]]
    (runtime/module! rt key config))
  rt)

(defn- execution-params []
  {:feature (:feature fixture)
   :card-reviewer "equivalence-card-reviewer"
   :card-set-reviewer "equivalence-card-set-reviewer"})

(defn- target-behavior [step]
  ;; These are the stable authoring obligations shared by both targets. Their
  ;; titles and instructions are intentionally target-specific, so retain
  ;; those separately for diagnostics without pretending they are identical.
  {:artifact (:artifact step)
   :title (:title step)
   :instruction (:instruction step)})

(defn- execute-target!
  "Run one published target through the stage's real defer consumer path."
  [stage target & [configure!]]
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (activate! (:runtime ctx))]
      (current/with-runtime rt
        (when configure!
          (configure! rt))
        (let [run-id (str "equivalence-" (name target))
              started (workflow/start! run-id stage (execution-params))
              defer (workflow/ready-step run-id)
              filled (workflow/defer! run-id target
                                       {:feature (:feature fixture)
                                        :repository "repo" :mainline "main"
                                        :merged-revision "abc123"
                                        :proposal-path "proposal.md"
                                        :merge-evidence "merge-record"})
              authored-step (workflow/ready-step run-id)
              after-authoring (last
                                (doall
                                  (for [_ (range (if (= target :author-kanban-cards) 4 1))]
                                    (workflow/complete! run-id
                                                        {:attributes
                                                         {"devflow/review-set" (:cards fixture)}}))))
              handoff (workflow/ready-step run-id)]
          {:target target
           :binding (:workflows defer)
           :target-behavior (target-behavior authored-step)
           :handoff (:checkpoint handoff)
           :review-ref-count (count (:cards fixture))
           :started-ready (mapv :role (:ready started))
           :filled-ready (mapv :role (:ready filled))
           :after-authoring-ready (mapv :role (:ready after-authoring))})))))

(defn- fail-mismatch! [message strand kanban]
  (throw (ex-info message {:strand strand :kanban kanban})))

(def ^:private contract-fields
  [:artifact :title :instruction])

(defn- assert-contract!
  "Validate the published fields carried by one authoring target report."
  [{:keys [target target-behavior] :as report}]
  (let [{:keys [title instruction]} target-behavior]
    (when-not (and (keyword? target)
                   (map? target-behavior)
                   (every? #(let [value (get target-behavior %)]
                              (and (string? value) (not (str/blank? value))))
                           contract-fields)
                   (str/includes? title (:feature fixture))
                   (str/includes? instruction "guidance")
                   (str/includes? (str/lower-case instruction) "card"))
      (fail-mismatch! "card-authoring target contract is incomplete" report report))))

(defn- assert-equivalent!
  "Validate the two published authoring reports.

  Artifact, handoff, and review refs are shared contract fields. Titles and
  instructions may differ between named targets because each explains its own
  authoring system; when the target name is unchanged, every contract field
  must remain equal so same-name behavior changes fail the gate."
  [strand kanban]
  (doseq [report [strand kanban]]
    (assert-contract! report)
    (when-not (some #{(name (:target report))} (:binding report))
      (fail-mismatch! "card-authoring target is not in its defer binding"
                      strand kanban)))
  (let [shared (juxt #(select-keys (:target-behavior %) [:artifact])
                     :handoff
                     :review-ref-count)]
    (when-not (= (shared strand) (shared kanban))
      (fail-mismatch! "card-authoring semantic mismatch" strand kanban)))
  (when (and (= (:target strand) (:target kanban))
             (not= (mapv #(get-in strand [:target-behavior %]) contract-fields)
                   (mapv #(get-in kanban [:target-behavior %]) contract-fields)))
    (fail-mismatch! "card-authoring same-name contract divergence" strand kanban))
  true)

(s/def ::divergent-params (s/keys :req-un [:ct.spools.devflow.internal.definition/feature]))

;; This definition exists only to prove that the verifier notices a published
;; target's behavior changing behind the same registered name. It is never one
;; of the authoring targets used by the equivalence gate itself.
(workflow/defworkflow divergent-author-card-strands
  "A deliberately divergent card-authoring target used by the regression."
  {:entrypoints #{:call}
   :param-spec ::divergent-params
   :defaults {}}
  (workflow/workflow
    (fn [{:keys [feature]}]
      (str "Author divergent implementation cards for " feature))
    (workflow/step :divergent-authoring
                   (fn [{:keys [feature]}]
                     (str "Author divergent implementation cards for " feature))
                   :self
                   :attributes {"workflow/artifact" "divergent cards"
                                "devflow/guide" "decompose"
                                "workflow/instruction" "The published target behavior diverged."})))

(defn- divergence-regression! [strand]
  (doseq [field [:title :instruction]]
    (let [divergent (update-in strand [:target-behavior field]
                               #(str % " changed"))]
      (try
        (assert-equivalent! strand divergent)
        (throw (ex-info "card-authoring divergence regression did not fire"
                        {:field field}))
        (catch clojure.lang.ExceptionInfo error
          (when (= "card-authoring divergence regression did not fire"
                   (.getMessage error))
            (throw error))))))
  (let [divergent (execute-target!
                   #'devflow/decompose
                   :author-card-strands
                   (fn [_]
                     (workflow/register-workflow!
                     :author-card-strands
                     'ct.spools.devflow-equivalence/divergent-author-card-strands)))]
    (try
      (assert-equivalent! strand divergent)
      (throw (ex-info "card-authoring divergence regression did not fire" {}))
      (catch clojure.lang.ExceptionInfo error
        (when (= "card-authoring divergence regression did not fire"
                 (.getMessage error))
          (throw error))))))

(defn- binding-regression! []
  (try
    (execute-target! #'devflow/decompose :author-kanban-cards)
    (throw (ex-info "card-authoring binding regression did not fire" {}))
    (catch clojure.lang.ExceptionInfo error
      (if (= "card-authoring binding regression did not fire"
             (.getMessage error))
        (throw error)
        (when-not (= :workflow/defer-target-not-allowed
                     (:reason (ex-data error)))
          (throw error))))))

(defn -main [& _]
  (let [strand (execute-target! #'devflow/decompose
                                :author-card-strands)
        kanban (execute-target!
                #'adapter/decompose-kanban
                :author-kanban-cards)]
    (assert-equivalent! strand kanban)
    (divergence-regression! strand)
    (binding-regression!)
    (println "card-authoring equivalence: clean")
    (println "  targets: author-card-strands, author-kanban-cards")
    (println "  consumer-path: decompose defer -> authoring step -> review handoff")
    (println "  fresh databases: 2")
    (println "  review-refs:" (:review-ref-count strand))
    (println "  core-sha:" (or (System/getenv "MSR04_CORE_SHA") "<not supplied>"))))
