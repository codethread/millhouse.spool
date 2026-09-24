(ns consumer-deps
  "Generate a coherent published tools.deps closure for selected Millhouse roots."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn dependency-closure
  "Return selected libraries and their declared internal production dependencies.

  Reject unknown selections. External dependencies remain owned by their package
  manifests; test aliases and runtime activation are not part of this graph."
  [manifests selected]
  (when-let [unknown (seq (remove #(contains? manifests %) selected))]
    (throw (ex-info "Unknown Millhouse package" {:libraries (vec unknown)})))
  (loop [pending (seq selected)
         found #{}]
    (if-let [library (first pending)]
      (if (contains? found library)
        (recur (next pending) found)
        (recur (concat (next pending)
                       (filter #(contains? manifests %)
                               (keys (:deps (get manifests library)))))
               (conj found library)))
      found)))

(defn published-deps
  "Return direct Git coordinates for the selected internal dependency closure.

  tools.deps installs Git libraries in per-library checkouts. Promoting the full
  selected closure to top-level dependencies prevents conflicting transitive
  local roots from those distinct checkouts. Unselected packages stay absent."
  [roots manifests sha selected]
  (when-not (and (string? sha) (re-matches #"[0-9a-f]{40}" sha))
    (throw (ex-info "Expected a full immutable Millhouse SHA" {:sha sha})))
  {:deps
   (into (sorted-map)
         (map (fn [library]
                [library {:git/url "https://github.com/codethread/millhouse.spool.git"
                          :git/sha sha
                          :deps/root (get-in roots [library :root])}]))
         (dependency-closure manifests selected))})

(defn -main
  "Print consumer deps.edn for this checkout's revision and selected packages.

  Usage: scripts/consumer-deps.sh SHA LIBRARY..."
  [sha & packages]
  (when (empty? packages)
    (throw (ex-info "Select at least one Millhouse package" {})))
  (let [revision (shell/sh "git" "rev-parse" "HEAD")]
    (when-not (and (zero? (:exit revision))
                   (= sha (str/trim (:out revision))))
      (throw (ex-info "Generate from the checkout of the requested revision"
                      {:requested sha :actual (:out revision)}))))
  (let [roots (:roots (edn/read-string (slurp "spool.edn")))
        manifests (into {}
                        (map (fn [[library {:keys [root]}]]
                               [library (edn/read-string
                                         (slurp (io/file root "deps.edn")))]))
                        roots)]
    (prn (published-deps roots manifests sha (mapv symbol packages)))))
