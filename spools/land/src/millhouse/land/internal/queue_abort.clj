(ns millhouse.land.internal.queue-abort
  "Resolve repository-owned abort workflows for queue withdrawal."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]))

(defn resolve-workflow!
  "Resolve and validate the abort workflow declared by `root`.

  Merge definition defaults beneath the captured landing context and require
  the repository workflow to declare its continuation entrypoint."
  [root run-id params]
  (let [definition-ref (attr-get root :land/abort-definition)]
    (when-not (and (string? definition-ref) (not (str/blank? definition-ref)))
      (fail! "Landing root does not declare its repository abort workflow"
             {:run-id run-id :root (:id root)}))
    (let [definition-symbol (symbol definition-ref)
          definition-var
          (try
            (requiring-resolve definition-symbol)
            (catch Exception cause
              (fail! "Repository abort workflow cannot be resolved"
                     {:run-id run-id :definition definition-ref
                      :cause (.getMessage cause)})))]
      (when-not definition-var
        (fail! "Repository abort workflow cannot be resolved"
               {:run-id run-id :definition definition-ref}))
      (let [{:keys [defaults entrypoints param-spec]} @definition-var
            resolved-params (merge defaults params)]
        (when-not (contains? entrypoints :continue)
          (fail! "Repository abort workflow does not declare continue entry"
                 {:run-id run-id :definition definition-ref
                  :entrypoints entrypoints}))
        (when (and param-spec (not (s/valid? param-spec resolved-params)))
          (fail! "Repository abort workflow rejected landing context"
                 {:run-id run-id :definition definition-ref
                  :spec param-spec
                  :explain (s/explain-data param-spec resolved-params)}))
        {:params resolved-params :workflow definition-var}))))
