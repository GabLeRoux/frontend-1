(ns frontend.controllers.api
  (:require [cljs.core.async :refer [close!]]
            [frontend.api :as api]
            [frontend.api.path :as api-path]
            [frontend.async :refer [put! raise!]]
            [frontend.components.forms :refer [release-button!]]
            [frontend.models.action :as action-model]
            [frontend.models.build :as build-model]
            [frontend.models.container :as container-model]
            [frontend.models.project :as project-model]
            [frontend.models.repo :as repo-model]
            [frontend.models.user :as user-model]
            [frontend.notifications :as notifications]
            [frontend.pusher :as pusher]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.analytics.core :as analytics]
            [frontend.favicon]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.state :as state-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils.docs :as doc-utils]
            [frontend.utils :as utils :refer [mlog merror]]
            [om.core :as om :include-macros true]
            [goog.string :as gstring]
            [clojure.set :as set]
            [cljs-time.core :as time]
            [cljs-time.format :as timef]
            [frontend.datetime :as datetime]
            [frontend.components.forms :as forms]))

;; when a button is clicked, the post-controls will make the API call, and the
;; result will be pushed into the api-channel
;; the api controller will do assoc-in
;; the api-post-controller can do any other actions

;; --- API Multimethod Declarations ---

(defmulti api-event
  ;; target is the DOM node at the top level for the app
  ;; message is the dispatch method (1st arg in the channel vector)
  ;; args is the 2nd value in the channel vector)
  ;; state is current state of the app
  ;; return value is the new state
  (fn [target message status args state] [message status]))

(defmulti post-api-event!
  (fn [target message status args previous-state current-state comms] [message status]))

;; --- API Multimethod Implementations ---

(defmethod api-event :default
  [target message status args state]
  ;; subdispatching for state defaults
  (let [submethod (get-method api-event [:default status])]
    (if submethod
      (submethod target message status args state)
      (do (merror "Unknown api: " message args)
          state))))

(defmethod post-api-event! :default
  [target message status args previous-state current-state comms]
  ;; subdispatching for state defaults
  (let [submethod (get-method post-api-event! [:default status])]
    (if submethod
      (submethod target message status args previous-state current-state)
      (merror "Unknown api: " message status args))))

(defmethod api-event [:default :started]
  [target message status args state]
  (mlog "No api for" [message status])
  state)

(defmethod post-api-event! [:default :started]
  [target message status args previous-state current-state comms]
  (mlog "No post-api for: " [message status]))

(defmethod api-event [:default :success]
  [target message status args state]
  (mlog "No api for" [message status])
  state)

(defmethod post-api-event! [:default :success]
  [target message status args previous-state current-state comms]
  (mlog "No post-api for: " [message status]))

(defmethod api-event [:default :failed]
  [target message status args state]
  (mlog "No api for" [message status])
  state)

(defmethod post-api-event! [:default :failed]
  [target message status args previous-state current-state comms]
  (put! (:errors comms) [:api-error args])
  (mlog "No post-api for: " [message status]))

(defmethod api-event [:default :finished]
  [target message status args state]
  (mlog "No api for" [message status])
  state)

(defmethod post-api-event! [:default :finished]
  [target message status args previous-state current-state comms]
  (mlog "No post-api for: " [message status]))


