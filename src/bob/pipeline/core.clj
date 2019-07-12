;   This file is part of Bob.
;
;   Bob is free software: you can redistribute it and/or modify
;   it under the terms of the GNU General Public License as published by
;   the Free Software Foundation, either version 3 of the License, or
;   (at your option) any later version.
;
;   Bob is distributed in the hope that it will be useful,
;   but WITHOUT ANY WARRANTY; without even the implied warranty of
;   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;   GNU General Public License for more details.
;
;   You should have received a copy of the GNU General Public License
;   along with Bob. If not, see <http://www.gnu.org/licenses/>.

(ns bob.pipeline.core
  (:require [ring.util.response :as res]
            [manifold.deferred :as d]
            [failjure.core :as f]
            [bob.pipeline.internals :as p]
            [bob.util :as u]
            [bob.resource.internals :as ri]
            [bob.states :as states]
            [bob.pipeline.db :as db]
            [bob.resource.db :as rdb]))

(defn create
  "Creates a new pipeline.

  Takes the following:
  - group
  - name
  - a list of steps
  - a map of environment vars
  - a list of resources
  - a starting Docker image.

  The group defines a logical grouping of pipelines like dev or staging
  and the name is the name of the pipeline like build or test.
  Steps is a list of strings of the commands that need to be executed in sequence.
  The steps are assumed to be valid shell commands.

  Returns Ok or the error if any."
  [group name pipeline-steps vars resources image]
  (d/let-flow [pipeline (u/name-of group name)
               evars    (map #(vector (clojure.core/name (first %))
                                      (last %)
                                      pipeline)
                             vars)
               result   (f/attempt-all [_ (u/unsafe! (db/insert-pipeline states/db
                                                                         {:name  pipeline
                                                                          :image image}))
                                        _ (u/unsafe! (doseq [resource resources]
                                                       (let [{:keys [name params type provider]} resource]
                                                         (rdb/insert-resource states/db
                                                                              {:name     name
                                                                               :type     type
                                                                               :pipeline pipeline
                                                                               :provider provider})
                                                         (ri/add-params name params pipeline))))
                                        _ (when (not (empty? evars))
                                            (u/unsafe! (db/insert-evars states/db
                                                                        {:evars evars})))
                                        _ (u/unsafe! (doseq [step pipeline-steps]
                                                       (let [{cmd                   :cmd
                                                              needs-resource        :needs_resource
                                                              {artifact-name :name
                                                               artifact-path :path} :produces_artifact} step]
                                                         (db/insert-step states/db
                                                                         {:cmd               cmd
                                                                          :needs_resource    needs-resource
                                                                          :produces_artifact artifact-name
                                                                          :artifact_path     artifact-path
                                                                          :pipeline          pipeline}))))]
                          (u/respond "Ok")
                          (f/when-failed [err]
                            (do (u/unsafe! (db/delete-pipeline states/db {:name pipeline}))
                                (res/bad-request {:message (f/message err)}))))]
    result))

(defn start
  "Asynchronously starts a pipeline in a group by name.
  Returns Ok or any starting errors."
  [group name]
  (d/let-flow [pipeline (u/name-of group name)
               result   (f/attempt-all [image (p/image-of pipeline)
                                        steps (u/unsafe! (db/ordered-steps states/db
                                                                           {:pipeline pipeline}))
                                        steps (map #(update-in % [:cmd] u/clob->str) steps)
                                        vars  (u/unsafe! (->> (db/evars-by-pipeline states/db
                                                                                    {:pipeline pipeline})
                                                              (map #(hash-map
                                                                      (keyword (:key %)) (:value %)))
                                                              (into {})))]
                          (do (p/exec-steps image steps pipeline vars)
                              (u/respond "Ok"))
                          (f/when-failed [err]
                            (res/bad-request
                              {:message (f/message err)})))]
    result))

(defn stop
  "Stops a running pipeline with SIGKILL.
  Returns Ok or any stopping errors."
  [group name number]
  (d/let-flow [pipeline (u/name-of group name)
               result   (p/stop-pipeline pipeline number)]
    (if (nil? result)
      (res/not-found {:message "Pipeline not running"})
      (u/respond result))))

(defn status
  "Fetches the status of a particular run of a pipeline.
  Returns the status or 404."
  [group name number]
  (d/let-flow [pipeline (u/name-of group name)
               status   (u/unsafe! (-> (db/status-of states/db
                                                     {:pipeline pipeline
                                                      :number   number})
                                       (:status)
                                       (keyword)))]
    (if (nil? status)
      (res/not-found {:message "No such pipeline"})
      (u/respond status))))

(defn remove-pipeline
  "Removes a pipeline.
  Returns Ok or 404."
  [group name]
  (d/let-flow [pipeline (u/name-of group name)
               _        (u/unsafe! (db/delete-pipeline states/db
                                                       {:name pipeline}))]
    (u/respond "Ok")))

(defn logs-of
  "Handler to fetch logs for a particular run of a pipeline.
  Take the starting offset to read and the number of lines to read after it.
  Returns logs as a list."
  [group name number offset lines]
  (d/let-flow [pipeline (u/name-of group name)
               result   (p/pipeline-logs pipeline number offset lines)]
    (u/respond result)))

(defn running-pipelines
  "Collects all pipeline names that have status 'running'.
  Returns pipeline names as a list."
  []
  (d/let-flow [pipeline-names (u/unsafe! (->> (db/running-pipelines states/db)
                                              (map #(:name %))))]
    (if (empty? pipeline-names)
      (res/not-found {:message "No running pipelines"})
      (u/respond (->> pipeline-names
                      (map #(clojure.string/split % #":"))
                      (map #(hash-map :group (first %)
                                      :name (second %))))))))

(comment
  (create "test"
          "test"
          ["echo hello"
           {:needs_resource "source"
            :cmd            "ls"}]
          {}
          [{:name     "source"
            :type     "external"
            :provider "git"
            :params   {:repo   "https://github.com/bob-cd/bob",
                       :branch "master"}}
           {:name     "source2"
            :type     "external"
            :provider "git"
            :params   {:repo   "https://github.com/lispyclouds/clj-docker-client",
                       :branch "master"}}]
          "busybox:musl")
  (start "test" "test")
  (status "dev" "test" 1)
  (logs-of "test" "test" 1 0 20)
  (remove-pipeline "test" "test"))
