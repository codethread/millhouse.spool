(ns millhouse.workflow-cli-test
  "Tests for the opt-in workflow discovery CLI: the separately activated
  `workflow` op, the live registry catalogue, and the definition point read."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.cli.alpha :as cli-alpha]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.test-support :as test-support]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.cli :as cli]
            [millhouse.workflow.internal.registry :as wf-registry]
            [millstrand.test.alpha :as test-alpha]))

;; --- fixtures ---------------------------------------------------------------

(s/def ::scope string?)
(s/def ::brief string?)
(s/def ::spike-params (s/keys :req-un [::scope] :opt-un [::brief]))

(workflow/defworkflow build
  "Build an agreed feature scope."
  {:entrypoints #{:start :continue :call}}
  (workflow/workflow "Build" (workflow/step :implement "Implement the scope" :self)))

(workflow/defworkflow review
  "Review a completed implementation."
  {:entrypoints #{:start :call}}
  (workflow/workflow "Review" (workflow/step :read-diff "Read the diff" :self)))

(workflow/defworkflow fold
  "Fold a finished run back into the board."
  {:entrypoints #{:continue :call}}
  (workflow/workflow "Fold" (workflow/step :record "Record the outcome" :self)))

(workflow/defworkflow spike
  "Reduce uncertainty and recommend the next routine."
  {:entrypoints #{:start :continue}
   :param-spec ::spike-params
   :defaults {:prototype-targets ["compact queue"]}}
  (workflow/workflow
   (fn [{:keys [scope]}] (str "Spike " scope))
   (workflow/step :inspect "Inspect the current board" :self)
   (workflow/step :prototype "Prototype one target" :self
                  :depends-on [:inspect]
                  :loop {:each :prototype-targets})
   (workflow/gate :ci "Wait for CI" :agent :depends-on [:prototype])
   (workflow/call :assess :review {} :depends-on [:ci])
   (workflow/checkpoint :recommendation "Choose what follows the spike"
                        :kind :agent
                        :depends-on [:assess]
                        :choices [{:key :recommend-build :label "Build" :next :build}
                                  {:key :stop :label "Stop"}])))

(workflow/defworkflow deferred
  "Run whichever returning routine the worker picks."
  {:entrypoints #{:start}}
  (workflow/bind-defers
   (workflow/workflow
    "Hand off"
    (workflow/step :summarize "Summarize what happened" :self)
    (workflow/defer :next-routine "Choose the next routine" :depends-on [:summarize]))
   {:next-routine #{:build :fold}}))

;; Every function a definition can carry, wired to the same counter. Discovery
;; reads declarations; running any of these would mean it did something else.
(def ^:private executions (atom 0))

(defn- counted
  [value]
  (fn [_params] (swap! executions inc) value))

(s/def ::counted-scope (fn [value] (swap! executions inc) (string? value)))
(s/def ::counting-params (s/keys :req-un [::counted-scope]))

(workflow/defworkflow counting
  "Declare a function in every position discovery could be tempted to call."
  {:entrypoints #{:start}
   :param-spec ::counting-params
   :defaults {:targets ["one"]}}
  (workflow/workflow
   (counted "Counting")
   (workflow/step :render (counted "Rendered title") :self
                  :description (counted "Rendered description")
                  :attributes {"owner" (counted "agent")}
                  :loop {:each (fn [_params] (swap! executions inc) ["one"])})
   (workflow/step :conditional "Conditional" :self
                  :depends-on [:render]
                  :condition :optional)))

(defn- with-cli-runtime [f]
  ;; These reads are serialized and assert no persistence or connection topology.
  (test-support/with-embedded-runtime {:storage :sqlite-memory} f))

(defn- declared-op []
  (get-in (test-alpha/collect-module-forms
           :test/workflow-cli 'millhouse.workflow-cli-test
           #(millstrand/use-op! cli/workflow))
          [:contribution :ops :entries "workflow"]))

(defn- definition-module-source
  "Write a module source file declaring `forms` and return its workspace path."
  [config-dir label forms]
  (let [source (str "modules/" label ".clj")
        file (io/file config-dir source)]
    (io/make-parents file)
    (spit file (str "(ns test.module." label "\n"
                    "  \"Definition module fixture for the workflow discovery tests.\"\n"
                    "  (:require [millhouse.workflow :as workflow]))\n"
                    forms))
    source))

(defn- alpha-form
  [doc]
  (str "(workflow/defworkflow! alpha\n"
       "  \"" doc "\"\n"
       "  {:entrypoints #{:start}}\n"
       "  (workflow/workflow \"Alpha\" (workflow/step :a \"A\" :self)))\n"))

(defn- activate-engine!
  [rt]
  (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow))

(defn- activate-cli!
  "Activate the engine and then the separately declared CLI module."
  [rt]
  (activate-engine! rt)
  (test-support/activate-spool! rt :millhouse/workflow-cli 'millhouse.test-modules.workflow-cli
                                :after [:millhouse/workflow]))

(defn- op-names
  [rt]
  (set (map :name (weaver/ops rt))))

(defn- register!
  [& names]
  (doseq [name names]
    (workflow/register-workflow!
     name (symbol "millhouse.workflow-cli-test" (clojure.core/name name)))))

(defn- listed
  ([] (listed {}))
  ([args] (:definitions (cli/workflow {:op/args (assoc args :subcommand ["list"])}))))

(defn- shown
  [name]
  (cli/workflow {:op/args {:subcommand ["show"] :workflow (clojure.core/name name)}}))

(defn- wire-value
  [value]
  (json/read-str (json/write-str value) :key-fn keyword))

(defn- reason-of
  [f]
  (:reason (ex-data (try (f) (catch clojure.lang.ExceptionInfo e e)))))

;; --- opt-in activation ------------------------------------------------------

(deftest activating-the-workflow-engine-publishes-no-cli-ops
  ;; PROP-Wcd-001.S1: the worker vocabulary is opted into, never inherited from
  ;; the engine a spool activated for its own domain surface.
  (with-cli-runtime
    (fn [rt _]
      (activate-engine! rt)
      (is (not (contains? (op-names rt) "workflow"))
          "the engine module contributes no operation entries")
      (is (nil? (ns-resolve 'millhouse.workflow 'spool))
          "the forms-only engine exposes no legacy entry point"))))

(deftest activating-the-workflow-cli-publishes-the-workflow-op
  (with-cli-runtime
    (fn [rt _]
      (activate-cli! rt)
      (let [entry (weaver/resolve-op rt 'workflow)]
        (is (= "workflow" (:name entry)))
        (is (= 'millhouse.workflow.cli (:provenance entry)))
        (is (= 'millhouse.workflow.cli/workflow (:fn entry)))))))

(deftest cli-declaration-carries-read-classes-and-worker-guidance
  (let [entry (declared-op)]
    (doseq [verb ["list" "show" "executors"]]
      (let [leaf (get-in entry [:arg-spec :subcommands verb])]
        (is (= :read (:hook-class leaf)))
        (is (= :standard (:deadline-class leaf)))))
    (is (str/includes? (:about entry) "worker surface"))
    (let [prime (:prime entry)]
      (is (str/includes? prime "strand workflow list"))
      (is (str/includes? prime "strand workflow show intake"))
      (is (str/includes? prime
                         "strand workflow start <run-id> --workflow intake --params '{...}'"))
      (doseq [field ["params.contract" "params.template" "params.example"]]
        (is (str/includes? prime field))))))

;; --- list: deterministic filtering ------------------------------------------

(deftest list-defaults-to-startable-definitions-in-name-order
  (with-cli-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :build :review :fold :spike)
      (is (= [{:name "build"
               :doc "Build an agreed feature scope."
               :entrypoints ["start" "continue" "call"]
               :definition "millhouse.workflow-cli-test/build"}
              {:name "review"
               :doc "Review a completed implementation."
               :entrypoints ["start" "call"]
               :definition "millhouse.workflow-cli-test/review"}
              {:name "spike"
               :doc "Reduce uncertainty and recommend the next routine."
               :entrypoints ["start" "continue"]
               :definition "millhouse.workflow-cli-test/spike"}]
             (listed))
          "only :start definitions, in registered-name order, with exactly the four catalogue fields"))))

(deftest list-selects-one-entrypoint-at-a-time
  (with-cli-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :build :review :fold :spike)
      (is (= ["build" "fold" "spike"] (mapv :name (listed {:entrypoint "continue"}))))
      (is (= ["build" "fold" "review"] (mapv :name (listed {:entrypoint "call"}))))
      (is (= ["build" "review" "spike"] (mapv :name (listed {:entrypoint "start"})))))))

(deftest list-reads-the-registry-live
  ;; The catalogue answers from the effective registry at each call: a module
  ;; publishing a definition, deleting it by omission, and a trusted repoint are
  ;; all visible to the next read with no restart.
  (with-cli-runtime
    (fn [rt config-dir]
      (activate-cli! rt)
      (let [source (definition-module-source config-dir "cli-alpha" (alpha-form "Alpha routine."))]
        (is (= :applied (:status (runtime/module! rt :cli-alpha {:file source}))))
        (is (= [{:name "alpha"
                 :doc "Alpha routine."
                 :entrypoints ["start"]
                 :definition "test.module.cli-alpha/alpha"}]
               (listed))
            "a definition living in a workspace module resolves under the runtime's spool classloader")
        (definition-module-source config-dir "cli-alpha" (alpha-form "Alpha routine, revised."))
        (runtime/module! rt :cli-alpha {:file source})
        (is (= ["Alpha routine, revised."] (mapv :doc (listed)))
            "the next read sees the reloaded definition Var")
        (definition-module-source config-dir "cli-alpha" "")
        (runtime/module! rt :cli-alpha {:file source})
        (is (= [] (listed)) "an owner deleting its entry by omission empties the catalogue")))))

(deftest discovery-fails-loudly-on-a-definition-whose-var-vanished
  ;; Publication refuses an unresolvable entry, so this is the case where a Var
  ;; disappeared *after* it was published. The catalogue says so rather than
  ;; quietly listing one workflow fewer.
  (with-cli-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :build)
      (wf-registry/register-definition! rt :ghost 'millhouse.workflow-cli-test/no-such-definition)
      (let [data (ex-data (try (listed) (catch clojure.lang.ExceptionInfo e e)))]
        (is (= :workflow/definition-unresolvable (:reason data)))
        (is (= :ghost (:name data)))
        (is (= 'millhouse.workflow-cli-test/no-such-definition (:definition data)))
        (is (= :millstrand.owner/repl (:owner data)) "the failure names the owner who must repair it")
        (is (seq (:repair data))))
      (is (= :workflow/definition-unresolvable (reason-of #(shown :ghost)))))))

;; --- show: the full-fidelity point read -------------------------------------

(deftest show-projects-a-static-definition-exactly
  (with-cli-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :build :review :spike)
      (let [view (shown :spike)]
        (is (= {:operation "workflow show"
                :name "spike"
                :doc "Reduce uncertainty and recommend the next routine."
                :entrypoints ["start" "continue"]
                :definition "millhouse.workflow-cli-test/spike"}
               (dissoc view :params :declared)))
        (testing "the param contract is the live spec, its form graph, and the defaults"
          (is (= {:kind "spec"
                  :defaults {:prototype-targets ["compact queue"]}
                  :spec "millhouse.workflow-cli-test/spike-params"
                  :spec-forms [{"spec" "millhouse.workflow-cli-test/spike-params"
                                "relation" "root"
                                "form" (pr-str (s/form ::spike-params))}
                               {"spec" "millhouse.workflow-cli-test/brief"
                                "relation" "keyword-reference"
                                "form" "clojure.core/string?"}
                               {"spec" "millhouse.workflow-cli-test/scope"
                                "relation" "keyword-reference"
                                "form" "clojure.core/string?"}]}
                 (-> (:params view)
                     (dissoc :contract :template)
                     (update :spec-forms
                             (partial mapv #(select-keys % ["spec" "relation" "form"])))))))
        (testing "params carry the shared nested contract and copyable template"
          (is (= "map" (get-in view [:params :contract "kind"])))
          (is (= ["scope"] (mapv #(get % "key")
                                 (get-in view [:params :contract "required"]))))
          (is (contains? (get-in view [:params :template]) "scope")))
        (testing "the declared summary reports roles without expanding one"
          (is (= {:kind "static"
                  :entry ["inspect"]
                  :loops [{:step "prototype" :each "prototype-targets"}]
                  :gates [{:step "ci" :waiter "agent"}]
                  :checkpoints [{:step "recommendation" :choices ["recommend-build" "stop"]}]
                  :calls [{:step "assess" :procedure "review" :kind "registered"}]
                  :defers []
                  :routes ["build"]}
                 (:declared view))))))))

(deftest show-answers-for-definitions-list-omits-by-default
  (with-cli-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :build :review :fold)
      (is (= ["start" "call"] (:entrypoints (shown :review)))
          "the point read preserves every declared entrypoint")
      (is (= {:kind "none" :defaults {}} (:params (shown :review)))
          "a definition constraining nothing says so rather than omitting the field")
      (is (= ["continue" "call"] (:entrypoints (shown :fold)))))))

(deftest show-reports-a-defer-exit-with-its-bound-targets
  (with-cli-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :build :fold :deferred)
      (is (= {:kind "static"
              :entry ["summarize"]
              :loops []
              :gates []
              :checkpoints []
              :calls []
              :defers [{:step "next-routine"
                        :defer "next-routine"
                        :workflows ["build" "fold"]
                        :entrypoint "call"}]
              :routes []}
             (:declared (shown :deferred)))))))

(deftest show-fails-loudly-on-an-unregistered-name
  (with-cli-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :build)
      (let [data (ex-data (try (shown :absent) (catch clojure.lang.ExceptionInfo e e)))]
        (is (= :workflow/definition-unregistered (:reason data)))
        (is (= [:build] (:registered data)))))))

(deftest discovery-executes-no-definition-function
  ;; PROP-Wcd-001.S3: rendered names, titles, attributes, loop sources,
  ;; conditions, and spec predicates all stay unevaluated. A catalogue read is
  ;; safe to ask for at any time precisely because it runs none of them.
  (with-cli-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :counting)
      (reset! executions 0)
      (let [view (shown :counting)]
        (is (= "spec" (get-in view [:params :kind])))
        (is (= [{:step "render" :each "fn"}] (get-in view [:declared :loops]))
            "a computed loop source is reported as computed, never resolved")
        (is (= ["render"] (get-in view [:declared :entry]))))
      (is (seq (listed)))
      (is (zero? @executions)
          "discovery reads declarations and spec forms only")
      (is (s/valid? ::counting-params {:counted-scope "x"}))
      (is (pos? @executions) "validation is what runs a predicate"))))

;; --- executors: gate-executor discovery --------------------------------------

(defn never-stalled
  "Stall predicate fixture that reports a healthy gate."
  [_gate-view]
  nil)

(deftest executors-projects-declared-request-contracts
  ;; The gate-authoring read: every registered waiter in order, each carrying
  ;; its stall predicate and — where the executor declares a request spec — the
  ;; projected contract with the exact attribute keys an author writes.
  ;; Shell activation starts a concurrent DB-reading scanner.
  (test-support/with-runtime
    (fn [rt _]
      (activate-cli! rt)
      (test-support/activate-spool! rt :millhouse/shell 'millhouse.test-modules.shell-executor
                                    :after [:millhouse/workflow])
      (workflow/register-executor! :bare-sym 'millhouse.workflow-cli-test/never-stalled)
      (workflow/register-executor! :raw-fn (constantly nil))
      (let [result (cli/workflow {:op/args {:subcommand ["executors"]}})
            items (:executors result)
            by-waiter (into {} (map (juxt :waiter identity)) items)]
        (is (= "workflow executors" (:operation result)))
        (is (= ["bare-sym" "raw-fn" "shell"] (mapv :waiter items))
            "one item per waiter, in waiter order")
        (testing "a declared executor projects its gate-request contract"
          (let [item (by-waiter "shell")
                request (:request item)]
            (is (= "execution" (:driver item)))
            (is (= "millhouse.executors.shell/request" (:spec request)))
            (is (= ["shell/argv"]
                   (mapv #(get % "key") (get-in request [:contract "required"])))
                "shell/argv is the one required attribute, qualified spelling")
            (is (= ["shell/cwd" "shell/timeout-secs"]
                   (mapv #(get % "key") (get-in request [:contract "optional"]))))
            (is (= #{"shell/argv" "shell/cwd" "shell/timeout-secs"}
                   (set (keys (:template request))))
                "the template is keyed by the attribute spelling an author writes")
            (is (vector? (get (:template request) "shell/argv"))
                "the argv skeleton renders as a JSON array")
            (is (seq (:spec-forms request)))))
        (testing "a bare-symbol executor lists with no contract"
          (is (= {:waiter "bare-sym"
                  :stall-predicate "millhouse.workflow-cli-test/never-stalled"}
                 (by-waiter "bare-sym"))))
        (testing "a raw function value lists with a null stall predicate"
          (is (= {:waiter "raw-fn" :stall-predicate nil}
                 (by-waiter "raw-fn"))))))))

(deftest workflow-op-returns-match-their-declaration
  (with-cli-runtime
    (fn [rt _]
      (activate-cli! rt)
      (register! :build :review :spike)
      (let [parse #(cli-alpha/parse (:arg-spec (weaver/resolve-op rt 'workflow)) %)
            list-result (cli/workflow {:op/args (parse ["list" "--entrypoint" "call"])})]
        (is (= ["build" "review"] (mapv :name (:definitions list-result))))
        (is (= "workflow list" (:operation list-result)))
        (test-alpha/check-op-return! rt 'workflow {:subcommand ["list"]}
                                     (wire-value list-result)))
      (let [args (cli-alpha/parse (:arg-spec (weaver/resolve-op rt 'workflow))
                                  ["show" "spike"])
            result (cli/workflow {:op/args args})]
        (is (= "spike" (:name result)))
        (test-alpha/check-op-return! rt 'workflow {:subcommand ["show"]}
                                     (wire-value result)))
      (workflow/register-executor! :bare-sym 'millhouse.workflow-cli-test/never-stalled)
      (test-alpha/check-op-return!
       rt 'workflow {:subcommand ["executors"]}
       (wire-value (cli/workflow {:op/args {:subcommand ["executors"]}}))))))

(deftest declared-args-parse-without-a-world
  ;; Collect the public declaration, not a private arg-spec or a fake runtime.
  ;; Real publication and parsed handler dispatch are checked separately.
  (let [parse #(cli-alpha/parse (:arg-spec (declared-op)) %)]
    (is (= {:subcommand ["list"]} (parse ["list"])))
    (is (= {:subcommand ["list"] :entrypoint "call"}
           (parse ["list" "--entrypoint" "call"])))
    (is (= {:subcommand ["show"] :workflow "spike"} (parse ["show" "spike"])))
    (is (= {:subcommand ["executors"]} (parse ["executors"])))
    (testing "the parser refuses what the surface does not declare"
      (is (thrown? clojure.lang.ExceptionInfo (parse ["show"])))
      (is (thrown? clojure.lang.ExceptionInfo (parse ["list" "--all"])))
      (is (thrown? clojure.lang.ExceptionInfo (parse ["list" "--limit" "5"])))
      (is (thrown? clojure.lang.ExceptionInfo (parse ["start" "run-1"]))))))

(deftest workflow-op-refuses-an-unknown-verb
  (is (thrown? clojure.lang.ExceptionInfo
               (cli/workflow {:op/args {:subcommand ["explain"]}}))))
