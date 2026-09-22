(ns millhouse.spools.auto-run-explain
  "Collect and classify read-only evidence for one auto-run delivery."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [millhouse.spools.auto-run-reporting :as reporting]
            [millhouse.spools.kanban :as kanban]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.math BigInteger]
           [java.security MessageDigest]))

(def ^:private schema-version "codethread.auto-run.explain/v1")

(def ^:private phase-words
  [["human" "human-checkpoint"] ["accept" "human-checkpoint"]
   ["review" "review"] ["valid" "validation"] ["quality" "validation"]
   ["check" "validation"] ["merge" "merge"] ["land" "merge"]
   ["cleanup" "cleanup"] ["bookkeep" "bookkeeping"]
   ["handoff" "handoff"] ["publish" "publication"]])

(defn- phase-for [frontier]
  (let [text (str/lower-case
              (str (:title frontier) " " (:role frontier) " " (:gate frontier)
                   " " (:checkpoint-kind frontier)))]
    (or (some (fn [[word phase]] (when (str/includes? text word) phase)) phase-words)
        (when (= "checkpoint" (:role frontier)) "human-checkpoint")
        "implementation")))

(defn- blocking-relations [rt card-id]
  (let [edges (graph/outgoing-edges rt [card-id] "depends-on")
        dependencies (into {} (map (juxt :id identity))
                           (graph/strands-by-ids rt (mapv :to_strand_id edges)))]
    (->> edges
         (keep (fn [{:keys [to_strand_id]}]
                 (let [strand (dependencies to_strand_id)]
                   (when (= "active" (:state strand))
                     (select-keys strand [:id :title :state])))))
         vec)))

