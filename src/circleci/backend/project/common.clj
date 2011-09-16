(ns circleci.backend.project.common)

;; fns used by multiple project types

(defn remote-checkout
  "checks out the source code onto our build box"
  [& {:keys [project-name git-url]}])