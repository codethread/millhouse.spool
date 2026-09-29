(ns millhouse.land.card-actions-test
  "Exercise landing card bookkeeping independently of repository workflows."
  (:require [clojure.test :refer [deftest is]]
            [millhouse.kanban :as kanban]
            [millhouse.land.card-actions :as card-actions]
            [millhouse.test-support :as test-support :refer [with-runtime]]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- card-fixture
  [runtime]
  (let [root (test-support/temp-dir "millstrand-land-card-actions")
        _ (test-support/run-git! root "init" "-b" "main")
        card (:id (:card (kanban/add! runtime "Landing fixture" {})))]
    (kanban/claim! runtime card {"--owner" "test-agent"
                                 "--branch" "feature/land-test"
                                 "--worktree" (.getPath root)})
    {:root root :card card}))

(defn- card-lane
  [runtime id]
  (attr-get (weaver/show runtime id) :kanban/lane))

(deftest card-actions-are-idempotent-after-success
  (with-runtime {:storage :sqlite-memory}
    (fn [runtime _]
      (let [{:keys [root card]} (card-fixture runtime)]
        (try
          (is (nil? (card-actions/review! runtime {:card card})))
          (is (= "in_review" (card-lane runtime card)))
          (is (nil? (card-actions/pause! runtime {:card card})))
          (is (= "in_review" (card-lane runtime card)))
          (is (nil? (card-actions/rework! runtime {:card card})))
          (is (= "claimed" (card-lane runtime card)))
          (weaver/update! runtime card {:attributes {:kanban/lane "pending"}})
          (is (nil? (card-actions/finish! runtime {:card card})))
          (is (= ["closed" "done"]
                 ((juxt :state #(attr-get % :kanban/outcome))
                  (weaver/show runtime card))))
          (is (nil? (card-actions/finish! runtime {:card card})))
          (finally
            (test-support/delete-tree! root)))))))