(defmethod api-event [:projects :success]
  [target message status {:keys [resp]} {:keys [navigation-point] :as current-state}]
  (let [new-projects (map (fn [project] (update project :scopes #(set (map keyword %)))) resp)
        old-projects-lookup (into {} (for [old-p (get-in current-state state/projects-path)]
                                       [(-> old-p
                                            api/project-build-key
                                            (dissoc :branch))
                                        old-p]))
        processed-new-projects
        (for [new-project new-projects
              :let [matching-project (or (old-projects-lookup (-> new-project
                                                                  api/project-build-key
                                                                  (dissoc :branch)))
                                         {})]]
          (merge new-project
                 (select-keys matching-project [:recent-builds :build-timing])))]
    (assoc-in current-state state/projects-path processed-new-projects)))

(def projects-success-thens
  {:build-insights (fn [state comms]
                     (let [projects (get-in state state/projects-path)
                           build-keys (map api/project-build-key projects)
                           api-ch (:api comms)]
                         (api/get-projects-builds build-keys 60 api-ch)))
   :project-insights (fn [{:keys [navigation-data] :as state} comms]
                       (let [build-key (api/project-build-key navigation-data)
                             api-ch (:api comms)]
                         (api/get-projects-builds [build-key] 100 api-ch)
                         (api/get-branch-build-times build-key api-ch)))})

(defmethod post-api-event! [:projects :success]
  [target message status args previous-state {:keys [navigation-point] :as current-state} comms]
  (when-let [then (projects-success-thens navigation-point)]
    (then current-state comms)))

(defmethod api-event [:me :success]
  [target message status args state]
  (update-in state state/user-path merge (:resp args)))

(defmethod api-event [:recent-builds :success]
  [target message status args state]
  (if-not (and (= (get-in state [:navigation-data :org])
                  (get-in args [:context :org]))
               (= (get-in state [:navigation-data :repo])
                  (get-in args [:context :repo]))
               (= (get-in state [:navigation-data :branch])
                  (get-in args [:context :branch]))
               (= (get-in state [:navigation-data :query-params :page])
                  (get-in args [:context :query-params :page])))
    state
    (-> state
        (assoc-in [:recent-builds] (:resp args))
        (assoc-in state/project-scopes-path (:scopes args))
        ;; Hack until we have organization scopes
        (assoc-in state/page-scopes-path (or (:scopes args) #{:read-settings})))))

(defmethod api-event [:recent-project-builds :success]
  [target message status {page-of-recent-builds :resp, {target-key :project-id
                                                        page-result :page-result
                                                        all-page-results :all-page-results} :context} state]
  ;; Deliver the result to the result atom.
  (assert (nil? @page-result))
  (reset! page-result page-of-recent-builds)

  ;; If all page-results have been delivered, we're ready to update the state.
  (if (every? deref all-page-results)
    (let [all-recent-builds (apply concat (map deref all-page-results))
          add-recent-builds (fn [projects]
                              (for [project projects
                                    :let [{:keys [branch]} target-key
                                          project-key (api/project-build-key project)]]
                                (cond-> project
                                  (= (dissoc project-key :branch) (dissoc target-key :branch))
                                  (assoc-in [:recent-builds branch] all-recent-builds))))]
      (update-in state state/projects-path add-recent-builds))
    state))

(defmethod api-event [:branch-build-times :success]
  [target message status {timing-data :resp, {:keys [target-key]} :context} state]
  (let [add-timing-data (fn [projects]
                          (doall (for [project projects
                                       :let [{:keys [branch]} target-key
                                             project-key (api/project-build-key project)]]
                                   (cond-> project
                                     (= (dissoc project-key :branch) (dissoc target-key :branch))
                                     (assoc-in [:build-timing branch] timing-data)))))]
    (update-in state state/projects-path add-timing-data)))

(defmethod api-event [:build-observables :success]
  [target message status {:keys [context resp]} state]
  (let [parts (:build-parts context)]
    (if (= parts (state-utils/build-parts (get-in state state/build-path)))
      (update-in state state/build-path merge resp)
      (if-let [index (state-utils/usage-queue-build-index-from-build-parts state parts)]
        (update-in state (state/usage-queue-build-path index) merge resp)
        state))))

(defmethod post-api-event! [:build-observables :success]
  [target message status args previous-state current-state comms]
  (let [build (get-in current-state state/build-path)
        previous-build (get-in previous-state state/build-path)]
    (frontend.favicon/set-color! (build-model/favicon-color build))
    (when (and (build-model/finished? build)
               (empty? (get-in current-state state/tests-path)))
      (when (and (not (build-model/finished? previous-build))
                 ;; TODO for V2 notifications we should consider reading from localstorage directly because
                 ;; storing it in state gets it out of sync with localstorage — or maybe this is reasonable
                 ;; behavior for our app?
                 (get-in current-state state/web-notifications-enabled-path))
        (notifications/notify-build-done build))
      (api/get-build-tests build (:api comms)))))

(defmethod post-api-event! [:build-fetch :success]
  [target message status args previous-state current-state comms]
  (mlog "=>" "post-api-event! [:build-fetch :success]" ["API" :build :success args])
  (put! (:api comms) [:build :success args]))

(defmethod post-api-event! [:build-fetch :failed]
  [target message status {:keys [status-code]} previous-state current-state comms]
  (mlog "=>" "post-api-event! [:build-fetch :failed]" [:error {:status status-code :inner? false}])
  (put! (:nav comms) [:error {:status status-code :inner? false}]))

(defmethod post-api-event! [:build-fetch :finished]
  [target message status {:keys [ajax-execution-path]} previous-state current-state comms]
  (when-not ajax-execution-path
    (mlog "=>" "post-api-event! [:build-fetch :failed]" ["ERR" :api-error nil])
    (put! (:error comms) [:api-error nil])))

(defmethod api-event [:build :success]
  [target message status args state]
  (mlog "build success: scopes " (:scopes args))
  (let [build (:resp args)
        {:keys [build-num project-name]} (:context args)
        containers (vec (build-model/containers build))]
    (if-not (and (= build-num (:build_num build))
                 (= project-name (vcs-url/project-name (:vcs_url build))))
      state
      (let [branch (some-> build :branch utils/encode-branch)
            tag (some-> build :vcs_tag utils/encode-branch)
            crumb-path (state/project-branch-crumb-path state)
            tag-crumb-path (conj crumb-path :tag)
            active-crumb-path (conj crumb-path :active)
            branch-crumb-path (conj crumb-path :branch)]
        (cond-> state
                (and branch (not tag)) (assoc-in branch-crumb-path branch)
                tag (assoc-in tag-crumb-path tag)
                tag (assoc-in active-crumb-path true)
                true (assoc-in state/build-path build)
                true (assoc-in state/project-scopes-path (:scopes args))
                true (assoc-in state/page-scopes-path (:scopes args))
                true (assoc-in state/containers-path containers))))))

(defn maybe-set-containers-filter!
  "Depending on the status and outcome of the build, set active
  container filter to failed."
  [state comms]
  (let [build (get-in state state/build-path)
        containers (get-in state state/containers-path)
        build-running? (not (build-model/finished? build))
        failed-containers (filter #(= :failed (container-model/status % build-running?))
                                  containers)
        {:keys [container-id]} (get-in state state/navigation-data-path)
        failed-filter-valid? (or (not container-id)
                                 (some #(= container-id (container-model/id %)) failed-containers))
        controls-ch (:controls comms)]
    ;; set filter
    (when (and (not build-running?)
               (seq failed-containers)
               failed-filter-valid?)
      (put! controls-ch [:container-filter-changed {:new-filter :failed
                                                    :containers failed-containers}]))))

(defn fetch-visible-output
  "Only fetch the output that is shown by default and is shown in this container."
  [current-state comms build-num vcs-url]
  (let [visible-actions-with-output (->> (get-in current-state state/containers-path)
                                         (mapcat :actions)
                                         (filter #(action-model/visible-with-output? % (get-in current-state state/current-container-path)))
                                         ;; Some steps, like deployment, were run on conainer 0 but are on every container.
                                         ;; These steps will be returned every time, since they are duplicated of eachother, and therefore always are visible?
                                         ;; To keep from calling the api once for each duplicate, only call it on distinct actions.
                                         (distinct))]
    (doseq [action visible-actions-with-output]
      (api/get-action-output {:vcs-url vcs-url
                              :build-num build-num
                              :step (:step action)
                              :index (:index action)}
                             (:api comms)))))

(defmethod post-api-event! [:build :success]
  [target message status {build :resp :keys [scopes context] :as args} previous-state current-state comms]
  (let [api-ch (:api comms)
        {:keys [build-num project-name]} context
        {:keys [org repo vcs_type]} (state/navigation-data current-state)
        {:keys [read-settings]} scopes
        plan-url (gstring/format "/api/v1.1/project/%s/%s/plan" vcs_type project-name)]
    ;; This is slightly different than the api-event because we don't want to have to
    ;; convert the build from steps to containers again.
    (analytics/track {:event-type :view-build
                      :current-state current-state
                      :build build})
    ;; Preemptively make the usage-queued API call if the build is in the
    ;; usage queue and the user has access to the info
    (when (and read-settings
               (build-model/in-usage-queue? build))
      (api/get-usage-queue build api-ch))
    (when (and (not (get-in current-state state/project-path))
               repo
               read-settings)
      (ajax/ajax :get
                 (api-path/project-info vcs_type org repo)
                 :project-settings
                 api-ch
                 :context {:project-name project-name
                           :vcs-type vcs_type}))
    (when (and (not (get-in current-state state/project-plan-path))
               repo
               read-settings)
      (ajax/ajax :get
                 plan-url
                 :project-plan
                 api-ch
                 :context {:project-name project-name
                           :vcs-type vcs_type}))
    (when (build-model/finished? build)
      (api/get-build-tests build api-ch))
    (when (and (= build-num (get-in args [:resp :build_num]))
               (= project-name (vcs-url/project-name (get-in args [:resp :vcs_url]))))
      (fetch-visible-output current-state comms build-num (get-in args [:resp :vcs_url]))
      (frontend.favicon/set-color! (build-model/favicon-color (get-in current-state state/build-path)))
      (maybe-set-containers-filter! current-state comms))))

(defmethod api-event [:cancel-build :success]
  [target message status {:keys [context resp]} state]
  (let [build-id (:build-id context)]
    (if-not (= build-id (build-model/id (get-in state state/build-path)))
      state
      (update-in state state/build-path merge resp))))

(defmethod api-event [:github-repos :success]
  [target message status args state]
  (if (empty? (:resp args))
    ;; this is the last api request, update the loading flag.
    (assoc-in state state/github-repos-loading-path false)
    ;; Add the items on this page of results to the state.
    (update-in state state/repos-path #(into % (:resp args)))))

(defmethod post-api-event! [:github-repos :success]
  [target message status args previous-state current-state comms]
  (when-not (empty? (:resp args))
    ;; fetch the next page
    (let [page (-> args :context :page)]
      (api/get-github-repos (:api comms) :page (inc page)))))

(defmethod api-event [:github-repos :failed]
  [target message status {:keys [status-code]} state]
  (cond-> state
    (= 401 status-code) (update-in state/user-path user-model/deauthorize-github)
    true (assoc-in state/github-repos-loading-path false)))

(defmethod api-event [:bitbucket-repos :success]
  [target message status args state]
  (-> state
      (assoc-in state/bitbucket-repos-loading-path nil)
      (update-in state/repos-path #(into % (:resp args)))))

(defmethod api-event [:bitbucket-repos :failed]
  [target message status {:keys [status-code]} state]
  (cond-> state
    (= 401 status-code) (update-in state/user-path user-model/deauthorize-bitbucket)
    true (assoc-in state/bitbucket-repos-loading-path false)))

(defn filter-piggieback [orgs]
  "Return subset of orgs that aren't covered by piggyback plans."
  (let [covered-orgs (into #{} (apply concat (map :piggieback_orgs orgs)))]
    (remove (comp covered-orgs :login) orgs)))

(defmethod api-event [:organizations :success]
  [target message status {orgs :resp} state]
  (assoc-in state state/user-organizations-path orgs))

(defmethod api-event [:tokens :success]
  [target message status args state]
  (print "Tokens received: " args)
  (assoc-in state state/user-tokens-path (:resp args)))

(defmethod api-event [:usage-queue :success]
  [target message status args state]
  (let [usage-queue-builds (:resp args)
        build-id (:context args)]
    (if-not (= build-id (build-model/id (get-in state state/build-path)))
      state
      (assoc-in state state/usage-queue-path usage-queue-builds))))

(defmethod post-api-event! [:usage-queue :success]
  [target message status args previous-state current-state comms]
  (let [usage-queue-builds (get-in current-state state/usage-queue-path)
        ws-ch (:ws comms)]
    (doseq [build usage-queue-builds
            :let [parts (state-utils/build-parts build)]]
      (put! ws-ch [:subscribe {:channel-name (pusher/build-all-channel parts)
                               :messages [:build/update]}])
      (put! ws-ch [:subscribe {:channel-name (pusher/obsolete-build-channel parts)
                               :messages [:build/update]}]))))


(defmethod api-event [:build-artifacts :success]
  [target message status args state]
  (let [artifacts (:resp args)
        build-id (:context args)]
    (if-not (= build-id (build-model/id (get-in state state/build-path)))
      state
      (assoc-in state state/artifacts-path artifacts))))

(defmethod api-event [:build-tests :success]
  [target message status {{:keys [tests exceptions]} :resp :as args} state]
  (let [build-id (:context args)]
    (cond-> state
      (= build-id (build-model/id (get-in state state/build-path)))
      (-> (update-in state/tests-path (fn [old-tests]
                                        ;; prevent om from thrashing while doing comparisons
                                        (if (> (count tests) (count old-tests))
                                          tests
                                          (vec old-tests))))
          (assoc-in state/tests-parse-errors-path exceptions)))))

(defmethod api-event [:action-steps :success]
  [target message status args state]
  (let [build (get-in state state/build-path)
        {:keys [build-num project-name]} (:context args)]
    (if-not (and (= build-num (:build_num build))
                 (= project-name (vcs-url/project-name (:vcs_url build))))
      state
      (let [build (assoc build :steps (:resp args))]
        (-> state
            (assoc-in state/build-path build)
            (assoc-in state/containers-path (vec (build-model/containers build))))))))

(defn update-pusher-subscriptions
  [state comms old-index new-index]
  (let [ws-ch (:ws comms)
        build (get-in state state/build-path)]
    (put! ws-ch [:unsubscribe (-> build
                                  (state-utils/build-parts old-index)
                                  (pusher/build-container-channel))])
    (put! ws-ch [:subscribe {:channel-name (-> build
                                               (state-utils/build-parts new-index)
                                               (pusher/build-container-channel))
                             :messages pusher/container-messages}])))

(defmethod post-api-event! [:action-steps :success]
  [target message status args previous-state current-state comms]
  (let [{:keys [build-num project-name old-container-id new-container-id]} (:context args)
        build (get-in current-state state/build-path)
        vcs-url (:vcs_url build)]
    (when (and (= build-num (:build_num build))
               (= project-name (vcs-url/project-name vcs-url)))
      (fetch-visible-output current-state comms build-num vcs-url)
      (update-pusher-subscriptions current-state comms old-container-id new-container-id)
      (frontend.favicon/set-color! (build-model/favicon-color build)))))

(defmethod api-event [:action-log :success]
  [target message status args state]
  (let [action-log (:resp args)
        {action-index :step container-index :index} (:context args)
        build (get-in state state/build-path)
        vcs-url (:vcs_url build)]
    (-> state
        (assoc-in (state/action-output-path container-index action-index) action-log)
        (update-in (state/action-path container-index action-index)
                   (fn [action]
                     (if (some :truncated action-log)
                       (assoc action :truncated-client-side? true)
                       action)))
        (assoc-in (conj (state/action-path container-index action-index) :user-facing-output-url)
                  (api-path/action-output-file
                   (vcs-url/vcs-type vcs-url)
                   (vcs-url/project-name vcs-url)
                   (:build_num build)
                   action-index
                   container-index))
        (update-in (state/action-path container-index action-index) action-model/format-all-output))))


(defmethod api-event [:project-settings :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (str (get-in state [:navigation-data :org]) "/" (get-in state [:navigation-data :repo])))
    state
    (update-in state state/project-path merge resp)))


(defmethod api-event [:project-plan :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (str (get-in state [:navigation-data :org]) "/" (get-in state [:navigation-data :repo])))
    state
    (assoc-in state state/project-plan-path resp)))


(defmethod api-event [:project-token :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (:project-settings-project-name state))
    state
    (assoc-in state state/project-tokens-path resp)))


(defmethod api-event [:project-checkout-key :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (:project-settings-project-name state))
    state
    (assoc-in state state/project-checkout-keys-path resp)))


(defmethod api-event [:project-envvar :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (:project-settings-project-name state))
    state
    (assoc-in state
              state/project-envvars-path
              (into {} (map (juxt :name :value)) resp))))


(defmethod api-event [:update-project-parallelism :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state (conj state/project-data-path :parallelism-edited) true)))

(defn update-cache-clear-state
  [state {:keys [resp context]} k success?]
  (if-not (= (:project-id context)
             (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state
              (conj state/project-data-path k)
              {:success? success?
               :message (:status resp
                                 (if success?
                                   "cache cleared"
                                   "cache not cleared"))})))

(defmethod api-event [:clear-build-cache :success]
  [target message status args state]
  (update-cache-clear-state state args :build-cache-clear true))

(defmethod api-event [:clear-build-cache :failed]
  [target message status args state]
  (update-cache-clear-state state args :build-cache-clear false))

(defmethod api-event [:clear-source-cache :success]
  [target message status args state]
  (update-cache-clear-state state args :source-cache-clear true))

(defmethod api-event [:clear-source-cache :failed]
  [target message status args state]
  (update-cache-clear-state state args :source-cache-clear false))

(defmethod post-api-event! [:clear-build-cache :success]
  [_ _ _ {:keys [context]} _ _]
  (release-button! (:uuid context) :success))

(defmethod post-api-event! [:clear-build-cache :failed]
  [_ _ _ {:keys [context]} _ _]
  (release-button! (:uuid context) :failed))

(defmethod post-api-event! [:clear-source-cache :success]
  [_ _ _ {:keys [context]} _ _]
  (release-button! (:uuid context) :success))

(defmethod post-api-event! [:clear-source-cache :failed]
  [_ _ _ {:keys [context]} _ _]
  (release-button! (:uuid context) :failed))


(defmethod api-event [:create-env-var :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (-> state
        (update-in state/project-envvars-path (fnil conj {}) [(:name resp) (:value resp)])
        (assoc-in (conj state/inputs-path :new-env-var-name) "")
        (assoc-in (conj state/inputs-path :new-env-var-value) "")
        (state/add-flash-notification "Environment variable added successfully."))))

(defmethod post-api-event! [:create-env-var :success]
  [target message status {:keys [context]} previous-state current-state comms]
  ((:on-success context)))


(defmethod api-event [:delete-env-var :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (-> state
        (update-in state/project-envvars-path dissoc (:env-var-name context))
        (state/add-flash-notification (gstring/format "Environment variable '%s' deleted successfully."
                                                      (:env-var-name context))))))


(defmethod api-event [:save-ssh-key :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (-> state
        (assoc-in (conj state/project-data-path :new-ssh-key) {})
        (state/add-flash-notification "Your key has been successfully added."))))

(defmethod post-api-event! [:save-ssh-key :success]
  [target message status {:keys [context resp]} previous-state current-state comms]
  (when (= (:project-id context) (project-model/id (get-in current-state state/project-path)))
    ((:on-success context))
    (let [project-name (vcs-url/project-name (:project-id context))
          api-ch (:api comms)
          vcs-type (vcs-url/vcs-type (:project-id context))
          org-name (vcs-url/org-name (:project-id context))
          repo-name (vcs-url/repo-name (:project-id context))]
      (ajax/ajax :get
                 (api-path/project-settings vcs-type org-name repo-name)
                 :project-settings
                 api-ch
                 :context {:project-name project-name}))))


(defmethod api-event [:delete-ssh-key :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (let [{:keys [hostname fingerprint]} context]
      (-> state
          (update-in (conj state/project-path :ssh_keys)
                     (fn [keys]
                       (remove #(and (= (:hostname %) hostname)
                                     (= (:fingerprint %) fingerprint))
                               keys)))
          (state/add-flash-notification "Your key has been successfully deleted.")))))


(defmethod api-event [:save-project-api-token :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (-> state
        (assoc-in (conj state/project-data-path :new-api-token) {})
        (update-in state/project-tokens-path (fnil conj []) resp)
        (state/add-flash-notification "Your token has been successfully added."))))


(defmethod post-api-event! [:save-project-api-token :success]
  [target message status {:keys [context]} previous-state current-state comms]
  (forms/release-button! (:uuid context) status)
  ((:on-success context)))


(defmethod api-event [:delete-project-api-token :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (-> state
        (update-in state/project-tokens-path
                   (fn [tokens]
                     (remove #(= (:token %) (:token context))
                             tokens)))
        (state/add-flash-notification "Your token has been successfully deleted."))))


(defmethod api-event [:set-heroku-deploy-user :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state (conj state/project-path :heroku_deploy_user) (:login context))))


(defmethod api-event [:remove-heroku-deploy-user :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state (conj state/project-path :heroku_deploy_user) nil)))


(defmethod api-event [:first-green-build-github-users :success]
  [target message status {:keys [resp context]} state]
  (if-not (and (= (:project-name context)
                  (vcs-url/project-name (:vcs_url (get-in state state/build-path))))
               (= (:vcs-type context)
                  (get-in state (conj state/build-path :vcs_type))))
    state
    (assoc-in state
              state/build-invite-members-path
              (vec (map-indexed (fn [i u] (assoc u :index i)) resp)))))

(defmethod api-event [:invite-team-members :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (vcs-url/project-name (:vcs_url (get-in state state/build-path))))
    state
    (assoc-in state state/dismiss-invite-form-path true)))


(defmethod api-event [:get-org-members :success]
  [target message status {:keys [resp context]} state]
  (let [{:keys [vcs-type org-name]} context]
    (assoc-in state
              (conj (state/org-ident vcs-type org-name) :vcs-users)
              resp)))


(defmethod api-event [:enable-project :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (update-in state state/project-path merge (select-keys resp [:has_usable_key]))))


(defmethod api-event [:follow-project :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state (conj state/project-path :followed) true)))


(defmethod api-event [:unfollow-project :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state (conj state/project-path :followed) false)))

(defmethod api-event [:stop-building-project :success]
  [target message status {:keys [resp context]} state]
  (update-in state state/projects-path (fn [projects] (remove #(= (:project-id context) (project-model/id %)) projects))))

(defmethod post-api-event! [:stop-building-project :success]
  [target message status args previous-state current-state comms]
  (put! (:nav comms) [:navigate! {:path (routes/v1-dashboard)}]))

(defn org-selectable?
  [state org-name vcs-type]
  (let [org-settings (get-in state state/org-settings-path)
        add-projects-selected-org (get-in state state/add-projects-selected-org-path)]
    (or (and (= org-name (:org-name org-settings))
             (= vcs-type (:vcs_type org-settings)))
        (and (= org-name (:login add-projects-selected-org))
             (= vcs-type (:vcs_type add-projects-selected-org))))))

(defmethod api-event [:org-plan :success]
  [target message status {:keys [resp context]} state]
  (let [{:keys [org-name vcs-type]} context]
    (if-not (org-selectable? state org-name vcs-type)
      state
      (let [{piggieback-orgs :piggieback_org_maps
             :as plan}
            resp]
        (-> state
            (assoc-in state/org-plan-path plan)
            (assoc-in state/selected-piggieback-orgs-path (set piggieback-orgs)))))))

(defmethod api-event [:org-settings :success]
  [target message status {:keys [resp context]} state]
  (let [{:keys [org-name vcs-type]} context]
    (if-not (org-selectable? state org-name vcs-type)
      state
      (-> state
          (update-in state/org-data-path merge resp)
          (assoc-in state/org-loaded-path true)
          (assoc-in state/org-authorized?-path true)))))

(defmethod api-event [:org-settings :failed]
  [target message status {:keys [resp context]} state]
  (let [{:keys [org-name vcs-type]} context]
    (if-not (org-selectable? state org-name vcs-type)
      state
      (-> state
          (assoc-in state/org-loaded-path true)
          (assoc-in state/org-authorized?-path false)))))

(defmethod api-event [:follow-repo :success]
  [target message status {:keys [resp context]} state]
  (if-let [repo-index (state-utils/find-repo-index (get-in state state/repos-path)
                                                   (:login context)
                                                   (:name context))]
    (assoc-in state (conj (state/repo-path repo-index) :following) true)
    state))

(defmethod post-api-event! [:follow-repo :success]
  [target message status args previous-state current-state comms]
  (api/get-projects (:api comms))
  (if-let [first-build (get-in args [:resp :first_build])]
    (let [nav-ch (:nav comms)
          build-path (-> first-build
                         :build_url
                         (goog.Uri.)
                         (.getPath)
                         (subs 1))]
      (put! nav-ch [:navigate! {:path build-path}]))
    (when (repo-model/should-do-first-follower-build? (:context args))
      (ajax/ajax :post
                 (gstring/format "/api/v1.1/project/%s/%s"
                                 (-> args :context :vcs_type)
                                 (vcs-url/project-name (:vcs_url (:context args))))
                 :start-build
                 (:api comms)))))

(defmethod api-event [:unfollow-repo :success]
  [target message status {:keys [resp context]} state]
  (if-let [repo-index (state-utils/find-repo-index (get-in state state/repos-path)
                                                   (:login context)
                                                   (:name context))]
    (assoc-in state (conj (state/repo-path repo-index) :following) false)
    state))

(defmethod post-api-event! [:unfollow-repo :success]
  [target message status args previous-state current-state comms]
  (api/get-projects (:api comms)))


(defmethod post-api-event! [:start-build :success]
  [target message status args previous-state current-state comms]
  (let [nav-ch (:nav comms)
        build-url (-> args :resp :build_url (goog.Uri.) (.getPath) (subs 1))]
    (put! nav-ch [:navigate! {:path build-url}])))


(defmethod post-api-event! [:retry-build :success]
  [target message status args previous-state current-state comms]
  (let [nav-ch (:nav comms)
        build-url (-> args :resp :build_url (goog.Uri.) (.getPath) (subs 1))]
    (put! nav-ch [:navigate! {:path build-url}])))


(defmethod post-api-event! [:save-dependencies-commands :success]
  [target message status {:keys [context resp]} previous-state current-state comms]
  (when (and (= (project-model/id (get-in current-state state/project-path))
                (:project-id context))
             (= :setup (:project-settings-subpage current-state)))
    (let [nav-ch (:nav comms)
          org (vcs-url/org-name (:project-id context))
          repo (vcs-url/repo-name (:project-id context))
          vcs-type (vcs-url/vcs-type (:project-id context))]
      (put! nav-ch [:navigate! {:path (routes/v1-project-settings-path {:org org
                                                                        :repo repo
                                                                        :vcs_type vcs-type
                                                                        :_fragment "tests"})}]))))


(defmethod post-api-event! [:save-test-commands-and-build :success]
  [target message status {:keys [context resp]} previous-state current-state comms]
  (let [controls-ch (:controls comms)]
    (put! controls-ch [:started-edit-settings-build context])))


(defmethod api-event [:plan-card :success]
  [target message status {:keys [resp context]} state]
  (let [{:keys [org-name vcs-type]} context]
    (if-not (org-selectable? state org-name vcs-type)
      state
      (let [card (or resp {})] ; special case in case card gets deleted
        (assoc-in state state/stripe-card-path card)))))


(defmethod api-event [:create-plan :success]
  [target message status {:keys [resp context]} state]
  (let [{:keys [org-name vcs-type]} context]
    (if-not (org-selectable? state org-name vcs-type)
      state
      (assoc-in state state/org-plan-path resp))))

(defmethod post-api-event! [:create-plan :success]
  [target message status {:keys [resp context]} previous-state current-state comms]
  (let [{:keys [org-name vcs-type]} context]
    (when (org-selectable? current-state org-name vcs-type)
      (let [nav-ch (:nav comms)]
        (put! nav-ch [:navigate! {:path (routes/v1-org-settings-path {:org org-name
                                                                      :vcs_type vcs-type
                                                                      :_fragment (name (get-in current-state state/org-settings-subpage-path))})
                                  :replace-token? true}])))))

(defmethod api-event [:update-plan :success]
  [target message status {:keys [resp context]} state]
  (let [{:keys [org-name vcs-type]} context]
    (cond-> state
      (and (= org-name (get-in state state/project-plan-org-name-path))
           (= vcs-type (get-in state state/project-plan-vcs-type-path)))
      (assoc-in state/project-plan-path resp)

      (org-selectable? state org-name vcs-type)
      (update-in state/org-plan-path merge resp))))

(defmethod api-event [:update-heroku-key :success]
  [target message status {:keys [resp context]} state]
  (assoc-in state (conj state/user-path :heroku-api-key-input) ""))

(defmethod api-event [:create-api-token :success]
  [target message status {:keys [resp context]} state]
  (let [label (:label context)
        label (if (empty? label)
                label
                (str "'" label "'"))]
    (-> state
        (assoc-in state/new-user-token-path "")
        (update-in state/user-tokens-path conj resp)
        (state/add-flash-notification (gstring/format "API token %s added successfully."
                                                      label)))))

(defmethod api-event [:delete-api-token :success]
  [target message status {:keys [resp context]} state]
  (let [deleted-token (:token context)
        label (:label deleted-token)
        label (if (empty? label)
                label
                (str "'" label "'"))]
    (-> state
        (update-in state/user-tokens-path (fn [tokens]
                                            (vec (remove #(= (:token %) (:token deleted-token)) tokens))))
        (state/add-flash-notification (gstring/format "API token %s deleted successfully."
                                                      label)))))

(defmethod api-event [:plan-invoices :success]
  [target message status {:keys [resp context]} state]
  (utils/mlog ":plan-invoices API event: " resp)
  (let [{:keys [org-name vcs-type]} context]
    (if-not (org-selectable? state org-name vcs-type)
      state
      (assoc-in state state/org-invoices-path resp))))

(defmethod api-event [:build-state :success]
  [target message status {:keys [resp]} state]
  (assoc-in state state/build-state-path resp))

(defmethod api-event [:fleet-state :success]
  [target message status {:keys [resp]} state]
  (assoc-in state state/fleet-state-path resp))

(defmethod api-event [:get-all-system-settings :success]
  [_ _ _ {:keys [resp]} state]
  (assoc-in state state/system-settings-path resp))

(defmethod api-event [:build-system-summary :success]
  [target message status {:keys [resp]} state]
  (assoc-in state state/build-system-summary-path resp))

(defmethod api-event [:license :success]
  [target message status {:keys [resp]} state]
  (assoc-in state state/license-path resp))

(defmethod api-event [:all-users :success]
  [_ _ _ {:keys [resp]} state]
  (assoc-in state state/all-users-path resp))

(defmethod api-event [:set-user-admin-state :success]
  [_ _ _ {user :resp} state]
  (assoc-in state
            state/all-users-path
            (map #(if (= (:login %) (:login user))
                    user
                    %)
                 (get-in state state/all-users-path))))

(defmethod api-event [:system-setting-set :success]
  [_ _ _ {updated-setting :resp} state]
  (update-in state
             state/system-settings-path
             (fn [settings]
               (mapv #(if (= (:name  %) (:name updated-setting))
                        updated-setting
                        %)
                     settings))))

(defmethod api-event [:system-setting-set :failed]
  [_ _ _ {{{:keys [error setting]} :message} :resp} state]
  (update-in state
             state/system-settings-path
             (fn [settings]
               (mapv #(if (= (:name %) (:name setting))
                        (assoc setting :error error)
                        %)
                     settings))))

(defmethod api-event [:user-plans :success]
  [target message status {:keys [resp]} state]
  (assoc-in state state/user-plans-path resp))

(defmethod api-event [:get-code-signing-keys :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (:project-settings-project-name state))
    state
    (assoc-in state state/project-osx-keys-path (:data resp))))

(defmethod api-event [:set-code-signing-keys :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (:project-settings-project-name state))
    state
    (-> state
        (assoc-in state/error-message-path nil)
        (state/add-flash-notification "Your key has been successfully added."))))

(defmethod post-api-event! [:set-code-signing-keys :success]
  [target message status {:keys [context]} previous-state current-state comms]
  (api/get-project-code-signing-keys (:project-name context) (:vcs-type context) (:api comms))
  ((:on-success context))
  (forms/release-button! (:uuid context) status))

(defmethod post-api-event! [:set-code-signing-keys :failed]
  [target message status {:keys [context] :as args} previous-state current-state comms]
  (put! (:errors comms) [:api-error args])
  (forms/release-button! (:uuid context) status))

(defmethod api-event [:delete-code-signing-key :success]
  [target message status {:keys [context]} state]
  (if-not (= (:project-name context) (:project-settings-project-name state))
    state
    (-> state
        (update-in state/project-osx-keys-path (partial remove #(and (:id %) ; figure out why we get nil id's
                                                                     (= (:id context) (:id %)))))
        (state/add-flash-notification "Your key has been successfully removed."))))

(defmethod post-api-event! [:delete-code-signing-keys :success]
  [target message status {:keys [context]} previous-state current-state comms]
  (forms/release-button! (:uuid context) status))

(defmethod post-api-event! [:delete-code-signing-keys :failed]
  [target message status {:keys [context] :as args} previous-state current-state comms]
  (put! (:errors comms) [:api-error args])
  (forms/release-button! (:uuid context) status))

(defmethod api-event [:org-settings-normalized :success]
  [target message status {:keys [resp]} state]
  (update-in state [:organization/by-vcs-type-and-name [(:vcs_type resp) (:name resp)]] merge resp))

(defmethod api-event [:org-plan-normalized :success]
  [target message status {:keys [resp]} state]
  (let [org (:org resp)]
    (update-in state [:organization/by-vcs-type-and-name [(:vcs_type org) (:name org)]] merge {:plan resp})))

(defmethod api-event [:enterprise-site-status :success]
  [target message status {:keys [resp]} state]
  (assoc-in state [:enterprise :site-status] resp))

(defmethod api-event [:merge-pull-request :success]
  [target message status {:keys [resp]} state]
  (state/add-flash-notification state (-> resp :message)))

(defmethod api-event [:merge-pull-request :failed]
  [target message status {:keys [resp]} state]
  (state/add-flash-notification state (-> resp :message)))

(defmethod api-event [:get-jira-projects :success]
  [target message status {:keys [resp]} state]
  (assoc-in state state/jira-projects-path resp))

(defmethod api-event [:get-jira-issue-types :success]
  [target message status {:keys [resp]} state]
  (assoc-in state state/jira-issue-types-path resp))

(defmethod post-api-event! [:create-jira-issue :success]
  [target message status {:keys [context]} previous-state current-state comms]
  (forms/release-button! (:uuid context) status)
  ((:on-success context)))

(defmethod post-api-event! [:create-jira-issue :failed]
  [target message status {:keys [context] :as args} previous-state current-state comms]
  (put! (:errors comms) [:api-error args])
  (forms/release-button! (:uuid context) status))
