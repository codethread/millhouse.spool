(ns millhouse.workflow.internal.execution.data
  "Private execution wire contracts and restricted snapshot encoding."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn json?
  "True for finite JSON values; nil is a value, not missing acceptance."
  [x]
  (cond
    (map? x) (and (every? #(or (string? %) (keyword? %)) (keys x))
                  (every? json? (vals x)))
    (vector? x) (every? json? x)
    (number? x) (and (not (ratio? x)) (Double/isFinite (double x)))
    :else (or (nil? x) (boolean? x) (string? x))))

(defn edn-data?
  "Accept only value EDN; reject records, Java handles, functions and tags."
  [x]
  (cond
    (record? x) false
    (map? x) (every? edn-data? (mapcat identity x))
    (or (vector? x) (set? x) (list? x)) (every? edn-data? x)
    :else (or (nil? x) (boolean? x) (string? x) (keyword? x)
              (and (number? x) (Double/isFinite (double x))))))

(defn encode
  "Encode a complete version-one snapshot without runtime objects."
  [memory]
  (when-not (edn-data? memory)
    (throw (ex-info "Execution snapshot contains non-EDN data" {})))
  (pr-str {:version 1 :memory memory}))

(defn decode
  "Read the one supported snapshot version; never reset unknown state."
  [text]
  (let [snapshot (edn/read-string {:readers {}
                                   :default (fn [tag _]
                                              (throw (ex-info "Unsupported EDN tag" {:tag tag})))} text)]
    (when-not (and (= #{:version :memory} (set (keys snapshot)))
                   (= 1 (:version snapshot)) (map? (:memory snapshot))
                   (edn-data? snapshot))
      (throw (ex-info "Unsupported execution snapshot" {})))
    (:memory snapshot)))

(defn error
  "Normalize a failure to bounded JSON diagnostics, never retaining Throwable."
  [code throwable]
  {:code code
   :message (subs (or (ex-message throwable) (str throwable))
                  0 (min 2048 (count (or (ex-message throwable) (str throwable)))))
   :data {}})

(defn nonblank? [x] (and (string? x) (not (str/blank? x))))

(defn- bounded? [x]
  (and (json? x) (<= (count (pr-str x)) 16384)))

(defn- diagnostic? [x]
  (and (map? x) (= #{:code :message :data} (set (keys x)))
       (nonblank? (:code x)) (nonblank? (:message x))
       (map? (:data x)) (bounded? x)))

(defn observation?
  "Validate the closed backend observation boundary."
  [x]
  (and (map? x)
       (json? (dissoc x :status :phase :outcome :settlement))
       (case (:status x)
         :busy (= {:status :busy} x)
         :pending (and (every? #{:status :phase :reference :detail} (keys x))
                       (#{:waiting :running :stopping} (:phase x))
                       (bounded? (:detail x)))
         :unknown (and (every? #{:status :reference :reason} (keys x))
                       (diagnostic? (:reason x)))
         :terminal (and (every? #{:status :outcome :settlement :reference :value :error :evidence} (keys x))
                        (#{:succeeded :failed :cancelled} (:outcome x))
                        (= :settled (:settlement x)) (contains? x :value)
                        (or (nil? (:error x)) (diagnostic? (:error x)))
                        (map? (:evidence x)) (bounded? (:evidence x)))
         false)))
