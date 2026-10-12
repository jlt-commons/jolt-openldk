;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.manifest
  "The bake manifest. It fingerprints a classpath (size and CRC32 of each file,
  or of each .class file under a directory) and says in plain words what changed
  since a core was baked. Pure apart from reading the classpath files."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- crc32 [^java.io.File f]
  (.getValue (doto (java.util.zip.CRC32.)
               (.update (java.nio.file.Files/readAllBytes (.toPath f))))))

(defn- file-print [f]
  {:size (.length f) :crc32 (crc32 f)})

(defn- relative [dir f]
  (str/replace (subs (.getPath f) (inc (count (.getPath dir)))) "\\" "/"))

(defn- entry-print [entry]
  (let [f (io/file entry)
        path (.getCanonicalPath f)]
    (cond
      (not (.exists f)) {:path path :missing true}
      (.isDirectory f)
      (let [root (io/file path)]
        {:path path
         :classes (into (sorted-map)
                        (for [c (file-seq root)
                              :when (and (.isFile c) (str/ends-with? (.getName c) ".class"))]
                          [(relative root c) (file-print c)]))})
      :else (assoc (file-print f) :path path))))

(defn fingerprint
  "One map per classpath entry, in order. A file gives {:path :size :crc32}, a
  directory gives {:path :classes {relative-path {:size :crc32}}} over its .class
  files, and a missing entry gives {:path :missing true}. Paths are canonical."
  [entries]
  (mapv entry-print entries))

(def ^:private volatile-keys [:built :bake-script])

(defn- shared-build [dist-manifest]
  (apply dissoc dist-manifest volatile-keys))

(defn build
  "The manifest stored beside a baked core."
  [{:keys [entries warm dist-manifest counts]}]
  {:classpath-entries (fingerprint entries)
   :warm (vec warm)
   :shared-build (shared-build dist-manifest)
   :counts counts})

(defn- entry-problems [b c]
  (let [p (:path b)]
    (cond
      (= b c) []
      (or (:missing b) (:missing c))
      [(str p " changed (" (if (:missing c) "now missing" "was missing") ")")]
      (or (:classes b) (:classes c))
      (let [bc (:classes b) cc (:classes c)]
        (vec (concat
              (for [k (sort (remove bc (keys cc)))] (str p ": class " k " added"))
              (for [k (sort (remove cc (keys bc)))] (str p ": class " k " removed"))
              (for [k (sort (keys bc)) :when (and (contains? cc k) (not= (bc k) (cc k)))]
                (str p ": class " k " changed (content)")))))
      (not= (:size b) (:size c)) [(str p " changed (size)")]
      :else [(str p " changed (content)")])))

(defn problems
  "Human-readable reasons the baked manifest no longer matches `current`, a map
  of :entries, :warm and :dist-manifest (build's input without :counts). Empty
  when valid."
  [baked {:keys [entries warm dist-manifest]}]
  (let [cur (fingerprint entries)
        bpaths (mapv :path (:classpath-entries baked))
        cpaths (mapv :path cur)
        bshared (:shared-build baked)
        cshared (shared-build dist-manifest)]
    (vec (concat
          (if (= bpaths cpaths)
            (mapcat entry-problems (:classpath-entries baked) cur)
            [(str "classpath changed: " (pr-str bpaths) " -> " (pr-str cpaths))])
          (when (not= (vec (:warm baked)) (vec warm))
            [(str "warm list changed: " (pr-str (:warm baked)) " -> " (pr-str (vec warm)))])
          (for [k (sort (distinct (concat (keys bshared) (keys cshared))))
                :when (not= (get bshared k) (get cshared k))]
            (str "shared build changed: " k " " (pr-str (get bshared k)) " -> "
                 (pr-str (get cshared k))))))))
