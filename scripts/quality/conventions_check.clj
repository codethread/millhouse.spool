(ns quality.conventions-check
  "Enforce shared-spool Clojure conventions that prose cannot hold."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [quality.source-forms :as source-forms]))

(def source-roots
  "Clojure roots scanned by the conventions gate."
  ["spools/chime/src"
   "spools/cron/src"
   "spools/workflow/src"
   "spools/kanban/src"
   ".millstrand/init.clj"
   "test"])

(def spool-roots
  "Shared-spool production roots subject to public API and tier checks."
  #{"spools/chime/src"
    "spools/cron/src"
    "spools/workflow/src"
    "spools/kanban/src"})

(def ^:private core-macro-names
  (->> (ns-publics 'clojure.core)
       vals
       (filter #(:macro (meta %)))
       (map #(-> % symbol name))
       set))

(def ^:private public-var-forms '#{def defonce defn defmacro})

(defn- under-root?
  [filename roots]
  (some #(str/starts-with? filename (str % java.io.File/separator)) roots))

(defn- clj-files
  [roots]
  (for [root roots
        :let [root-file (io/file root)]
        ^java.io.File file (if (.isDirectory root-file)
                             (sort (file-seq root-file))
                             [root-file])
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
    file))

(defn- quoted-libspec-ns
  [arg]
  (when (and (seq? arg) (= 'quote (first arg)) (= 2 (count arg)))
    (let [libspec (second arg)]
      (cond
        (symbol? libspec) libspec
        (and (vector? libspec) (symbol? (first libspec))) (first libspec)))))

(defn- quoted-require-calls
  [form]
  (letfn [(walk [node quoted?]
            (if (coll? node)
              (let [inner? (or quoted? (and (seq? node) (= 'quote (first node))))
                    hit (when (and quoted? (seq? node) (= 'require (first node)))
                          [node])]
                (into (vec hit) (mapcat #(walk % inner?)) (seq node)))
              []))]
    (walk form false)))

(defn- resolvable-namespace?
  [ns-sym]
  (let [path (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/"))
        candidates [(str path ".clj") (str path ".cljc")]]
    (boolean
     (or (some (fn [root]
                 (some #(.isFile (io/file root %)) candidates))
               source-roots)
         (some io/resource candidates)))))

(defn- embedded-require-findings
  [file forms]
  (for [form forms
        call (quoted-require-calls form)
        arg (rest call)
        :let [ns-sym (quoted-libspec-ns arg)]
        :when (and ns-sym (not (resolvable-namespace? ns-sym)))]
    (str file ":" (:line (meta call)) ": embedded require of `" ns-sym
         "` resolves to no source file or classpath resource")))

(defn reproducible-json?
  "True when `value` is object/array JSON reproduced exactly by json/write-str."
  [value]
  (and (string? value)
       (or (str/starts-with? value "{") (str/starts-with? value "["))
       (str/includes? value "\"")
       (try
         (= value (json/write-str (json/read-str value)))
         (catch Exception _ false))))

(defn- json-findings
  [file forms]
  (letfn [(walk [form line compared?]
            (cond
              (string? form)
              (when (and (not compared?) (reproducible-json? form))
                [(str file ":" line
                      ": JSON is hand-escaped; author Clojure data and call json/write-str")])

              (coll? form)
              (let [line (or (:line (meta form)) line)
                    comparison? (and (seq? form) (#{'= 'not=} (first form)))]
                (mapcat #(walk % line comparison?) form))

              :else []))]
    (mapcat #(walk % 1 false) forms)))

(def ^:private deferred-body-forms
  '#{comment defn defmacro fn fn* quote var delay lazy-seq})

(defn- spool-declaration-site
  [form]
  (when (and (seq? form)
             (contains? public-var-forms (first form))
             (= 'spool (second form))
             (not (:private (meta (second form)))))
    form))

(defn- spool-declaration-sites
  [form]
  (if-let [site (spool-declaration-site form)]
    [site]
    (cond
      (seq? form)
      (if (contains? deferred-body-forms (first form))
        []
        (mapcat spool-declaration-sites (rest form)))

      (coll? form)
      (mapcat spool-declaration-sites form)

      :else [])))

(defn- spool-declaration-findings
  [file forms]
  (for [form forms
        site (spool-declaration-sites form)]
    (str file ":" (:line (meta site))
         ": legacy public `spool` declaration; use millstrand.api.lifecycle.alpha authoring forms")))

(defn source-findings
  "Return authored-source findings for `roots`."
  [roots]
  (mapcat
   (fn [^java.io.File file]
     (let [path (.getPath file)
           lines (str/split-lines (slurp file))
           forms (source-forms/read-all file)]
       (concat
        (for [[index line] (map-indexed vector lines)
              :when (> (count line) 180)]
          (str path ":" (inc index) ": source line exceeds 180 columns"))
        (embedded-require-findings path forms)
        (when (under-root? path spool-roots)
          (json-findings path forms))
        (spool-declaration-findings path forms))))
   (clj-files roots)))

(defn analysis-findings
  "Return findings derived from clj-kondo `analysis`."
  [analysis]
  (concat
   (for [{:keys [filename name]} (:namespace-definitions analysis)
         :when (nil? (:doc (first (filter #(and (= filename (:filename %))
                                                (= name (:name %)))
                                          (:namespace-definitions analysis)))))]
     (str filename ": namespace " name " has no docstring"))
   (for [{:keys [filename row name]} (:locals analysis)
         :when (core-macro-names (str name))]
     (str filename ":" row ": local `" name "` shadows a clojure.core macro"))
   (for [{:keys [filename row name private doc defined-by]} (:var-definitions analysis)
         :when (and (under-root? filename spool-roots)
                    (not (str/includes? (str/replace filename "\\" "/") "/internal/"))
                    (not private)
                    (not= "clojure.core/declare" (str defined-by))
                    (nil? doc))]
     (str filename ":" row ": public var `" name "` has no docstring"))
   (for [{:keys [filename row from to]} (concat (:namespace-usages analysis)
                                                (:var-usages analysis))
         :when (and (under-root? filename spool-roots)
                    (str/starts-with? (str to) "millstrand.core."))]
     (str filename ":" row ": shared spool `" from
          "` uses internal namespace `" to "`; use millstrand.api.*.alpha"))))

(defn findings
  "Return every convention finding for the configured roots and Kondo analysis."
  [analysis]
  (doseq [root source-roots]
    (when-not (.exists (io/file root))
      (throw (ex-info "Configured source root is missing" {:root root}))))
  (concat (analysis-findings analysis)
          (source-findings source-roots)))

(defn -main
  [& _]
  (let [input (java.io.PushbackReader. *in* 8192)
        analysis (:analysis (json/read input :key-fn keyword))
        all-findings (vec (findings analysis))]
    (if (seq all-findings)
      (do
        (binding [*out* *err*]
          (doseq [finding all-findings]
            (println finding))
          (println "conventions-check:" (count all-findings) "finding(s)"))
        (System/exit 1))
      (println "conventions-check: OK"))))