(defn- run-record [run accepted-head?]
  (let [get-attr #(attr-get run %)]
    (cond-> {:id (:id run)
             :state (:state run)
             :published (= "true" (get-attr :harness/published))
             :accepted-head accepted-head?
             :status (get-attr :harness/status)
             :settled (= "true" (get-attr :harness/settled))
             :target (get-attr :harness/target)
             :role (get-attr :harness/role)
             :request-id (get-attr :harness/request-id)
             :logical-id (get-attr :harness/logical-id)
             :alias (get-attr :harness/alias)
             :harness (get-attr :harness/harness)
             :model (get-attr :harness/model)
             :effort (get-attr :harness/effort)
             :identity (get-attr :identity/id)
             :attempt (get-attr :harness/attempt)
             :invocation (get-attr :harness/invocation)
             :resumes (get-attr :harness/resumes)
             :continues (get-attr :harness/after)
             :created-at (:created_at run)
             :updated-at (:updated_at run)}
      (get-attr :harness/settlement)
      (assoc :settlement (get-attr :harness/settlement))
      (get-attr :harness/error) (assoc :error (get-attr :harness/error))
      (get-attr :harness/result) (assoc :result (get-attr :harness/result)))))

(defn- relevant-runs [rt card]
  (let [all-runs (weaver/list rt [:= [:attr "harness/run"] "true"] {})
        request-id (attr-get card :auto-run/request-id)
        seed? #(or (= (:id %) (attr-get card :auto-run/run-id))
                   (= (:id card) (attr-get % :harness/target))
                   (and request-id (= request-id (attr-get % :harness/request-id))))
        children (fn [id]
                   (concat (map :from_strand_id (graph/incoming-edges rt [id] "resumes"))
                           (map :from_strand_id (graph/incoming-edges rt [id] "continues"))))
        parents (fn [run] (remove nil? [(attr-get run :harness/resumes)
                                        (attr-get run :harness/after)]))
        by-id (into {} (map (juxt :id identity)) all-runs)
        seeds (set (map :id (filter seed? all-runs)))
        ids (loop [known seeds]
              (let [adjacent (set (mapcat (fn [id]
                                            (concat (children id)
                                                    (some-> (by-id id) parents)))
                                          known))
                    expanded (into known adjacent)]
                (if (= known expanded) known (recur expanded))))
        runs (keep by-id ids)
        published-ids (set (map :id (filter #(= "true" (attr-get % :harness/published)) runs)))
        continued (set (mapcat (fn [id] (filter published-ids (children id))) published-ids))
        predecessors (set (mapcat (fn [run]
                                    (when (published-ids (:id run)) (parents run)))
                                  runs))
        heads (set (remove predecessors published-ids))]
    {:original-receipt {:request-id request-id
                        :run-id (attr-get card :auto-run/run-id)}
     :lineages (->> runs (sort-by (juxt :created_at :id))
                    (mapv #(run-record % (contains? heads (:id %)))))
     :accepted-heads (vec (sort heads))
     :unpublished (vec (sort (remove published-ids ids)))
     :published-continuations (vec (sort continued))
     :ambiguous (> (count heads) 1)}))

(defn- workflow-evidence [rt run-id]
  (if-not run-id
    {:availability "absent" :reason "No workflow receipt is recorded."}
    (current/with-runtime rt
      (try
        (let [ready (workflow/ready run-id)
              history (workflow/run-history run-id)
              done (workflow/done? run-id)]
          {:availability "present"
           :run-id run-id
           :done done
           :frontier (mapv #(assoc % :phase (phase-for %)) ready)
           :history history})
        (catch Exception error
          {:availability "unknown" :run-id run-id
           :reason (ex-message error)})))))

(defn- land-evidence [rt adapter context]
  (if-not adapter
    {:availability "unsupported"
     :reason "No Land evidence adapter is configured."}
    (try
      (if-let [evidence ((runtime/resolve-var rt adapter) rt context)]
        {:availability "present" :refs evidence}
        {:availability "absent"
         :reason "The configured adapter successfully found no linked Land evidence."})
      (catch Exception error
        {:availability "unknown" :reason (ex-message error)}))))

(defn- sha256 [^String text]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                          (.getBytes text "UTF-8")))))

(defn- disk-evidence [repo]
  (let [file (io/file repo ".millstrand" "deps.edn")]
    (if (.isFile file)
      (let [text (slurp file)]
        {:availability "present" :path (.getCanonicalPath file)
         :sha256 (sha256 text) :declarations text})
      {:availability "absent" :reason "No readable .millstrand/deps.edn was found."})))

(defn- recorded-refs [card]
  (->> (:attributes card)
       (keep (fn [[key value]]
               (let [name (name key)]
                 (when (and (some? value)
                            (re-find #"(?i)(pr|head|review|check|resource|merge)" name))
                   {:attribute (str key) :value value :verification "recorded"}))))
       (sort-by :attribute)
       vec))

(defn- merge-boundary [card land]
  (let [recorded (recorded-refs card)
        post? (some #(re-find #"(?i)(merged|post-merge)" (str (:value %))) recorded)
        pre? (some #(re-find #"(?i)(pre-merge|open)" (str (:value %))) recorded)]
    (cond
      post? {:value "post-merge" :basis "recorded"}
      pre? {:value "pre-merge" :basis "recorded"}
      :else {:value "unknown"
             :reason (str "No positive merge-boundary evidence; Land is "
                          (:availability land) ".")})))

(defn classify
  "Classify collected delivery evidence without proposing a mutation."
  [{:keys [card admission agents workflow land agent-blocker] :as collected}]
  (let [frontier (:frontier workflow)
        active-head? (some #(and (:accepted-head %)
                                 (contains? #{"ready" "running"} (:status %)))
                           (:lineages agents))
        failures (vec (for [run (:lineages agents)
                            :when (and (:accepted-head run) (= "failed" (:status run)))]
                        {:kind "harness-run" :run (:id run)
                         :attempt (:attempt run) :error (:error run)}))
        failed? (seq failures)
        blocked? (:blocked agent-blocker)
        human? (some #(= "human-checkpoint" (:phase %)) frontier)
        closed? (= "closed" (:state card))
        complete? (and closed? (:done workflow)
                       (= "post-merge" (get-in collected [:evidence :merge-boundary :value])))
        disposition (cond
                      complete? "completed"
                      failed? "failed"
                      active-head? "active"
                      (or human? (seq frontier) (seq (:blockers admission)) blocked?) "waiting"
                      :else "unknown")
        phases (vec (distinct (map :phase frontier)))
        phase (cond complete? "bookkeeping" (seq phases) (first phases)
                    (empty? (:accepted-heads agents)) "publication" :else "implementation")
        next-role (cond blocked? "operator"
                        human? "human" failed? "operator"
                        (or (seq (:blockers admission)) active-head?) "worker"
                        (= "unknown" (:availability workflow)) "operator"
                        :else "unknown")]
    {:agent-blocker agent-blocker
     :cause {:evidence failures
             :evidence-status (if failed? "present" "unknown")}
     :disposition disposition
     :phase phase
     :frontier (mapv #(select-keys % [:id :title :phase :role :gate
                                      :checkpoint :checkpoint-kind]) frontier)
     :reason (cond complete? "Closed card, finished workflow, and positive post-merge evidence."
                   failed? "The current accepted Harnesses run records a failure."
                   blocked? "The agent reported a blocker; inspect its evidence strand."
                   human? "The delivery workflow is waiting at a human checkpoint."
                   active-head? "An accepted published lineage head is active."
                   :else "Available evidence does not establish a current delivery state.")
     :next {:role next-role
            :action (if blocked?
                      "Inspect the referenced agent evidence; continuing work requires authorization."
                      (case next-role
                        "human" "Inspect the recorded review package and choose the workflow checkpoint."
                        "operator" "Inspect the named failed or unavailable evidence; no retry is authorised."
                        "worker" "Wait for or inspect the current accepted worker and workflow frontier."
                        "No responsible role can be established from recorded evidence."))
            :unavailable-evidence
            (cond-> []
              (not= "present" (:availability land))
              (conj {:kind "land" :availability (:availability land)
                     :reason (:reason land)})
              (= "unknown" (:availability workflow))
              (conj {:kind "workflow" :availability "unknown"
                     :reason (:reason workflow)
                     :command (str "strand workflow ready " (:run-id workflow))}))}}))

(defn explain
  "Return one bounded, read-only delivery explanation for feature `card-id`."
  [rt config card-id]
  (when-not (and (string? card-id) (not (str/blank? card-id)))
    (fail! "auto-run explain requires a non-blank card id" {:card card-id}))
  (let [card (or (weaver/show rt card-id)
                 (fail! "Auto-run explanation card not found" {:card card-id}))]
    (when-not (and (= "true" (attr-get card :kanban/card))
                   (= "feature" (attr-get card :kanban/type)))
      (fail! "Auto-run explanation requires a feature card" {:card card-id}))
    (let [agent-blocker (reporting/read-blocker rt card)
          observed-at (str (runtime/now rt))
          runs (relevant-runs rt card)
          workflow (workflow-evidence rt (attr-get card :auto-run/workflow-run-id))
          blockers (blocking-relations rt card-id)
          all-runs (weaver/list rt [:= [:attr "harness/run"] "true"] {})
          occupied (filter #(or (contains? #{"ready" "running"}
                                           (attr-get % :harness/status))
                                (not= "true" (attr-get % :harness/settled))) all-runs)
          assigned (weaver/list rt [:= [:attr "auto-run/status"] "assigned"] {})
          active-ids (set (map :id occupied))
          busy (count (filter #(contains? active-ids (attr-get % :auto-run/run-id)) assigned))
          admission {:opted-in (= "true" (attr-get card :kanban.label/auto-run))
                     :current-owner (kanban/current-ownership rt card-id)
                     :blockers blockers
                     :configured (select-keys config [:seat :effort :workflow :max-running])
                     :effective {:seat (attr-get card :auto-run/effective-seat)
                                 :effort (attr-get card :auto-run/effective-effort)
                                 :workflow (attr-get card :auto-run/effective-workflow)}
                     :receipt {:status (attr-get card :auto-run/status)
                               :request-id (attr-get card :auto-run/request-id)
                               :run-id (attr-get card :auto-run/run-id)
                               :workflow-run-id (attr-get card :auto-run/workflow-run-id)
                               :error (attr-get card :auto-run/error)}
                     :capacity {:basis "unsettled dispatcher assignment receipts"
                                :busy busy :maximum (:max-running config)
                                :available (when (:max-running config)
                                             (max 0 (- (:max-running config) busy)))}}
          land (land-evidence rt (get-in config [:evidence-adapters :land])
                              {:card card :workflow workflow :agents runs})
          evidence {:records (recorded-refs card)
                    :historical-errors (vec (keep #(when-let [error (:error %)]
                                                     {:run (:id %) :error error
                                                      :scope "harness-run"})
                                                  (:lineages runs)))
                    :observed-at observed-at
                    :merge-boundary (merge-boundary card land)}
          collected {:schema-version schema-version
                     :workspace (:repo config)
                     :card (select-keys card [:id :title :state :created_at :updated_at])
                     :observed-at observed-at
                     :agent-blocker agent-blocker
                     :admission admission :agents runs :workflow workflow :land land
                     :evidence evidence
                     :runtime {:loaded (runtime/status rt)
                               :disk (disk-evidence (:repo config))
                               :live-dependency-comparison
                               {:availability "unknown"
                                :reason "Loaded status and direct declarations do not prove a resolved transitive basis or BOM compatibility."}
                               :external-inspection
                               (str "mill weaver status --workspace " (:repo config)
                                    "/.millstrand --json")}}]
      (merge collected (classify collected)))))
