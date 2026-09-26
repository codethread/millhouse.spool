(ns millhouse.test-support-test
  "Check the repository fixture's isolation, binding and cleanup contract."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [millhouse.test-support :as support]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.weaver.alpha :as weaver]))

(deftest embedded-runtime-rejects-unknown-options-and-invalid-storage
  (doseq [opts [{:storag :sqlite-memory} {:storage :unknown}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (support/with-embedded-runtime
                   opts (fn [_ _] (throw (AssertionError. "Must not call body"))))))))

(deftest embedded-runtime-cleans-up-and-does-not-reuse-state
  (let [first-world (atom nil)
        second-dir (atom nil)
        strand-id (atom nil)
        failure (ex-info "Fixture body failed" {})]
    (is (identical?
         failure
         (try
           (support/with-embedded-runtime
             {:storage :sqlite-memory}
             (fn [rt dir]
               (reset! first-world {:runtime rt :dir dir})
               (is (identical? rt (current/runtime)))
               (reset! strand-id (:id (weaver/add! rt {:title "First world"})))
               (spit (io/file dir "marker") "first")
               (throw failure)))
           (catch clojure.lang.ExceptionInfo e e))))
    (is (not (.exists ^java.io.File (:dir @first-world))))
    ;; The existing name must still work with omitted storage (file default).
    (support/with-runtime
      (fn [rt dir]
        (reset! second-dir dir)
        (is (identical? rt (current/runtime)))
        (is (not (identical? rt (:runtime @first-world))))
        (is (not= dir (:dir @first-world)))
        (is (nil? (weaver/show rt @strand-id)))
        (is (not (.exists (io/file dir "marker"))))))
    (is (not (.exists ^java.io.File @second-dir)))))
