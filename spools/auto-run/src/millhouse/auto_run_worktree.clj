(ns millhouse.auto-run-worktree
  "Optional wktree preparation recipe for the auto-run dispatcher."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [fail!]]))

(defn ready-result
  "Parse wktree's ready result, rejecting blocked allocation and wrong branches."
  [text branch]
  (let [result (json/read-str text :key-fn keyword)
        cwd (:worktree_path result)
        script (:post_create_script_path result)]
    (when-not (and (= "ready" (:kind result))
                   (= branch (:branch result))
                   (string? cwd) (not (str/blank? cwd))
                   (or (nil? script) (and (string? script) (not (str/blank? script)))))
      (fail! "wktree did not prepare the requested branch" {:branch branch :result result}))
    {:cwd cwd :branch branch :script script}))

(defn- command! [repo argv]
  (let [{:keys [exit out err]} (apply sh/sh (concat argv [:dir repo]))]
    (when-not (zero? exit)
      (fail! "Auto-run worktree preparation failed"
             {:argv argv :repo repo :exit exit :out out :err err}))
    out))

(defn prepare!
  "Create auto/<card-id> using repository wktree policy and run its setup script.

  Existing branches and blocked allocations fail visibly; the dispatcher never
  retries this side effect. Repositories may supply their own callback instead.
  No card is claimed and no fallback to the canonical checkout is permitted."
  [_rt {:keys [repo card]}]
  (let [branch (str "auto/" (:id card))
        {:keys [cwd script] :as prepared}
        (ready-result (command! repo ["wktree" "--cwd" repo "add"
                                      "--branch" branch "--json"])
                      branch)]
    (when (= (.getCanonicalPath (io/file repo)) (.getCanonicalPath (io/file cwd)))
      (fail! "Auto-run refuses the canonical checkout as a worktree" {:cwd cwd}))
    (when script (command! repo ["bash" script]))
    (dissoc prepared :script)))
