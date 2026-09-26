(ns millhouse.workflow-spec-test
  "Test workflow spec documentation, input contracts and JSON conversion."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millhouse.workflow :as workflow]))

(workflow/defworkflow introspect-stage-b
  "The stage an approved introspection round hands off to."
  {:entrypoints #{:continue}}
  (workflow/workflow
   "Introspect stage B"
   (workflow/step :finish "Finish B" :self)))

(s/def ::reason string?)

(s/def ::revise-reason-input (s/keys :req-un [::reason]))

(s/def ::introspect-params (s/keys :req-un [::feature]))

(workflow/defworkflow introspect-stage-a
  "A stage carrying a conditioned step, a routed choice, and a revision round."
  {:entrypoints #{:start :continue}
   :param-spec ::introspect-params
   :defaults {}}
  (workflow/workflow
   "Introspect stage A"
   (workflow/step :draft (fn [{:keys [feature]}] (str "Draft " feature)) :self
                  :condition [:!= :revision true])
   (workflow/step :refine "Refine draft" :self :depends-on [:draft])
   (workflow/checkpoint :signoff "Sign off"
                        :depends-on [:refine]
                        :kind :agent
                        :choices [{:key :approve
                                   :label "Approve"
                                   :description "Ship it."
                                   :next 'millhouse.workflow-spec-test/introspect-stage-b}
                                  {:key :revise
                                   :label "Revise"
                                   :description "Send it back."
                                   :revise {:params {:revision true}}
                                   :input {:spec ::revise-reason-input
                                           :doc "Why revise"}}])))

(deftest workflow-describe-projects-choices-input-and-condition-filtering
  ;; describe is a compile-time projection: no strands are written, so it needs no
  ;; runtime. On the base pass the conditioned :draft is present with its
  ;; :condition; the checkpoint's choices carry declared :input and routing.
  (let [desc (workflow/describe #'introspect-stage-a {:feature "widgets"})
        by-id (into {} (map (juxt :id identity)) (:steps desc))
        signoff (:signoff by-id)
        choices (into {} (map (juxt :key identity)) (:choices signoff))]
    (is (= "Introspect stage A" (:name desc)))
    (is (= #{:draft :refine :signoff} (set (keys by-id))))
    (is (= "Draft widgets" (:title (:draft by-id))))
    (is (= [:!= :revision true] (:condition (:draft by-id))))
    (is (= "checkpoint" (:role signoff)))
    (is (= "step" (:role (:refine by-id))))
    (is (= "millhouse.workflow-spec-test/introspect-stage-b"
           (:next (get choices "approve"))))
    (is (= {:revision true} (:revise (get choices "revise"))))
    (is (= {"spec" "millhouse.workflow-spec-test/revise-reason-input"
            "doc" "Why revise"}
           (select-keys (:input-spec (get choices "revise")) ["spec" "doc"]))))
  ;; a revision round condition-excludes :draft; its dependent :refine splices to
  ;; become the entry step, so the description matches what would pour
  (is (= #{:refine :signoff}
         (set (map :id (:steps (workflow/describe #'introspect-stage-a
                                                  {:feature "widgets" :revision true})))))))

(deftest workflow-describe-fails-loudly-on-params-its-spec-rejects
  ;; A missing required param is now the definition's own :param-spec refusing
  ;; the whole map, so describe fails with the spec's identity and explanation
  ;; rather than a hand-rolled required-key check.
  (let [data (try (workflow/describe #'introspect-stage-a {})
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :workflow/params-invalid (:reason data)))
    (is (= ::introspect-params (:spec data)))))

(s/def ::scope string?)

(s/def ::reviewer string?)

(s/def ::approval-note string?)

(s/def ::approval-input (s/keys :req-un [::approval-note]))

(workflow/defworkflow spec-first-signoff
  "Approve or reject under a live checkpoint input spec."
  {:entrypoints #{:start}}
  (workflow/workflow
   "Sign off"
   (workflow/checkpoint :signoff "Approve the change"
                        :kind :agent
                        :choices [{:key :approve
                                   :label "Approve"
                                   :input {:spec ::approval-input
                                           :doc "Record why this was approved."}}
                                  {:key :reject :label "Reject"}])))

(s/def ::documented-scope ::scope)

(s/def ::authored-params (s/keys :req-un [::scope ::documented-scope]
                                 :opt-un [::reviewer]))

(workflow/defworkflow authored-build
  "Build under authored params documentation."
  {:entrypoints #{:start}
   :param-spec ::authored-params
   :example {:scope "compact queue" :documented-scope "follow-up scope"}
   :param-docs {:scope "What to build."
                :documented-scope "Anchored through the collapsed alias."}}
  (workflow/workflow
   "Authored build"
   (workflow/step :implement "Implement" :self)))

(deftest authored-example-and-param-docs-validate-at-construction
  (testing "a valid example travels on the definition value"
    (is (= {:scope "compact queue" :documented-scope "follow-up scope"}
           (:example authored-build))))
  (testing "an example the live spec rejects fails with the projection fields"
    (let [thrown (try (workflow/workflow
                       "bad-example"
                       {:param-spec ::authored-params
                        :example {:scope 42 :documented-scope "x"}}
                       (workflow/step :a "A" :self))
                      (catch clojure.lang.ExceptionInfo e e))
          data (ex-data thrown)]
      (is (= :workflow/example-invalid (:reason data)))
      (is (= ::authored-params (:spec data)))
      (is (re-find #"scope" (:explain data)))
      (is (= "map" (get-in data [:contract "kind"])))))
  (testing "a doc for an undeclared outer key is rejected"
    (let [thrown (try (workflow/workflow
                       "bad-doc-key"
                       {:param-spec ::authored-params
                        :param-docs {:scoop "typo"}}
                       (workflow/step :a "A" :self))
                      (catch clojure.lang.ExceptionInfo e e))
          data (ex-data thrown)]
      (is (= :workflow/param-docs-unknown-key (:reason data)))
      (is (= [:scoop] (:unknown data)))
      (is (= ["documented-scope" "reviewer" "scope"] (:declared data)))))
  (testing "authored documentation without a param spec has nothing to anchor to"
    (let [thrown (try (workflow/workflow
                       "unanchored"
                       {:example {:scope "x"}}
                       (workflow/step :a "A" :self))
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :workflow/param-authoring-unanchored (:reason (ex-data thrown))))))
  (testing "a non-JSON example fails the options shape"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow options"
                          (workflow/workflow
                           "non-json"
                           {:param-spec ::authored-params
                            :example {:scope "x" :documented-scope :keyword}}
                           (workflow/step :a "A" :self)))))
  (testing "a number outside the JSON wire domain fails the options shape"
    (doseq [outlaw [1/2 Double/NaN Double/POSITIVE_INFINITY]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow options"
                            (workflow/workflow
                             "non-json-number"
                             {:param-spec ::authored-params
                              :example {:scope "x" :documented-scope outlaw}}
                             (workflow/step :a "A" :self))))))
  (testing "a blank doc fails the options shape"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow options"
                          (workflow/workflow
                           "blank-doc"
                           {:param-spec ::authored-params
                            :param-docs {:scope ""}}
                           (workflow/step :a "A" :self))))))

(deftest choices-view-spec-rejects-malformed-details
  (let [choices :millhouse.workflow.view.choices/choices]
    (is (s/valid? choices {"approve" {"label" "Approve"
                                      "input-spec" {"spec" "x/y"
                                                    "registered" true
                                                    "spec-forms" []
                                                    "contract" {}
                                                    "template" {}}}}))
    (is (not (s/valid? choices {"approve" {"unknown" 1}}))
        "detail keys are a closed set")
    (is (not (s/valid? choices {"approve" {"input-spec" {"spec" "x/y"}}}))
        "a projected input spec must say whether it is registered")
    (is (not (s/valid? choices {"approve" {"input-spec" {"spec" "x/y"
                                                         "registered" "yes"}}}))
        "registered is a boolean, not a string")))

(s/def ::label string?)

(s/def ::children (s/coll-of ::node))

(s/def ::node (s/keys :req-un [::label] :opt-un [::children]))

(s/def ::node-alias ::node)

(s/def ::draft string?)

(s/def ::stage-enum #{::draft :final})

(def ^:private predicate-calls (atom 0))

(defn- counting-string?
  "A predicate that records every call, so a discovery walk that stays out of
  validation is provable rather than asserted."
  [value]
  (swap! predicate-calls inc)
  (string? value))

(s/def ::counted counting-string?)

(s/def ::counting-params (s/keys :req-un [::counted]))

(defn- form-graph [spec-name]
  (mapv (juxt #(get % "spec") #(get % "relation")) (workflow/spec-forms spec-name)))

(deftest spec-forms-walks-nested-keys-collections-and-aliases
  (is (= [["millhouse.workflow-spec-test/node" "root"]
          ["millhouse.workflow-spec-test/children" "keyword-reference"]
          ["millhouse.workflow-spec-test/label" "keyword-reference"]]
         (form-graph ::node))
      "references are visited in qualified-name order and emitted once")
  (is (= (pr-str (s/form ::node)) (get (first (workflow/spec-forms ::node)) "form")))
  ;; `(s/def ::node-alias ::node)` registers the target spec itself, so the
  ;; alias prints the target's form and its graph is the target's graph — ::node
  ;; then reappears one level down, through ::children.
  (is (= [["millhouse.workflow-spec-test/node-alias" "root"]
          ["millhouse.workflow-spec-test/children" "keyword-reference"]
          ["millhouse.workflow-spec-test/label" "keyword-reference"]
          ["millhouse.workflow-spec-test/node" "keyword-reference"]]
         (form-graph ::node-alias)))
  (is (= (pr-str (s/form ::node)) (get (first (workflow/spec-forms ::node-alias)) "form"))))

(deftest spec-forms-reports-keyword-literals-without-claiming-dependency
  (is (= [["millhouse.workflow-spec-test/stage-enum" "root"]
          ["millhouse.workflow-spec-test/draft" "keyword-reference"]]
         (form-graph ::stage-enum))
      "a set member that also names a registered spec is supplementary documentation")
  (let [thrown (try (workflow/spec-forms ::never-registered-anywhere)
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (= :workflow/spec-missing (:reason (ex-data thrown)))
        "a stale or mistyped identity is never mistaken for a spec with no references")))

(deftest spec-forms-executes-no-predicate
  (reset! predicate-calls 0)
  (is (= [["millhouse.workflow-spec-test/counting-params" "root"]
          ["millhouse.workflow-spec-test/counted" "keyword-reference"]]
         (form-graph ::counting-params)))
  (is (zero? @predicate-calls) "discovery reads forms and the registry only")
  (is (s/valid? ::counting-params {:counted "x"}))
  (is (pos? @predicate-calls) "validation is what runs predicates"))

(s/def :acme.workflows/feature string?)

(s/def ::json-params (s/keys :req-un [::scope] :req [:acme.workflows/feature]))

(deftest json-params-keywordize-object-keys-recursively
  (is (= {:scope "queue"
          :acme.workflows/feature "cli"
          :options {:reviewer "agent" :tags ["a" "b"]}
          :steps [{:id "one"} {:id "two"}]}
         (workflow/json->params
          {"scope" "queue"
           "acme.workflows/feature" "cli"
           "options" {"reviewer" "agent" "tags" ["a" "b"]}
           "steps" [{"id" "one"} {"id" "two"}]})))
  (is (s/valid? ::json-params (workflow/json->params
                               {"scope" "queue" "acme.workflows/feature" "cli"}))
      "an unqualified JSON key satisfies :req-un and a qualified one addresses :req"))

(deftest json-params-fail-loudly-outside-the-object-contract
  (let [thrown (try (workflow/json->params [1 2 3])
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (= :workflow/params-not-json (:reason (ex-data thrown)))))
  (let [thrown (try (workflow/json->params {"" "blank"})
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (= :workflow/params-not-json (:reason (ex-data thrown))))))

(deftest describe-surfaces-the-declared-input-contract-without-its-form-graph
  (let [choices (-> (workflow/describe #'spec-first-signoff) :steps first :choices)
        approve (first (filter #(= "approve" (:key %)) choices))]
    (is (= {"spec" "millhouse.workflow-spec-test/approval-input"
            "doc" "Record why this was approved."}
           (:input-spec approve))
        "description stays cheap; the form graph is recorded when the checkpoint pours")))

(s/def ::feature string?)
