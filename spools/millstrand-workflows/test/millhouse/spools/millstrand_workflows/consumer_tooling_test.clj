(ns millhouse.spools.millstrand-workflows.consumer-tooling-test
  "Contract tests for manual consumer tooling setup by repository style."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.spools.millstrand-workflows.consumer-tooling :as tooling]
            [millhouse.spools.workflow :as workflow]))

(def ^:private params
  {:worktree "/tmp/consumer"
   :workspace "/tmp/consumer/.millstrand"})

(def ^:private routes
  [[:app #'tooling/configure-consumer-tooling-app]
   [:spool #'tooling/configure-consumer-tooling-spool]
   [:clojure-app #'tooling/configure-consumer-tooling-clojure-app]])

(defn- definition [var]
  @var)

(defn- step [definition id]
  (some #(when (= id (:id %)) %) (:steps definition)))

(defn- instruction [definition id]
  ((get-in (step definition id) [:attributes "workflow/instruction"])
   params))

(deftest params-require-an-explicit-consumer-world
  (is (s/valid? ::tooling/consumer-tooling-params params))
  (is (not (s/valid? ::tooling/consumer-tooling-params
                     (dissoc params :workspace))))
  (is (not (s/valid? ::tooling/consumer-tooling-params
                     (assoc params :worktree " ")))))

(deftest repository-style-choice-is-explicit
  (let [parent (definition #'tooling/configure-consumer-tooling)
        checkpoint (step parent :repository-style)
        choices (get-in checkpoint [:attributes "workflow/choice-details"])
        inspect-text (instruction parent :inspect-repository)]
    (is (= [:inspect-repository] (:depends-on checkpoint)))
    (is (= ":configure-consumer-tooling-app"
           (get-in choices ["app" "next"])))
    (is (= ":configure-consumer-tooling-spool"
           (get-in choices ["spool" "next"])))
    (is (= ":configure-consumer-tooling-clojure-app"
           (get-in choices ["clojure-app" "next"])))
    (is (nil? (get-in choices ["unsupported" "next"])))
    (is (str/includes? inspect-text "spool status"))
    (is (str/includes? inspect-text "app"))
    (is (str/includes? inspect-text "spool"))
    (is (str/includes? inspect-text "clojure-app"))))

(deftest every-route-is-an-ordinary-manual-sequence
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [route (definition definition-var)
            steps (:steps route)
            payload (workflow/compile route params)]
        (is (= [:align-tools-deps
                :configure-lsp
                :configure-lint
                :configure-tests
                :verify-weaver
                :handover]
               (mapv :id steps)))
        (is (= [nil
                [:align-tools-deps]
                [:configure-lsp]
                [:configure-lint]
                [:configure-tests]
                [:verify-weaver]]
               (mapv :depends-on steps)))
        (is (every? #(nil? (get-in % [:attributes "workflow/gate"])) steps))
        (is (every? #(nil? (get-in % [:attributes "shell/argv"])) steps))
        (is (not (str/includes? (pr-str payload) "workflow/gate")))))))

(deftest application-route-preserves-an-empty-product-base
  (let [route (definition #'tooling/configure-consumer-tooling-app)]
    (is (str/includes? (instruction route :align-tools-deps) ":paths []"))
    (is (str/includes? (instruction route :configure-lsp)
                       "select the Millstrand and test aliases explicitly"))
    (is (str/includes? (instruction route :configure-lsp)
                       "reads `spools.edn`"))
    (is (str/includes? (instruction route :configure-lint)
                       "Do not count syntax-only lint as hook proof"))
    (is (str/includes? (instruction route :configure-tests)
                       "disposable unpublished Weaver world"))
    (is (str/includes? (instruction route :verify-weaver)
                       "invoke one of its real operations"))))

(deftest spool-route-proves-publication-through-an-approved-world
  (let [route (definition #'tooling/configure-consumer-tooling-spool)]
    (is (str/includes? (instruction route :align-tools-deps)
                       "consumer approval in `spools.edn`"))
    (is (str/includes? (instruction route :configure-lint)
                       "own producer coordinate"))
    (is (str/includes? (instruction route :configure-tests)
                       "A direct `require` alone does not prove runtime acquisition"))
    (is (str/includes? (instruction route :configure-tests)
                       "fixture `spools.edn`"))
    (is (str/includes? (instruction route :verify-weaver)
                       "`:spools`-guarded namespace module"))))

(deftest clojure-application-route-preserves-the-ordinary-application
  (let [route (definition #'tooling/configure-consumer-tooling-clojure-app)]
    (is (str/includes? (instruction route :align-tools-deps)
                       "preserve the ordinary application's base"))
    (is (str/includes? (instruction route :configure-lsp)
                       "base application basis remains unchanged"))
    (is (str/includes? (instruction route :configure-tests)
                       "run it without selecting Millstrand"))
    (is (str/includes? (instruction route :configure-tests)
                       "reaches ordinary application code"))
    (is (str/includes? (instruction route :verify-weaver)
                       "result came from ordinary application code"))))

(deftest pending-generation-evidence-is-not-called-adopted-proof
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [route (definition definition-var)
            verify-text (instruction route :verify-weaver)
            handover-text (instruction route :handover)]
        (is (str/includes? verify-text "Do not restart"))
        (is (str/includes? verify-text "run that same style-specific probe"))
        (is (str/includes? verify-text "current-generation-only evidence"))
        (is (str/includes? verify-text "not adoption proof"))
        (is (str/includes? verify-text "adopted generation as unfinished"))
        (is (str/includes? handover-text "prepared configuration"))
        (is (str/includes? handover-text "adopted generation proof"))
        (is (str/includes? handover-text "unfinished"))))))
