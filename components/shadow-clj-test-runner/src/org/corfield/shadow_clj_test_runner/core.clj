(ns org.corfield.shadow-clj-test-runner.core
  "Simple test runner for shadow-cljs :node-test builds"
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [polylith.clj.core.test-runner-contract.interface :as test-runner-contract]))

(defn shadow-conf-exist? [dir]
  (.exists (io/file dir "shadow-cljs.edn")))

(defn get-shadow-builds
  ([dir] (get-shadow-builds dir nil))
  ([dir target]
   (when (shadow-conf-exist? dir)
     (let [res (->> (io/file dir "shadow-cljs.edn")
                    slurp
                    edn/read-string
                    :builds
                    seq)]
       (if (some? target)
         (filter (fn [[_ v]] (= (:target v) target)) res)
         res)))))

(defn get-changed-bricks [runner-opts]
  (let [changed-bases (->> runner-opts :changes :changed-bases (map #(str "bases/" %)))
        changed-components (->> runner-opts :changes :changed-components (map #(str "components/" %)))]
    (into changed-bases changed-components)))

(defn find-shadow-cljs-bricks-dirs [changed-bricks target]
  (filter (fn [dir] (seq (get-shadow-builds dir target)))
          changed-bricks))

(defmulti execute-test
  (fn [target build dir] target))

(defmethod execute-test :node-test [_ [_ {:keys [output-to]}] dir]
  (shell/sh "node" output-to :dir dir))

(defmethod execute-test :karma [_ _ dir]
  (shell/sh "npx" "karma" "start" "--single-run" :dir dir))

(defn test-target?
  [target]
  (contains? (set (keys (methods execute-test))) target))

(defn find-all-test-targets
  [changed-bricks]
  (->> changed-bricks
       (mapcat get-shadow-builds)
       (map #(get-in % [1 :target]))
       (filter test-target?)
       set))

(defn run-shadow-tests
  [dir target aliases]
  (mapv (fn [build]
          (let [build-name (-> build first name)
                alias-str (str "-A" (apply str (interpose ":" (map name aliases))))
                compile-result (shell/sh "npx" "shadow-cljs" alias-str "compile" build-name :dir dir)]
            (print (:out compile-result))
            (flush)
            (when-not (empty? (:err compile-result))
              (binding [*out* *err*]
                (print (:err compile-result))
                (flush)))

            (if (zero? (:exit compile-result))
              (let [run-result (execute-test target build dir)]
                (print (:out run-result))
                (flush)
                (when-not (empty? (:err run-result))
                  (binding [*out* *err*]
                    (print (:err run-result))
                    (flush)))
                {:success (zero? (:exit run-result))
                 :exit (:exit run-result)})
              {:success false
               :exit (:exit compile-result)})))
        (get-shadow-builds dir target)))

(defn create [{:keys [project test-settings]}]
  (let [{:keys [paths]} project
        aliases (get test-settings :shadow-cljs-test-runner/aliases [:test])
        test-sources-present (-> paths :test seq)]
    (reify test-runner-contract/TestRunner
      (test-runner-name [_]
        "Polylith io.spiritt.shadow-cljs-test-runner")

      (test-sources-present? [_] test-sources-present)

      (tests-present? [_ runner-opts]
        (seq (find-all-test-targets (get-changed-bricks runner-opts))))

      (run-tests [_ runner-opts]
        (let [project-name (get-in runner-opts [:project :name])
              changed-bricks (get-changed-bricks runner-opts)
              discovered-targets (find-all-test-targets changed-bricks)]
          (doseq [target discovered-targets]
            (let [dirs (find-shadow-cljs-bricks-dirs changed-bricks target)]
              (doseq [dir dirs
                      result (run-shadow-tests dir target aliases)]
                (when-not (:success result)
                  (throw (Exception. (format "Tests failed for target %s in project: %s"
                                             target project-name))))))))))))