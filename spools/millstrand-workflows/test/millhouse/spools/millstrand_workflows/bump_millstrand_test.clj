(ns millhouse.spools.millstrand-workflows.bump-millstrand-test
  "Contract tests for the local-aware Millstrand bump workflow."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo]
            [millhouse.spools.millstrand-workflows.bump-millstrand :as bump]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.test.alpha :as t]))

(def ^:private params
  {:families ["io.millstrand/millstrand"]
   :worktree "/tmp/consumer"
   :workspace "/tmp/consumer/.millstrand"
   :direct-user-request false
   :deps-file "deps.edn"})

(def ^:private project-root
  (.getCanonicalPath (java.io.File. ".")))

(def ^:private spools-edn
  {:spools {'millhouse/spools
            {:local/root project-root
             :roots {'millhouse.spools/workflow "spools/workflow"
                     'millhouse.spools/millstrand-workflows
                     "spools/millstrand-workflows"}}}})

(defn- definition [var]
  @var)

(defn- step [definition id]
  (some #(when (= id (:id %)) %) (:steps definition)))

(deftest params-are-one-explicit-millstrand-family
  (is (s/valid? ::bump/millstrand-bump-params params))
  (is (not (s/valid?
            ::bump/millstrand-bump-params
            (assoc params :families ["millhouse/spools"]))))
  (is (not (s/valid?
            ::bump/millstrand-bump-params
            (assoc params :families
                   ["io.millstrand/millstrand" "millhouse/spools"]))))
  (is (not (s/valid?
            ::bump/millstrand-bump-params
            (assoc params :families [])))))

(deftest classification-and-local-decision-are-explicit
  (let [main (definition #'bump/bump-millstrand)
        classification (step main :coordinate-classification)
        choices (get-in classification [:attributes "workflow/choice-details"])
        local (definition #'bump/bump-millstrand-local)
        local-decision (step local :local-checkout-decision)
        local-choices (get-in local-decision [:attributes "workflow/choice-details"])]
    (is (str/includes?
         ((get-in (step main :inspect-deps) [:attributes "workflow/instruction"])
          params)
         "deps.edn"))
    (is (= #{":bump-millstrand-local" ":bump-millstrand-pinned"}
           (set (keep #(get % "next") (vals choices)))))
    (is (= ":bump-millstrand-local-validate"
           (get-in local-choices ["move-forward" "next"])))
    (is (nil? (get-in local-choices ["stop" "next"])))
    (is (str/includes?
         (get-in local-choices ["move-forward" "description"])
         "bootstrap"))))

(deftest local-path-calls-shared-bootstrap-before-refresh
  (let [local (definition #'bump/bump-millstrand-local-validate)
        bootstrap-call (step local :bootstrap-kondo)
        refresh (step local :refresh-runtime)
        compiled (workflow/compile local params)
        refs (set (map :ref (:strands compiled)))]
    (is (= #'millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo
           (:procedure bootstrap-call)))
    (is (= [:bootstrap-kondo] (:depends-on refresh)))
    (is (contains? refs :bootstrap-kondo--select-world))
    (is (contains? refs :bootstrap-kondo--adoption-mode))
    (is (contains? refs :handover-pending-generation))
    (is (not-any? #(str/includes? (name %) "quality") refs))))

(deftest pinned-path-uses-automatic-latest-sha-and-bootstrap
  (t/with-weaver-world [ctx {:storage :sqlite-memory
                             :spools-edn spools-edn}]
    (let [rt (:runtime ctx)]
      (runtime/module! rt :millhouse/workflow
                       {:ns 'millhouse.spools.workflow})
      (runtime/module! rt :millhouse/millstrand-workflows
                       {:ns 'millhouse.spools.millstrand-workflows
                        :spools ['millhouse.spools/millstrand-workflows
                                 'millhouse.spools/workflow]
                        :after [:millhouse/workflow]})
      (current/with-runtime rt
        (let [resolved (workflow/resolve-workflow :bump-millstrand-pinned)
              payload (workflow/compile (:value resolved) params)
              strands (into {} (map (juxt :ref identity) (:strands payload)))
              bump-instruction (get-in strands
                                       [:bump-spool--bump-spool-1 :attributes
                                        "workflow/instruction"])]
          (is (= #{:continue} (:entrypoints resolved)))
          (is (str/includes? bump-instruction
                             "strand --workspace /tmp/consumer/.millstrand spool bump io.millstrand/millstrand --latest sha"))
          (is (str/includes? bump-instruction "already current"))
          (is (contains? (set (keys strands)) :bump-spool--bootstrap-kondo--select-world)))))))
