(ns millhouse.auto-review-test
  "Disposable admission, adapter and workflow tests; no remote writes or paid agents."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.harnesses :as harnesses]
            [millhouse.harnesses.executors.agent :as agent]
            [millhouse.auto-review :as review]
            [millhouse.auto-review.glab :as glab]
            [millhouse.auto-review.internal.process :as process]
            [millhouse.auto-review.workspace :as workspace]
            [millhouse.auto-review.workflow :as review-workflow]
            [millhouse.auto-run :as auto-run]
            [millhouse.cron :as cron]
            [millhouse.workflow :as workflow]
            [millhouse.test-support :as support]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as t]))

(def ^:private head (str/join (repeat 40 "a")))
(def ^:private base (str/join (repeat 40 "b")))

(defn- revision [id & [overrides]]
  (merge {:provider "fake-forge" :repository "urn:repo:one" :request id
          :url (str "https://forge.invalid/reviews/" id) :title "Untrusted title"
          :head head :base base :requested? false :ci {:status "passed" :head head}}
         overrides))

(def ^:private fixture
  "(ns auto-review.fixture
     (:require [millhouse.harnesses :as harnesses]
               [millhouse.harnesses.assignment :as assignment]
               [millhouse.harnesses.executors.agent :as agent]
               [millhouse.auto-review :as review]
               [millhouse.auto-review.workflow :as reviews]
               [millhouse.workflow :as workflow]
               [millstrand.api.lifecycle.alpha :as lifecycle]
               [millstrand.api.spool.alpha :refer [attr-get]]))
   (lifecycle/use-resource! harnesses/harness-core-runtime assignment/assignment-runtime)
   (workflow/use-executor! agent/agent-stalled?)
   (workflow/use-workflow! reviews/review-request)
   (defn poll [_ {:keys [config]}] (:revisions config))
   (defn prepare! [_ {:keys [card]}]
     {:cwd (attr-get card :fixture/cwd) :branch (str \"review/\" (:id card))})
   (defn start-params [rt params]
     (assoc (review/start-params rt params) :reviewer \"fake\"))")

(defn- root []
  (-> (t/spool-checkout-root "millhouse/auto_review.clj") .getParentFile .getParentFile))

(defn- deps-edn []
  (pr-str {:deps (update-vals (dissoc (:deps (edn/read-string (slurp (io/file (root) "deps.edn"))))
                                      'io.millstrand/millstrand)
                              #(if-let [path (:local/root %)]
                                 (assoc % :local/root (.getCanonicalPath (io/file (root) path)))
                                 %))}))

(defn- with-world [f & [source]]
  (t/with-weaver-world
    [ctx {:storage :sqlite-file :deps-edn (deps-edn)
          :init-clj
          "(require '[millstrand.api.current.alpha :as current]
                    '[millstrand.api.runtime.alpha :as runtime])
           (runtime/module! (current/runtime) :identity
             {:ns 'millhouse.identity :required? true})
           (runtime/module! (current/runtime) :workflow
             {:ns 'millhouse.workflow :required? true})
           (runtime/module! (current/runtime) :cron
             {:ns 'millhouse.cron :required? true})
           (runtime/module! (current/runtime) :fixture
             {:file \"fixture.clj\" :after [:identity :workflow] :required? true})"
          :files {"fixture.clj" (or source fixture)}}]
    (f (:runtime ctx) {:repo (:config-dir ctx) :poll 'auto-review.fixture/poll
                       :provider-config {:revisions []} :max-open 1
                       :workflow "review-request" :seat "fake" :effort "low"})))

(defn- poll! [rt config revisions]
  (review/poll! rt (assoc-in config [:provider-config :revisions] revisions)))

(deftest durable-provider-neutral-admission-and-capacity
  (with-world
    (fn [rt config]
      (let [ordinary (revision "2")
            requested (revision "3" {:requested? true})
            waiting (revision "1" {:ci {:status "pending" :head head}})
            stale-ci (revision "0" {:ci {:status "passed" :head base}})
            input [ordinary waiting requested stale-ci (revision "4") ordinary]
            result (poll! rt config input)
            cards (mapv #(weaver/show rt %) (:admitted result))]
        (is (= ["3" "2"] (mapv #(-> % review/request :request) cards)))
        (is (= ["p1" "p3"] (mapv #(attr-get % :kanban/priority) cards)))
        (doseq [card cards]
          (is (= "feature" (attr-get card :kanban/type)))
          (is (= "pending" (attr-get card :kanban/lane)))
          (is (= "true" (attr-get card :kanban.label/auto-run)))
          (is (= "review-request" (attr-get card :auto-run/workflow)))
          (is (= "fake" (attr-get card :auto-run/seat)))
          (is (nil? (attr-get card :auto-run/status))))
        (is (empty? (:admitted (poll! rt config input))))
        (testing "runtime state loss cannot forget durable dedup or capacity"
          (swap! (:spool-state rt) dissoc :millhouse.auto-review/admission)
          (weaver/update! rt (:id (second cards)) {:attributes {:auto-run/status "error"}})
          (is (empty? (:admitted (poll! rt config [(revision "4")])))))
        (testing "requested inbox priority never consumes the ordinary pool"
          (is (= 1 (count (:admitted (poll! rt config [(revision "5" {:requested? true})]))))))
        (testing "close releases only capacity, not the dedup tombstone"
          (weaver/update! rt (:id (second cards)) {:state "closed"})
          (is (empty? (:admitted (poll! rt config [ordinary]))))
          (is (= 1 (count (:admitted (poll! rt config [(revision "4")]))))))
        (testing "another provider's same request/head remains a distinct identity"
          (is (= 1 (count (:admitted
                           (poll! rt config [(assoc requested :provider "future-github")]))))))
        (testing "provider observations never rewrite a frozen snapshot"
          (poll! rt config [(assoc requested :title "Changed" :base head :requested? false)])
          (is (= requested (review/request (weaver/show rt (:id (first cards)))))))))))

(deftest boundary-failures-and-lost-create-response
  (with-world
    (fn [rt config]
      (testing "validate the complete provider batch before publishing any card"
        (is (thrown? Exception (poll! rt config [(revision "1")
                                                 (assoc (revision "2") :project_id 99)])))
        (is (empty? (weaver/list rt [:= [:attr "kanban/card"] "true"] {}))))
      (testing "a committed add with a lost response is not duplicated"
        (let [add! weaver/add!
              target-rt rt]
          (with-redefs [weaver/add! (fn [rt request & [options]]
                                      (let [result (add! rt request options)]
                                        (if (identical? target-rt rt)
                                          (throw (ex-info "Lost response" {}))
                                          result)))]
            (is (thrown-with-msg? Exception #"Lost response"
                                  (poll! rt config [(revision "1")])))))
        (is (empty? (:admitted (poll! rt config [(revision "1")]))))
        (is (= 1 (count (weaver/list rt [:= [:attr "kanban/card"] "true"] {})))))
      (testing "concurrent polls have one durable admission"
        (let [input [(revision "concurrent" {:requested? true})]
              go (promise)
              calls (mapv (fn [_] (future @go (poll! rt config input))) (range 4))]
          (deliver go true)
          (is (= 1 (count (mapcat :admitted (mapv deref calls))))))))))

(defn- mr [id & [overrides]]
  (merge {:project_id 7 :iid id :state "opened" :draft false :sha head
          :web_url (str "https://git.invalid/mr/" id) :title "Request" :labels ["review"]
          :reviewers [{:id 9}] :diff_refs {:head_sha head :base_sha base :start_sha base}
          :head_pipeline {:id 11 :status "success" :sha head}}
         overrides))

(deftest glab-is-paginated-exact-head-and-read-only
  (let [calls (atom [])
        details {1 (mr 1) 2 (mr 2 {:head_pipeline {:id 12 :status "success" :sha base}})
                 3 (mr 3 {:draft true}) 4 (mr 4 {:labels []})
                 5 (mr 5 {:sha base}) 6 (mr 6 {:head_pipeline nil
                                               :pipeline {:id 10 :status "success" :sha head}})
                 7 (mr 7 {:state "merged"}) 8 (mr 8 {:reviewers []})}
        request {:repo "/tmp" :config {:host "git.invalid" :project 7 :labels ["review"]}}
        command (fn [_ argv _ _]
                  (swap! calls conj argv)
                  (let [endpoint (last argv)]
                    (json/write-str
                     (cond
                       (= endpoint "user") {:id 9}
                       (str/ends-with? endpoint "&page=1") (vec (repeat 100 (mr 1)))
                       (str/ends-with? endpoint "&page=2") (mapv mr (range 2 9))
                       :else (details (parse-long (last (str/split endpoint #"/"))))))))]
    (with-redefs [process/command! command]
      (let [result (glab/poll nil request)
            by-id (into {} (map (juxt :request identity)) result)]
        (is (= #{"1" "2" "6" "8"} (set (keys by-id))))
        (is (:requested? (by-id "1")))
        (is (false? (:requested? (by-id "8"))))
        (is (= {:status "unknown"} (:ci (by-id "6"))))
        (is (= base (get-in by-id ["2" :ci :head])))
        (is (= "https://git.invalid/projects/7" (:repository (by-id "1"))))
        (is (every? #(= #{:provider :repository :request :url :title :head :base :requested? :ci}
                        (set (keys %))) result))))
    (is (some #(str/includes? (last %) "page=2") @calls))
    (is (every? #(= ["glab" "api" "--hostname" "git.invalid" "--method" "GET"]
                    (subvec % 0 6)) @calls))
    (with-redefs [process/command! (fn [& _] "{\"id\":null}")]
      (is (thrown? Exception (glab/poll nil request))))))

(defn- git-fixture [f]
  (let [dir (support/temp-dir "auto-review-git-")
        repo (io/file dir "repo")
        checkout (io/file dir "checkout")]
    (try
      (.mkdirs repo)
      (support/run-git! repo "init" "-q")
      (support/run-git! repo "config" "user.name" "Fixture")
      (support/run-git! repo "config" "user.email" "fixture@example.invalid")
      (spit (io/file repo "source.txt") "base\n")
      (support/run-git! repo "add" ".")
      (support/run-git! repo "commit" "-qm" "Base")
      (let [base (str/trim (support/run-git! repo "rev-parse" "HEAD"))]
        (spit (io/file repo "source.txt") "head\n")
        (support/run-git! repo "commit" "-qam" "Head")
        (support/run-git! dir "clone" "-q" (.getPath repo) (.getPath checkout))
        (f (.getCanonicalPath repo) (.getCanonicalPath checkout)
           (revision "git" {:head (str/trim (support/run-git! repo "rev-parse" "HEAD"))
                            :base base})))
      (finally (support/delete-tree! dir)))))

(deftest workspace-validation-and-preparation-receipts
  (git-fixture
   (fn [repo cwd revision]
     (is (= cwd (:cwd (workspace/inspect! repo revision cwd))))
     (is (thrown? Exception (workspace/inspect! repo revision repo)))
     (is (thrown? Exception (workspace/inspect! repo (assoc revision :head base) cwd)))
     (spit (io/file cwd "source.txt") "dirty\n")
     (is (thrown-with-msg? Exception #"tracked changes" (workspace/inspect! repo revision cwd)))
     (support/run-git! cwd "restore" "source.txt")
     (with-world
       (fn [rt config]
         (let [revision (assoc revision :ci {:status "passed" :head (:head revision)})
               card (weaver/show rt (first (:admitted (poll! rt (assoc config :repo repo) [revision]))))
               calls (atom [])
               command process/command!]
           (with-redefs [process/command!
                         (fn [dir argv cap timeout]
                           (swap! calls conj argv)
                           (case (first argv)
                             "wktree" (json/write-str {:kind "ready" :branch (str "review/" (:id card))
                                                       :worktree_path cwd :post_create_script_path "/fixture/bootstrap"})
                             "bash" (throw (ex-info "Bootstrap failed" {}))
                             (if (= ["git" "fetch"] (subvec argv 0 2))
                               "" ; The fixture's comparison objects already exist locally.
                               (command dir argv cap timeout))))]
             (is (thrown-with-msg? Exception #"Bootstrap failed"
                                   (workspace/prepare! rt {:repo repo :card card}))))
           (is (= cwd (:worktree_path (attr-get (weaver/show rt (:id card)) :auto-review/workspace))))
           (is (some #(= ["git" "branch" (str "review/" (:id card)) (:head revision)] %) @calls))
           (is (some #(= ["wktree" "--cwd" repo "add" "--branch" (str "review/" (:id card)) "--json"] %) @calls))))))))

(deftest polling-composes-with-real-auto-run-and-workflow-agent-gates
  (git-fixture
   (fn [repo cwd revision]
     (with-world
       (fn [rt config]
         (let [revision (assoc revision :ci {:status "passed" :head (:head revision)})
               config (assoc config :repo repo)
               result (poll! rt config [revision (assoc revision :request "requested" :requested? true)])
               cards (mapv #(weaver/show rt %) (:admitted result))]
           (doseq [card cards]
             (weaver/update! rt (:id card) {:attributes {:fixture/cwd cwd}}))
           (harnesses/register-harness! rt :fake
                                        {:modes #{:headless} :prepare 'millhouse.harnesses/create!
                                         :finish 'millhouse.harnesses/finish!})
           (auto-run/configure! rt {:repo repo :seat "fake" :effort "high"
                                    :workflow "review-request" :workflows #{"review-request"}
                                    :prepare 'auto-review.fixture/prepare!
                                    :start-params 'auto-review.fixture/start-params
                                    :enabled? true :max-running 1 :interval-ms 3600000})
           (let [{:keys [card run workflow-run-id]} (first (:dispatched (auto-run/scan! rt)))]
             (is (= (:id (first cards)) card) "Requested card wins Auto-run priority")
             (is (empty? (:dispatched (auto-run/scan! rt))) "No requested execution bypass")
             (current/with-runtime rt
               (let [context (attr-get (workflow/current-root workflow-run-id) :workflow/context)
                     inspect (first (workflow/ready workflow-run-id))
                     gate (weaver/show rt (:id inspect))]
                 (is (= (assoc revision :request "requested" :requested? true) (:review context)))
                 (is (= cwd (:worktree context)))
                 (is (= cwd (:cwd (review-workflow/inspect-workspace! (attr-get gate :code/params)))))
                 ;; Trusted disposable executor completion after running the actual check.
                 (workflow/run-complete! {:run-id workflow-run-id :step (:id inspect) :executor "code"})
                 (agent/scan!)
                 (let [review-gate (first (workflow/ready workflow-run-id))
                       reviewer (first (weaver/list rt [:edge/out "serves" [:= :id (:id review-gate)]] {}))]
                   (is (some? reviewer))
                   (is (= cwd (attr-get reviewer :harness/cwd)))
                   (is (str/includes? (attr-get reviewer :harness/prompt) (:head revision)))
                   (is (str/includes? (attr-get reviewer :harness/prompt) "Do not publish remote"))
                   (agent/scan!)
                   (is (= "Review the frozen request" (:title (first (workflow/ready workflow-run-id)))))
                   (harnesses/finish! rt (:id reviewer)
                                      {:status :done :exit-code 0 :result "Fake review evidence: no findings"})
                   (agent/scan!)
                   (is (= "Record findings for local decision" (:title (first (workflow/ready workflow-run-id)))))
                   (is (= (:id reviewer) (attr-get (weaver/show rt (:id review-gate)) :workflow/executor-run-id)))
                   (workflow/complete! workflow-run-id {:by-identity "fixture-driver"})
                   (is (= "human" (:checkpoint-kind (first (workflow/ready workflow-run-id)))))
                   (is (.isDirectory (io/file cwd)) "Human wait retains the workspace")
                   (is (= "active" (:state (weaver/show rt card))))
                   (is (empty? (:admitted (poll! rt config [revision])))))))
             (harnesses/finish! rt run {:status :done :exit-code 0 :result "Waiting for human decision"})
             (is (= 1 (count (:dispatched (auto-run/scan! rt)))) "Execution slot released independently")
             (auto-run/stop! rt))))))))

(defn cron-poll [rt]
  (review/poll! rt {:repo "/tmp" :poll 'auto-review.fixture/poll
                    :provider-config {:revisions [(revision "cron")]}
                    :max-open 1 :workflow "review-request"}))

(deftest cron-owns-the-only-polling-wake-and-repeated-observation
  (with-world
    (fn [rt _]
      (cron/register! rt {:id :review-fixture :interval-ms 3600000
                          :handler 'millhouse.auto-review-test/cron-poll})
      (try
        (doseq [expected [1 0]]
          (cron/fire-wake {:runtime rt :payload {:job "review-fixture"}})
          (cron/await-quiescent! rt {:timeout-ms (support/await-budget-ms)})
          (is (= expected (count (get-in (first (cron/jobs rt)) [:last-result :admitted])))))
        (is (= ["cron/review-fixture"] (mapv :key (scheduler/pending rt))))
        (is (= 1 (count (weaver/list rt [:= [:attr "kanban/card"] "true"] {}))))
        (is (empty? (weaver/list rt [:= [:attr "harness/run"] "true"] {})))
        (finally (cron/unregister! rt :review-fixture))))))

(deftest consumer-example-activates-disabled-with-existing-workflow-infrastructure
  (with-world
    (fn [rt config]
      (harnesses/register-harness! rt :review-driver
                                   {:modes #{:headless} :prepare 'millhouse.harnesses/create!
                                    :finish 'millhouse.harnesses/finish!})
      (spit (io/file (:repo config) "review.clj")
            (str/replace (slurp (io/file (root) "spools/auto-review/examples/review.clj"))
                         "/absolute/canonical/repo" (:repo config)))
      (let [result (support/with-module-activation
                     #(runtime/module! rt :example
                                       {:file "review.clj" :after [:fixture :cron] :required? true}))]
        (is (= (:repo config) (get-in (auto-run/status rt) [:config :repo])) (pr-str result))
        (is (false? (:enabled (auto-run/status rt))))
        (is (empty? (cron/jobs rt)))
        (is (empty? (weaver/list rt [:= [:attr "harness/run"] "true"] {})))
        (current/with-runtime rt
          (is (contains? (:entrypoints (workflow/resolve-workflow :review-request)) :start)))))
    (str/replace fixture "(workflow/use-workflow! reviews/review-request)" "")))

(deftest standalone-deps-root-resolves-without-repository-classpath
  (let [dir (support/temp-dir "auto-review-consumer-")]
    (try
      (spit (io/file dir "deps.edn")
            (pr-str {:deps {'millhouse/auto-review
                            {:local/root (.getCanonicalPath (io/file (root) "spools/auto-review"))}}}))
      (let [result (sh/sh "clojure" "-Srepro" "-M" "-e"
                          "(require 'millhouse.auto-review.glab 'millhouse.auto-review.workspace 'millhouse.auto-review.workflow) (println :resolved)"
                          :dir (.getPath dir))]
        (is (zero? (:exit result)) (:err result))
        (is (str/includes? (:out result) ":resolved")))
      (finally (support/delete-tree! dir)))))
