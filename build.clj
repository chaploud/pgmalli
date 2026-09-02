(ns build
  "clojure -T:build jar      target/pgmalli.jar and pom
   clojure -T:build install  into ~/.m2
   Publishing: clojure -X:deploy with CLOJARS_USERNAME and CLOJARS_PASSWORD (a deploy token)."
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.chaploud/pgmalli)
(defn- tag-version
  "The version is the release tag (v1.2.3 -> 1.2.3), passed in as PGMALLI_VERSION by the release
   workflow; a build outside a release is a snapshot of the last tag."
  []
  (or (System/getenv "PGMALLI_VERSION")
      (str (or (some-> (b/git-process {:git-args "describe --tags --abbrev=0 --match v*"}) (subs 1)) "0.0.0") "-SNAPSHOT")))

(def version (tag-version))
(def class-dir "target/classes")
(def jar-file "target/pgmalli.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_] (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir :lib lib :version version :basis @basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/chaploud/pgmalli"
                      :connection "scm:git:git://github.com/chaploud/pgmalli.git"
                      :developerConnection "scm:git:ssh://git@github.com/chaploud/pgmalli.git"
                      :tag (str "v" version)}
                :pom-data [[:description "Generate malli schemas from an applied PostgreSQL schema"]
                           [:url "https://github.com/chaploud/pgmalli"]
                           [:licenses [:license [:name "MIT License"] [:url "https://opensource.org/license/mit"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "built" jar-file "as" (str lib) version))

(defn install [_]
  (jar nil)
  (b/install {:basis @basis :lib lib :version version :jar-file jar-file :class-dir class-dir}))
