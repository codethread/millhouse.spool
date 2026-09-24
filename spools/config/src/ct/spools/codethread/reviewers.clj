(ns ct.spools.codethread.reviewers
  "Declare the review lenses included in the shared Harnesses catalog."
  (:require [ct.spools.harnesses.reviewers :as reviewers]
            [millstrand.api.format.alpha :as format-alpha]))

(reviewers/defreviewer!
  source-form
  "Check Clojure readability and source prose."
  {:seat ['grunt 'reviewer 'luna]
   :labels ["PR" "Clojure" "Readability"]
   :glob ["src/**" "spools/*/src/**"]
   :system-prompt
   (format-alpha/prose
    "
      This is a judgment-focused source-form review. Prefer a clear public
      story, named steps, and prose that is easy to scan over stylistic
      nitpicks. Do not duplicate correctness or test-coverage review.
      "
    {})}
  (format-alpha/prose
   "
     Review changed Clojure source for readability and source prose, not for
     the test suite. Check that public entry points lead through named steps,
     that helpers do not hide important control flow, and that docstrings,
     comments, and long prose values follow the surrounding source style.

     Report only concrete P1/P2 readability defects with repository-relative
     paths and line numbers, plus a practical rewrite or restructuring. Say
     `No findings` when the changed source is clear. Do not edit files or
     repository state.
     "
   {}))

(reviewers/defreviewer!
  docs-and-tests
  "Check contract coverage in docs and tests."
  {:seat ['grunt 'luna 'reviewer]
   :labels ["PR" "Docs" "Tests"]
   :glob ["README.md" "docs/**" "src/**" "test/**"
          "spools/*/README.md" "spools/*/src/**" "spools/*/test/**"]}
  (format-alpha/prose
   "
     Review changed files for contract coverage. Check relevant docs for
     commands, public names, and behavior changed by the patch. Check source
     and tests for a focused proof of the promised behavior; do not demand
     tests for claims they cannot establish.

     Report only concrete P1/P2 omissions with repository-relative paths and
     line numbers, followed by a practical fix. Say `No findings` when the
     diff is adequate. Do not edit files or repository state.
     "
   {}))

(reviewers/defreviewer!
  runtime-correctness
  "Check correctness of changed Clojure runtime behavior."
  {:seat ['grunt 'luna 'reviewer]
   :labels ["PR" "Correctness" "Clojure"]
   :glob ["src/**" "spools/*/src/**"]}
  (format-alpha/prose
   "
     Trace changed Clojure runtime behavior through its public seam and
     adjacent paths. Look for incorrect state transitions, boundary handling,
     error behavior, resource ordering, and concurrency mistakes. Verify
     claims against the changed code rather than proposing unrelated work.

     Report only actionable P1/P2 defects with repository-relative paths and
     line numbers, and explain the smallest practical fix. Say `No findings`
     when the behavior is sound. Do not edit files or repository state.
     "
   {}))
