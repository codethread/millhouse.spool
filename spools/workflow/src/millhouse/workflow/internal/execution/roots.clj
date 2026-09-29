(ns millhouse.workflow.internal.execution.roots
  "Private nearest-root membership shared by decisions and transaction authority."
  (:require [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get]]))

(defn nearest-root [rt gate]
  (loop [frontier [(:id gate)] seen #{}]
    (let [ids (->> (graph/incoming-edges rt frontier "parent-of")
                   (map :from_strand_id) (remove seen) distinct vec)
          roots (filterv #(= "root" (attr-get % :workflow/role)) (graph/strands-by-ids rt ids))]
      (cond
        (= 1 (count roots)) (first roots)
        (> (count roots) 1) (throw (ex-info "Ambiguous nearest workflow root" {:gate (:id gate)}))
        (empty? ids) nil
        :else (recur ids (into seen frontier))))))
