(ns millhouse.config-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.config.agents :as agents]
            [millhouse.config.consumer-provenance-smoke]
            [millhouse.config.bootstrap :as codethread]
            [millhouse.config.sub-coordinator :as sub-coordinator]
            [millhouse.harnesses :as harnesses]
            [millhouse.harnesses.reviewers :as reviewers]
            [millhouse.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as t]))

(def ^:private project-root (.getCanonicalPath (io/file "../..")))
(def ^:private workspace-root (io/file project-root ".millstrand"))
(def ^:private workspace-deps
  (edn/read-string (slurp (io/file workspace-root "deps.edn"))))
(def ^:private local-deps-edn
  (pr-str
   {:deps
    {'millhouse/config {:local/root (str project-root "/spools/config")}}}))

(defn- checked-in-workspace-deps-edn []
  (-> workspace-deps
      (update :deps
              (fn [deps]
                (into {}
                      (map (fn [[library coordinate]]
                             [library
                              (if-let [path (:local/root coordinate)]
                                {:local/root
                                 (.getCanonicalPath
                                  (io/file workspace-root path))}
                                coordinate)]))
                      deps)))
      pr-str))

(def ^:private workspace-init-clj
  (slurp (io/file workspace-root "init.clj")))
(def ^:private workspace-files
  (into {}
        (for [path ["me/auto_run_workflows.clj" "me/auto_run.clj"]]
          [path (slurp (io/file workspace-root path))])))

(deftest bootstrap-registers-catalog-and-reviewers-without-an-executor
  (t/with-weaver-world [ctx {:storage :sqlite-memory
                             :deps-edn local-deps-edn}]
    (let [rt (:runtime ctx)
          expected (mapv first codethread/module-definitions)
          result (codethread/register! rt)]
      (is (= expected (:registered result)))
      (is (= expected (:registered (codethread/register! rt))))
      (testing "delegated-coordination seats register on demand"
        ;; The shared catalog no longer elects these seats, so exercise the
        ;; additive live-registration seams directly.
        (is (= "sub-coordinator" (:alias (sub-coordinator/register! rt))))
        (is (= "sub-coordinator-sol"
               (:alias (sub-coordinator/register-sol! rt)))))
      (testing "the preferred Pi-backed role seats resolve"
        (is (= {:alias "luna" :harness "pi"}
               (select-keys (harnesses/resolve-harness rt :luna)
                            [:alias :harness])))
        (is (= "openai-codex/gpt-5.6-luna"
               (get-in (harnesses/resolve-harness rt :luna)
                       [:generated :harness/model])))
        (is (= "openai-codex/gpt-6-astra"
               (get-in (harnesses/resolve-harness rt :oracle)
                       [:generated :harness/model]))))
      (testing "the DeepSeek seat is gated on the China flag"
        (let [grunt-model #(get-in (harnesses/resolve-harness rt :grunt)
                                   [:generated :harness/model])]
          (is (true? (harnesses/flag rt agents/allow-china-flag)))
          (is (true? (:available (harnesses/availability rt :deepseek))))
          (is (= "deepseek/deepseek-v4-flash" (grunt-model)))
          (is (= "max"
                 (get-in (harnesses/resolve-harness rt :grunt)
                         [:generated :harness/effort])))
          (harnesses/set-flag! rt agents/allow-china-flag false)
          (is (false? (:available (harnesses/availability rt :deepseek))))
          (is (= "openai-codex/gpt-5.6-luna" (grunt-model)))
          (is (= "xhigh"
                 (get-in (harnesses/resolve-harness rt :grunt)
                         [:generated :harness/effort])))
          (agents/open-shared-catalog! {:runtime rt})
          (is (false? (harnesses/flag rt agents/allow-china-flag)))
          (is (false? (:available (harnesses/availability rt :deepseek))))
          (is (= "openai-codex/gpt-5.6-luna" (grunt-model)))
          (harnesses/set-flag! rt agents/allow-china-flag true)
          (is (= "deepseek/deepseek-v4-flash" (grunt-model)))))
      (testing "shared review lenses prefer grunt and retain its fallback"
        (let [selected-seats
              #(into {}
                     (map (juxt :name :selected-seat)
                          (reviewers/reviewers rt)))
              expected {"docs-and-tests" "grunt"
                        "runtime-correctness" "grunt"
                        "source-form" "grunt"}]
          (is (= expected (selected-seats)))
          (harnesses/set-flag! rt agents/allow-china-flag false)
          (is (= expected (selected-seats)))
          (is (= "openai-codex/gpt-5.6-luna"
                 (get-in (harnesses/resolve-harness rt :grunt)
                         [:generated :harness/model])))
          (is (= "xhigh"
                 (get-in (harnesses/resolve-harness rt :grunt)
                         [:generated :harness/effort])))
          (harnesses/set-flag! rt agents/allow-china-flag true)
          (is (= expected (selected-seats)))))
      (testing "the bounded sub-coordinator carries its Luna-first runbook"
        (let [sol-before (harnesses/resolve-harness rt :sol)
              luna (harnesses/resolve-harness rt :sub-coordinator)
              luna-guidance (get-in luna
                                    [:generated
                                     :harness/appended-system-prompts])
              luna-run (harnesses/create!
                        rt {:harness :sub-coordinator
                            :mode :interactive
                            :cwd "/tmp"
                            :title "Frozen Luna sub-coordinator run"})]
          (is (= "codex" (:harness luna)))
          (is (= "gpt-5.6-luna"
                 (get-in luna [:generated :harness/model])))
          (is (= "max" (get-in luna [:generated :harness/effort])))
          (is (= 1 (count luna-guidance)))
          (is (not (str/blank? (first luna-guidance))))
          (is (= "gpt-5.6-luna"
                 (attr-get luna-run :harness/model)))
          (is (= luna-guidance
                 (attr-get luna-run :harness/appended-system-prompts)))
          (harnesses/set-flag! rt :seat/sub-coordinator-terra true)
          (let [terra (harnesses/resolve-harness rt :sub-coordinator)]
            (is (= "codex" (:harness terra)))
            (is (= "gpt-5.6-terra"
                   (get-in terra [:generated :harness/model])))
            (is (= "high" (get-in terra [:generated :harness/effort])))
            (is (= luna-guidance
                   (get-in terra
                           [:generated :harness/appended-system-prompts])))
            (is (= "gpt-5.6-luna"
                   (attr-get (harnesses/run rt (:id luna-run))
                             :harness/model))))
          (is (= sol-before (harnesses/resolve-harness rt :sol)))))
      (testing "the Sol sub-coordinator resolves independently with sustained guidance"
        (let [sol-before (harnesses/resolve-harness rt :sol)
              bounded-before (harnesses/resolve-harness rt :sub-coordinator)
              sustained (harnesses/resolve-harness rt :sub-coordinator-sol)
              guidance (get-in sustained
                               [:generated :harness/appended-system-prompts])
              sustained-run (harnesses/create!
                             rt {:harness :sub-coordinator-sol
                                 :mode :interactive
                                 :cwd "/tmp"
                                 :title "Frozen Sol sub-coordinator run"})]
          (is (= "codex" (:harness sustained)))
          (is (= "gpt-5.6-sol"
                 (get-in sustained [:generated :harness/model])))
          (is (= "high" (get-in sustained [:generated :harness/effort])))
          (is (= 1 (count guidance)))
          (is (not (str/blank? (first guidance))))
          (is (= "gpt-5.6-sol"
                 (attr-get sustained-run :harness/model)))
          (is (= "high" (attr-get sustained-run :harness/effort)))
          (is (= guidance
                 (attr-get sustained-run :harness/appended-system-prompts)))
          (is (= sol-before (harnesses/resolve-harness rt :sol)))
          (is (= bounded-before
                 (harnesses/resolve-harness rt :sub-coordinator)))))
      (testing "provider defaults preserve the authoritative workspace policy"
        (is (false? (harnesses/flag rt :harness/claude)))
        (is (false? (harnesses/flag rt :harness/cursor)))
        (is (false? (:available (harnesses/availability rt :opus)))))
      (testing "shared review lenses resolve through available aliases"
        (let [catalog (reviewers/reviewers rt)]
          (is (= ["docs-and-tests" "runtime-correctness" "source-form"]
                 (mapv :name catalog)))
          (is (every? :available catalog))
          (is (some #{"spools/*/src/**"}
                    (:glob (some #(when (= "source-form" (:name %)) %)
                                 catalog))))))
      (testing "landing is active while executor activation stays deferred"
        (current/with-runtime rt
          (is (some? (workflow/workflow-definition :review)))
          (is (some? (workflow/workflow-definition :land)))
          (is (not (contains? (set (keys (workflow/executors))) :agent))))))))

(deftest live-sub-coordinator-registration-is-additive
  (t/with-weaver-world [ctx {:storage :sqlite-memory
                             :deps-edn local-deps-edn}]
    (let [rt (:runtime ctx)]
      (codethread/register! rt)
      (sub-coordinator/register! rt)
      (is (true? (harnesses/unregister-alias!
                  rt sub-coordinator/alias-name)))
      (let [registry-before (harnesses/harnesses rt)
            flags-before (harnesses/flags rt)
            modules-before (runtime/status rt)
            existing-run (harnesses/create!
                          rt {:harness :sol
                              :mode :interactive
                              :cwd "/tmp"
                              :title "Frozen existing Sol run"})
            run-before (harnesses/run rt (:id existing-run))
            registration (sub-coordinator/register! rt)
            registry-after (harnesses/harnesses rt)
            run-after (harnesses/run rt (:id existing-run))
            added (some #(when (= "sub-coordinator" (:name %)) %)
                        registry-after)
            resolved (harnesses/resolve-harness rt :sub-coordinator)
            guidance (get-in resolved
                             [:generated :harness/appended-system-prompts])]
        (is (= "sub-coordinator" (:alias registration)))
        (is (= 2 (count (:candidates registration))))
        (is (= registry-before
               (filterv #(not= "sub-coordinator" (:name %))
                        registry-after)))
        (is (= flags-before (harnesses/flags rt)))
        (is (= modules-before (runtime/status rt)))
        (is (= run-before run-after))
        (is (= "sol" (attr-get run-after :harness/alias)))
        (is (= "openai-codex/gpt-5.6-sol"
               (attr-get run-after :harness/model)))
        (is (= "codex" (:harness resolved)))
        (is (= "gpt-5.6-luna"
               (get-in resolved [:generated :harness/model])))
        (is (= "max" (get-in resolved [:generated :harness/effort])))
        (is (= 1 (count guidance)))
        (is (not (str/blank? (first guidance))))
        (is (= "alias" (:kind added)))
        (is (true? (:available added)))))))

(deftest live-sol-sub-coordinator-registration-is-additive
  (t/with-weaver-world [ctx {:storage :sqlite-memory
                             :deps-edn local-deps-edn}]
    (let [rt (:runtime ctx)]
      (codethread/register! rt)
      (sub-coordinator/register! rt)
      (sub-coordinator/register-sol! rt)
      (is (true? (harnesses/unregister-alias!
                  rt sub-coordinator/sol-alias-name)))
      (let [registry-before (harnesses/harnesses rt)
            flags-before (harnesses/flags rt)
            modules-before (runtime/status rt)
            resolutions-before
            (into {}
                  (map (fn [alias]
                         [alias (harnesses/resolve-harness rt alias)]))
                  [:sol :sub-coordinator])
            existing-runs
            (mapv #(harnesses/create!
                    rt {:harness %
                        :mode :interactive
                        :cwd "/tmp"
                        :title (str "Frozen existing " (name %) " run")})
                  [:sol :sub-coordinator])
            runs-before (mapv #(harnesses/run rt (:id %)) existing-runs)
            registration (sub-coordinator/register-sol! rt)
            registry-after (harnesses/harnesses rt)
            runs-after (mapv #(harnesses/run rt (:id %)) existing-runs)
            resolutions-after
            (into {}
                  (map (fn [alias]
                         [alias (harnesses/resolve-harness rt alias)]))
                  [:sol :sub-coordinator])
            added (some #(when (= "sub-coordinator-sol" (:name %)) %)
                        registry-after)
            resolved (harnesses/resolve-harness rt :sub-coordinator-sol)]
        (is (= "sub-coordinator-sol" (:alias registration)))
        (is (= 1 (count (:candidates registration))))
        (is (= registry-before
               (filterv #(not= "sub-coordinator-sol" (:name %))
                        registry-after)))
        (is (= flags-before (harnesses/flags rt)))
        (is (= modules-before (runtime/status rt)))
        (is (= resolutions-before resolutions-after))
        (is (= runs-before runs-after))
        (is (= "openai-codex/gpt-5.6-sol"
               (attr-get (first runs-after) :harness/model)))
        (is (= "gpt-5.6-luna"
               (attr-get (second runs-after) :harness/model)))
        (is (= "codex" (:harness resolved)))
        (is (= "gpt-5.6-sol"
               (get-in resolved [:generated :harness/model])))
        (is (= "high" (get-in resolved [:generated :harness/effort])))
        (is (= "alias" (:kind added)))
        (is (true? (:available added)))))))

(deftest consumer-modules-reconcile-before-explicit-executor-activation
  (t/with-weaver-world [ctx {:storage :sqlite-memory
                             :deps-edn local-deps-edn}]
    (let [rt (:runtime ctx)]
      (codethread/register! rt)
      (runtime/module! rt :consumer/aliases
                       {:ns 'millhouse.config.consumer-fixture
                        :after [:millhouse/config-agents]
                        :required? true})
      (is (= "openai-codex/gpt-5.6-luna"
             (get-in (harnesses/resolve-harness rt :consumer-luna)
                     [:generated :harness/model])))
      (codethread/register-executor! rt [:consumer/aliases])
      (current/with-runtime rt
        (is (contains? (set (keys (workflow/executors))) :agent))))))

(deftest checked-in-current-basis-activates-the-complete-cli-surface
  (t/with-weaver-world [ctx {:storage :sqlite-memory
                             :deps-edn (checked-in-workspace-deps-edn)
                             :init-clj workspace-init-clj
                             :files workspace-files}]
    (let [rt (:runtime ctx)
          aliases (set (map :name (weaver/op! rt 'agent ["list"])))
          reviewer-result (weaver/op! rt 'agent ["reviewers"])
          workflows (set (map :name (:definitions
                                     (weaver/op! rt 'workflow ["list"]))))
          op-names (set (map :name (weaver/ops rt)))]
      (is (contains? aliases "sol"))
      (is (= ["docs-and-tests" "runtime-correctness" "source-form"]
             (mapv :name (:reviewers reviewer-result))))
      (is (every? workflows ["auto-full-land" "auto-human-review" "land"]))
      (is (contains? op-names "auto-run"))
      (is (contains? op-names "merge-queue")))))

(deftest optional-workspace-config-keeps-the-devflow-kanban-election
  (t/with-weaver-world [ctx {:storage :sqlite-memory
                             :deps-edn local-deps-edn}]
    (let [rt (:runtime ctx)]
      (runtime/module! rt :millstrand/spools-batteries
                       {:ns 'millstrand.spools.batteries})
      (codethread/register! rt)
      (runtime/module! rt :devflow {:ns 'millhouse.devflow
                                    :after [:millhouse/workflow]})
      (runtime/module! rt :devflow/kanban-adapter
                       {:ns 'millhouse.devflow-kanban-adapter
                        :after [:devflow :millhouse/kanban
                                :millhouse/workflow]})
      (runtime/module! rt :millhouse/config-help
                       {:ns 'millhouse.config.help
                        :after [:millstrand/spools-batteries]})
      (runtime/module! rt :millhouse/config-devflow
                       {:ns 'millhouse.config.devflow})
      (let [result (runtime/module! rt :millhouse/config
                                    {:ns 'millhouse.config
                                     :after [:millhouse/config-help
                                             :millhouse/config-devflow
                                             :devflow/kanban-adapter]})]
        (is (contains? #{:applied :unchanged}
                       (get-in result [:modules :millhouse/config :status])))
        (codethread/register-executor!
         rt [:devflow/kanban-adapter :millhouse/config])
        (current/with-runtime rt
          (is (= 'millhouse.devflow-kanban-adapter/decompose-kanban
                 (workflow/workflow-definition :decompose))))))))

(defn -main
  "Run the config and cross-spool provenance checks."
  [& _]
  (let [summary (clojure.test/run-tests
                 'millhouse.config-test
                 'millhouse.config.consumer-provenance-smoke)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
