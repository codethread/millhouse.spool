(ns millhouse.spools.millstrand-workflows.bump-millstrand-test
  "Contract tests for the local-aware Millstrand bump workflow."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millhouse.spools.millstrand-workflows.bump-millstrand :as bump]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.test.alpha :as t]))

(def ^:private params
  {:bumps [{:family "io.millstrand/millstrand" :version "latest"}]
   :worktree "/tmp/consumer"
   :workspace "/tmp/consumer/.millstrand"
   :direct-user-request false
   :deps-file "deps.edn"
   :quality-argv ["make" "quality"]})

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

(deftest params-are-one-explicit-millstrand-request
  (is (s/valid? ::bump/millstrand-bump-params params))
  (is (not (s/valid?
            ::bump/millstrand-bump-params
            (assoc params :bumps [{:family "millhouse/spools" :version "latest"}]))))
  (is (not (s/valid?
            ::bump/millstrand-bump-params
            (assoc params :bumps [{:family "io.millstrand/millstrand"
                                   :version "v0"}])))))

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
         "clj-kondo"))))

(deftest local-path-imports-and-validates-before-quality
  (let [local (definition #'bump/bump-millstrand-local-validate)
        copy (step local :copy-configs)
        validate (step local :validate-kondo)
        quality (step local :quality)
        compiled (workflow/compile local params)
        refs (set (map :ref (:strands compiled)))]
    (is (= [:copy-configs]
           (:depends-on validate)))
    (is (= [:validate-kondo] (:depends-on quality)))
    (is (contains? refs :handover-pending-generation))
    (is (not (contains? refs :cutover)))
    (is (= "/tmp/consumer"
           ((get-in copy [:attributes "shell/cwd"]) params)))
    (is (str/includes?
         (get-in validate [:attributes "workflow/instruction"])
         "validation"))))

(deftest pinned-path-calls-registered-bump-spool
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
              params (assoc params
                            :bumps [{:family "io.millstrand/millstrand"
                                     :version "v12"}]
                            :worktree "/srv/consumer"
                            :workspace "/srv/consumer/.millstrand"
                            :direct-user-request true
                            :quality-argv ["just" "quality"])
              payload (workflow/compile (:value resolved) params)
              strands (into {} (map (juxt :ref identity) (:strands payload)))
              refs (set (keys strands))
              bump-instruction (get-in strands
                                       [:bump-spool--bump-spool-1 :attributes
                                        "workflow/instruction"])
              select-instruction (get-in strands
                                         [:bump-spool--select-world :attributes
                                          "workflow/instruction"])
              quality (get strands :bump-spool--quality)
              cutover (get strands :bump-spool--cutover)
              handover (get strands :bump-spool--handover-pending-generation)]
          (is (= #{:continue} (:entrypoints resolved)))
          (is (contains? refs :bump-spool--copy-configs))
          (is (str/includes? select-instruction "/srv/consumer"))
          (is (str/includes? select-instruction "/srv/consumer/.millstrand"))
          (is (str/includes? bump-instruction
                             "io.millstrand/millstrand"))
          (is (str/includes? bump-instruction "--to v12"))
          (is (= ["just" "quality"]
                 (get-in quality [:attributes "shell/argv"])))
          (is (some? cutover))
          (is (nil? handover)))))))
