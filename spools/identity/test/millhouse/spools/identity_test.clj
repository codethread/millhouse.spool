(ns millhouse.spools.identity-test
  "Focused lifecycle tests for logical session identity binding."
  (:require [clojure.test :refer [deftest is testing]]
            [millhouse.spools.identity :as identity]
            [millhouse.test-support :as test-support]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.weaver.alpha :as weaver]))

(deftest bind-mints-once-and-recovers-on-resume
  (test-support/with-runtime
    (fn [runtime _]
      (let [run (weaver/add! runtime {:title "run"})
            request {:harness "pi"
                     :native-session-id "native-1"
                     :model "claude-sonnet"
                     :thinking-level "high"
                     :run-id (:id run)}
            fresh (identity/bind! runtime request)
            resumed (identity/bind! runtime request)
            record (identity/current runtime (:identity fresh))]
        (is (re-matches #"[a-z]+-[a-z]+-[a-z]+" (:identity fresh)))
        (is (false? (:resumed fresh)))
        (is (= (assoc fresh :resumed true) resumed))
        (is (= "pi" (get-in record [:attributes :identity/harness])))
        (is (= (:identity fresh) (:title record)))
        (is (= [(:id run)]
               (mapv :to_strand_id
                     (graph/outgoing-edges runtime [(:id record)] "performed"))))))))

(deftest expected-identity-mismatch-fails-loudly
  (test-support/with-runtime
    (fn [runtime _]
      (let [bound (identity/bind! runtime {:harness "codex"
                                           :native-session-id "native-2"})]
        (testing "the native session cannot silently adopt another identity"
          (let [error (is (thrown-with-msg?
                           clojure.lang.ExceptionInfo
                           #"does not match expectation"
                           (identity/bind! runtime
                                           {:harness "codex"
                                            :native-session-id "native-2"
                                            :expected-identity "wrong-calm-otter"})))]
            (is (= (:identity bound) (:actual (ex-data error))))))))))
