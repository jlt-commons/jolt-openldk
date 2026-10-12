;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.project
  "Finds the Java classpath of a project. The first source that applies wins
  and is the whole classpath, nothing merges: an explicit classpath, then the
  :jolt-openldk/classpath or :jolt-openldk/deps key of deps.edn (either one,
  with the jars of the coordinates first), then the jars (and class files) in
  the project's jvm/ folder, then \".\". Resolving :jolt-openldk/deps can
  download jars and can throw jolt's resolution error."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [net.b12n.jolt.openldk.maven :as maven]))

(defn read-deps
  "The parsed deps.edn under `root`, or nil when there is none. A file that
  cannot be read throws ex-info naming the file and the reader's message."
  [root]
  (let [f (io/file root "deps.edn")]
    (when (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception e
             (throw (ex-info (str "cannot read " (.getPath f) ": " (ex-message e))
                             {:file (.getPath f)} e)))))))

(defn warm-classes
  "The class names listed under :jolt-openldk/warm in deps.edn, or []. The
  value must be absent, nil or a vector of strings, else this throws ex-info."
  [root]
  (let [v (:jolt-openldk/warm (read-deps root))]
    (when-not (or (nil? v) (and (vector? v) (every? string? v)))
      (throw (ex-info (str ":jolt-openldk/warm in " root "/deps.edn must be a vector of class name strings, got "
                           (pr-str v))
                      {:key :jolt-openldk/warm :value v})))
    (or v [])))

(defn- absolute [root entry]
  (if (.isAbsolute (io/file entry))
    entry
    (str root "/" entry)))

(defn- join [entries] (str/join ":" entries))

(defn- explicit-entries [explicit]
  (if (string? explicit)
    (vec (remove str/blank? (str/split explicit #":")))
    (vec explicit)))

(defn- jvm-dir-entries
  "Jars at the top level of root/jvm sorted by name, then the folder itself
  when it holds any .class file. Empty when there is no such folder."
  [root]
  (let [dir (io/file root "jvm")]
    (if (.isDirectory dir)
      (let [jars (->> (.listFiles dir)
                      (filter (fn [f] (and (.isFile f) (str/ends-with? (.getName f) ".jar"))))
                      (map (fn [f] (.getPath f)))
                      sort)
            classes? (some (fn [f] (and (.isFile f) (str/ends-with? (.getName f) ".class")))
                           (file-seq dir))]
        (vec (concat jars (when classes? [(.getPath dir)]))))
      [])))

(defn resolve-classpath
  "The classpath for the project at `root`. `explicit` is nil, a string or a
  seq of strings. Returns {:classpath \"a:b\" :entries [...] :source s}, where
  s is :explicit, :deps-edn, :jvm-dir or :default. Explicit entries are kept as
  given. Relative entries from deps.edn are resolved against `root`. A present
  :jolt-openldk/classpath wins even when empty, and must be a vector. A
  non-nil :jolt-openldk/deps also selects deps.edn: its coordinate jars come
  first, then the classpath entries, and the result gains :libs (lib to
  resolved coordinate). Resolving coordinates can download jars and can throw
  jolt's resolution error. An explicit classpath never resolves coordinates."
  [root explicit]
  (if (some? explicit)
    (let [entries (explicit-entries explicit)]
      {:classpath (if (string? explicit) explicit (join entries))
       :entries entries
       :source :explicit})
    (let [root (str root)
          deps (read-deps root)]
      (cond
        (or (contains? deps :jolt-openldk/classpath)
            (some? (:jolt-openldk/deps deps)))
        (let [cp? (contains? deps :jolt-openldk/classpath)
              v (:jolt-openldk/classpath deps)
              coords (:jolt-openldk/deps deps)]
          (when (and cp? (not (vector? v)))
            (throw (ex-info (str ":jolt-openldk/classpath in " root "/deps.edn must be a vector of paths, got "
                                 (if (nil? v) "nil" (.getName (class v))))
                            {:key :jolt-openldk/classpath :value v})))
          (when (some? coords)
            (try (maven/validate-deps coords)
                 (catch clojure.lang.ExceptionInfo e
                   (throw (ex-info (str/replace-first (ex-message e) "in deps.edn" (str "in " root "/deps.edn"))
                                   (ex-data e) e)))))
          (let [resolved (when (some? coords) (maven/resolve-jars root coords))
                entries (into (vec (:jars resolved))
                              (map (fn [e] (absolute root e)) v))]
            (cond-> {:classpath (join entries) :entries entries :source :deps-edn}
              (some? coords) (assoc :libs (:libs resolved)))))

        :else
        (let [entries (jvm-dir-entries root)]
          (if (seq entries)
            {:classpath (join entries) :entries entries :source :jvm-dir}
            {:classpath "." :entries ["."] :source :default}))))))
