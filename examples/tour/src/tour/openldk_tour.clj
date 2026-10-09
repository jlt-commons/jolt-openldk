(ns tour.openldk-tour
  "A walk through jolt-openldk: the JDK's own classes, then one of ours, then
  what an exception looks like from Clojure. Run with `bb tour`."
  (:require [net.b12n.jolt.openldk :as ldk]))

(defn- show [label v] (println (format "  %-34s %s" label (pr-str v))))

(defn -main [& _]
  (let [classes (or (System/getenv "TOUR_CLASSES") "examples/tour/target/classes")
        t0 (System/nanoTime)]
    (ldk/init! {:classpath classes})
    (println (format "OpenLDK up in %d ms (SBCL core, JDK class library from JAVA_HOME)"
                     (quot (- (System/nanoTime) t0) 1000000)))

    (println "\nthe JDK, statics")
    (show "Math.sqrt(2.0)" (ldk/call-static "java.lang.Math" "sqrt" "(D)D" 2.0))
    (show "Integer.toHexString(48879)"
          (ldk/call-static "java.lang.Integer" "toHexString" "(I)Ljava/lang/String;" 48879))
    (show "Character.isLetter('é')" (ldk/call-static "java.lang.Character" "isLetter" "(C)Z" \é))

    (println "\nthe JDK, objects")
    (ldk/with-ref [sb (ldk/new-object "java.lang.StringBuilder" "(Ljava/lang/String;)V" "jolt")]
      (ldk/release! (ldk/call sb "append" "(Ljava/lang/String;)Ljava/lang/StringBuilder;" " + "))
      (ldk/release! (ldk/call sb "append" "(Ljava/lang/String;)Ljava/lang/StringBuilder;" "OpenLDK"))
      (show "StringBuilder" (ldk/to-string sb))
      ;; reverse returns the same builder; the call hands back a second handle
      ;; to it, which with-ref releases like any other.
      (ldk/with-ref [r (ldk/call sb "reverse" "()Ljava/lang/StringBuilder;")]
        (show "  reversed" (ldk/to-string r))))
    (ldk/with-ref [m (ldk/new-object "java.util.TreeMap" "()V")]
      (doseq [w ["pear" "apple" "fig" "apple"]]
        (let [n (ldk/call m "get" "(Ljava/lang/Object;)Ljava/lang/Object;" w)]
          (ldk/call m "put" "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" w (inc (or n 0)))))
      (show "TreeMap word counts" (ldk/to-string m)))
    (ldk/with-ref [date (ldk/call-static "java.time.LocalDate" "of" "(III)Ljava/time/LocalDate;" 2026 10 9)
                   later (ldk/call date "plusDays" "(J)Ljava/time/LocalDate;" 100)]
      (show "2026-10-09 plus 100 days" (ldk/to-string later))
      (show "  day of week" (ldk/to-string (ldk/call later "getDayOfWeek" "()Ljava/time/DayOfWeek;"))))

    (println "\narrays")
    (show "Arrays.toString(int[])"
          (ldk/call-static "java.util.Arrays" "toString" "([I)Ljava/lang/String;" [3 1 2]))
    (ldk/with-ref [xs (ldk/new-array "D" [2.5 -1.0 9.75])]
      (ldk/call-static "java.util.Arrays" "sort" "([D)V" xs)
      (show "Arrays.sort on a double[] we hold" (ldk/array->vec xs)))
    (ldk/with-ref [s (ldk/new-object "java.lang.String" "(Ljava/lang/String;)V" "jolt,on,chez")
                   parts (ldk/call s "split" "(Ljava/lang/String;)[Ljava/lang/String;" ",")]
      (show "\"jolt,on,chez\".split(\",\")" (ldk/array->vec parts)))

    (println "\na class of our own")
    (ldk/with-ref [c (ldk/new-object "Counter" "(Ljava/lang/String;)V" "apples")]
      (show "add 3, add 4" [(ldk/call c "add" "(J)J" 3) (ldk/call c "add" "(J)J" 4)])
      (show "toString" (ldk/to-string c))
      (println "\nan exception, as Clojure sees it")
      (try (ldk/call c "add" "(J)J" -1)
           (catch Exception e
             (show "ex-message" (ex-message e))
             (show "ex-data" (ex-data e)))))

    (let [t0 (System/nanoTime) n 2000]
      (dotimes [i n] (ldk/call-static "java.lang.Math" "max" "(II)I" i 7))
      (println (format "\n%d calls to Math.max in %d ms" n (quot (- (System/nanoTime) t0) 1000000))))
    (shutdown-agents)))
