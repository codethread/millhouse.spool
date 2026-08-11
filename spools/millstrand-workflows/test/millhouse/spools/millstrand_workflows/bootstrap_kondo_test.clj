(ns millhouse.spools.millstrand-workflows.bootstrap-kondo-test
  "Contract tests for first-time Millstrand Kondo adoption."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo :as bootstrap]
            [millhouse.spools.workflow :as workflow]))

(def ^:private params
  {:worktree "/tmp/consumer"
   :workspace "/tmp/consumer/.millstrand"})

(defn- definition [var]
  @var)

(defn- step [definition id]
  (some #(when (= id (:id %)) %) (:steps definition)))

(deftest params-require-explicit-local-world
  (is (s/valid? ::bootstrap/bootstrap-kondo-params params))
  (is (not (s/valid? ::bootstrap/bootstrap-kondo-params
                     (dissoc params :workspace))))
  (is (not (s/valid? ::bootstrap/bootstrap-kondo-params
                     (assoc params :worktree " ")))))

(deftest adoption-mode-is-asked-before-route-specific-work
  (let [main (definition #'bootstrap/bootstrap-kondo)
        checkpoint (step main :adoption-mode)
        choices (get-in checkpoint [:attributes "workflow/choice-details"])]
    (is (= [:select-world] (:depends-on checkpoint)))
    (is (= ":bootstrap-kondo-greenfield"
           (get-in choices ["greenfield" "next"])))
    (is (= ":bootstrap-kondo-brownfield"
           (get-in choices ["brownfield" "next"])))
    (is (str/includes? (get-in choices ["greenfield" "description"])
                       "minimal"))
    (is (str/includes? (get-in choices ["brownfield" "description"])
                       "Inventory"))))

(deftest both-routes-import-one-full-resolved-classpath
  (doseq [[definition-var mode]
          [[#'bootstrap/bootstrap-kondo-greenfield :greenfield]
           [#'bootstrap/bootstrap-kondo-brownfield :brownfield]]]
    (let [definition (definition definition-var)
          prepare (step definition :prepare)
          copy (step definition :copy-configs)
          validate (step definition :validate)
          quality (step definition :discover-quality)
          handover (step definition :handover)
          command (nth (get-in copy [:attributes "shell/argv"]) 2)
          validate-command (nth (get-in validate [:attributes "shell/argv"]) 2)]
      (let [prepare-instruction ((get-in prepare [:attributes "workflow/instruction"])
                                 params)]
        (is (str/includes? prepare-instruction (name mode)))
        (is (str/includes? prepare-instruction ".clj-kondo/.cache/"))
        (is (str/includes? prepare-instruction "repository configuration")))
      (is (= [":prepare"] (mapv str (:depends-on copy))))
      (is (str/includes? command "clj-kondo --lint \"$(clojure -Spath)\""))
      (is (str/includes? command "--dependencies --parallel --copy-configs --skip-lint"))
      (is (str/includes? validate-command
                         "git check-ignore -q --no-index .clj-kondo/.cache/"))
      (is (str/includes? validate-command
                         "git ls-files '.clj-kondo/.cache/**'"))
      (is (= "/tmp/consumer"
             ((get-in copy [:attributes "shell/cwd"]) params)))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "provenance"))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "duplicate"))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "tracked `.clj-kondo/.cache`"))
      (is (str/includes? ((get-in quality [:attributes "workflow/instruction"])
                          params)
                         "discover"))
      (is (str/includes? ((get-in handover [:attributes "workflow/instruction"])
                          params)
                         "precise local handover")))))

(deftest bootstrap-does-not-fix-a-repository-quality-command
  (let [compiled (workflow/compile (definition #'bootstrap/bootstrap-kondo-greenfield)
                                   params)
        refs (set (map :ref (:strands compiled)))]
    (is (not-any? #(str/includes? (name %) "make") refs))
    (is (not-any? #(str/includes? (name %) "github") refs))
    (is (not-any? #(str/includes? (name %) "push") refs))))
