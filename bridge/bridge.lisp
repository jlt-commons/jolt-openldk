;;; -*- Mode: LISP; Syntax: COMMON-LISP; Package: OPENLDK -*-
;;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;;;
;;; jolt-openldk's bridge into OpenLDK: one C-callable entry point, LDK_ENTRY.
;;; It is loaded into OpenLDK's own image and calls OpenLDK internals, so it
;;; carries OpenLDK's licence.
;;;
;;; A request is a Lisp form in a string, written by net.b12n.jolt.openldk.wire;
;;; the reply is another, malloc'd, which the caller frees with ldk_free.
;;; Everything that can go wrong comes back in the reply rather than unwinding
;;; into C, because nothing above this frame is Lisp.
;;;
;;;   request                                   reply
;;;   (:setup "classpath")                      (:ok (:void))
;;;   (:static "a/B" "name" "(II)I" (v ...))    (:ok v) | (:throw ...) | (:error ...)
;;;   (:new "a/B" "(I)V" (v ...))               (:ok (:ref id "a/B"))
;;;   (:invoke id "name" "()Ljava/lang/String;" (v ...))
;;;   (:main "a/B" ("arg" ...))                 (:ok (:void))
;;;   (:class-name id)                          (:ok (:s "a.B"))
;;;   (:new-array "I" (v ...))                  (:ok (:ref id "[I"))
;;;   (:elements id)                            (:ok (:vec v ...)), or for a primitive
;;;                                             array (:ok (:raw "I" address n)),
;;;                                             malloc'd, freed by the caller
;;;   (:length id)                              (:ok (:i n))
;;;   (:proxy ("java/util/Comparator") fn-id)   (:ok (:ref id "jolt-openldk.Proxy..."))
;;;   (:super id "name" "()Ljava/lang/String;" (v ...))   (:ok v), the superclass's version
;;;   (:subclass "a.B" "()V" (v ...) fn-id ("run" ...))
;;;                                             (:ok (:ref id "jolt-openldk.Subclass..."))
;;;   (:get-field id "x")                       (:ok v), by the field's type
;;;   (:set-field id "x" v)                     (:ok (:void))
;;;   (:get-static "a.B" "X")                   (:ok v), the class initialized first
;;;   (:set-static "a.B" "X" v)                 (:ok (:void))
;;; Any request may instead get (:exit n): Java called System.exit or
;;; Runtime.halt with status n, and the process carries on.
;;;
;;; The other direction, Java calling Clojure, goes through the C function
;;; pointer ldk_upcall that jolt installs: (:call fn-id "compare" "(..)I"
;;; (v ...)) out, and (:ok v), (:throw "message" error-id) or (:error "msg")
;;; back.
;;;   (:release id)                             (:ok (:void))
;;;
;;; Values: (:i n) (:d x) (:nan) (:inf 1|-1) (:z :true|:false) (:c code) (:s "text")
;;; (:null) (:ref id) (:array (v ...)) (:raw-array carrier address n) on the way
;;; in, the same plus (:ref id "class") and (:void) on the way out. A
;;; (:raw-array ...) names elements the client wrote into its own memory, read
;;; before the call returns (%ldk-raw-values). A Java exception is
;;; (:throw "java.lang.Class" "message"|:null "toString()"), with a fifth
;;; element, a callback's error id, when one of its causes is the
;;; RuntimeException a callback's throw became (%ldk-callback-cause-id).

(in-package :openldk)

;;; --- fixes carried for OpenLDK -------------------------------------------
;;; OpenLDK functions replaced whole, until the pinned OpenLDK has the
;;; fixes. When the pin moves, compare each with its original (named below) and
;;; drop it once upstream matches.

;; From src/openldk.lisp. Upstream interns an instance method's stub in the
;; class loader's package, but instance methods dispatch on a generic function
;; in :openldk (see %build-method-definition). For a class from a user loader,
;; which is every class on the classpath jolt-openldk sets, the stub went onto
;; an unrelated generic function and the lazy compile stub stayed. Its next
;; call looped forever: %compile-method returns at once for a method already
;; marked :done, and the stub then invokes itself again.
(defun %install-jit-error-stub (class method method-key)
  "Install a function that signals on invocation, so a failed JIT compile
doesn't leave a self-recursing compilation stub behind.  Uses plain Lisp
errors (not Java exceptions) to avoid triggering further class loading
during error handling."
  (if (static-p method)
      (let ((fn-symbol (intern (%method-fn-name class method)
                               (%class-loader-package class))))
        (when (fboundp fn-symbol)
          (setf (symbol-function fn-symbol)
                (lambda (&rest args)
                  (declare (ignore args))
                  (internal-error "JIT compilation failed for ~A" method-key)))))
      ;; Instance method: replace the lazy stub's method on the :openldk
      ;; generic function with one that throws. CL:EVAL, not %eval, so the JIT
      ;; isn't re-entered.
      (let* ((fn-symbol (intern (%method-fn-name class method) :openldk))
             (class-sym (intern (slot-value class 'name)
                                (%class-specializer-package class)))
             (this-sym (intern "this" :openldk))
             (n-params (count-parameters (slot-value method 'descriptor)))
             (param-names (loop for i from 1 upto n-params
                                collect (gensym (format nil "P~A-" i)))))
        (when (find-class class-sym nil)
          (eval `(defmethod ,fn-symbol ((,this-sym ,class-sym) ,@param-names)
                   (declare (ignore ,this-sym ,@param-names))
                   (internal-error "JIT compilation failed for ~A" ,method-key)))))))

(defun %jmod-zip-offset (raw)
  "Where the zip archive inside the jmod RAW counts its offsets from: 4 when
from just after the JM header, as OpenJDK's jmods do, 0 when from the start of
the file, as 30 of the 84 jmods in GraalVM CE 25.0.1 do. Tells them apart by
where the central directory's signature (PK, 1, 2) is. Anything it doesn't
recognise, a zip64 archive included, gets 4, upstream's assumption."
  (let* ((len (file-length raw))
         (tail-len (min len 65557))
         (tail (make-array tail-len :element-type '(unsigned-byte 8))))
    (file-position raw (- len tail-len))
    (read-sequence tail raw)
    (flet ((u32 (i)
             (logior (aref tail i) (ash (aref tail (+ i 1)) 8)
                     (ash (aref tail (+ i 2)) 16) (ash (aref tail (+ i 3)) 24)))
           (central-directory-at-p (pos)
             (and (<= (+ pos 4) len)
                  (let ((sig (make-array 4 :element-type '(unsigned-byte 8))))
                    (file-position raw pos)
                    (read-sequence sig raw)
                    (equalp sig #(#x50 #x4B 1 2))))))
      (let ((eocd (loop for i from (- tail-len 22) downto 0
                        when (and (= (aref tail i) #x50) (= (aref tail (+ i 1)) #x4B)
                                  (= (aref tail (+ i 2)) 5) (= (aref tail (+ i 3)) 6))
                          return i)))
        (prog1
            (let ((cd (and eocd (u32 (+ eocd 16)))))
              (cond ((or (null cd) (= cd #xFFFFFFFF)) 4)
                    ((central-directory-at-p (+ cd 4)) 4)
                    ((central-directory-at-p cd) 0)
                    (t 4)))
          (file-position raw 4))))))

;; From src/classpath.lisp. Upstream always skips 4 bytes, so a jmod laid out
;; the GraalVM way is read 4 bytes past its central directory and fails zip's
;; signature assertion. OpenLDK reads the whole JDK from jmods/ when there is
;; one, so on such a JDK that broke classes in those modules and every lookup
;; of a class that isn't anywhere.
(defun open-jmod-zipfile (pathname)
  "Open a JMOD file as a zipfile, skipping the 4-byte JMOD magic header."
  (let ((raw (open pathname :element-type '(unsigned-byte 8))))
    ;; Verify JMOD magic: JM\1\0
    (let ((magic (make-array 4 :element-type '(unsigned-byte 8))))
      (read-sequence magic raw)
      (unless (and (= (aref magic 0) #x4A) ; J
                   (= (aref magic 1) #x4D) ; M
                   (= (aref magic 2) #x01)
                   (= (aref magic 3) #x00))
        (close raw)
        (error "Not a valid JMOD file: ~A" pathname)))
    ;; Close RAW if either step fails, which upstream doesn't.
    (let ((opened nil))
      (unwind-protect
           (prog1 (zip::open-zipfile-from-stream
                   (make-instance 'jmod-offset-stream :inner raw
                                                      :offset (%jmod-zip-offset raw)))
             (setf opened t))
        (unless opened (close raw))))))

;; From src/strings.lisp. Upstream stores a String's bytes unsigned, so a
;; character from U+0080 to U+00FF is 128..255 where Java's byte[] holds
;; -128..-1, and UTF16 bytes likewise. JDK code depends on the sign:
;; String.encodeUTF8 copies a LATIN1 value unchanged when
;; StringCoding.hasNegatives finds no negative byte, so "hé" encoded to UTF-8
;; as the Latin-1 bytes, and String.equals compares the bytes, so a string
;; passed in wasn't equal to the same text built in Java. Every string this
;; bridge passes in is a jstring. This is loaded before the core initializes
;; OpenLDK (build.sh), so no String is made with the old encoder.
(defun %java-byte (octet)
  "OCTET (0..255) as the signed value a Java byte holds."
  (if (> octet 127) (- octet 256) octet))

(defun %string-value-bytes (value)
  "Encode Lisp string VALUE per Java compact-string rules.  Returns
(values BYTES CODER): LATIN1 bytes with coder 0 when every character fits
in a byte, otherwise UTF-16LE code units (with surrogate pairs for
supplementary characters) with coder 1.  The bytes are signed, as in a Java
byte[]."
  (if (every (lambda (c) (< (char-code c) 256)) value)
      (let ((bytes (make-array (length value) :element-type '(signed-byte 8))))
        (dotimes (i (length value))
          (setf (aref bytes i) (%java-byte (char-code (char value i)))))
        (values bytes 0))
      (let* ((units (loop for c across value
                          sum (if (> (char-code c) #xFFFF) 2 1)))
             (bytes (make-array (* 2 units) :element-type '(signed-byte 8)))
             (j 0))
        (flet ((put-unit (unit)
                 (setf (aref bytes j) (%java-byte (logand unit #xFF)))
                 (setf (aref bytes (1+ j)) (%java-byte (ash unit -8)))
                 (incf j 2)))
          (loop for c across value
                for code = (char-code c)
                do (if (> code #xFFFF)
                       (let ((v (- code #x10000)))
                         (put-unit (logior #xD800 (ash v -10)))
                         (put-unit (logior #xDC00 (logand v #x3FF))))
                       (put-unit code))))
        (values bytes 1))))

;; From src/native/bytearray.lisp. Upstream prints the raw bytes through
;; code-char, which a signed byte breaks, so printing any String with such a
;; character would fail (in a condition report, say). LSTRING reads them.
(defmethod print-object ((str |java/lang/String|) out)
  (print-unreadable-object (str out :type t)
    (format out "~S" (lstring str))))

;; Missing from src/native/threads.lisp upstream. OpenLDK's natives are Lisp,
;; so there is no native library to load: report it as not loaded, which the
;; JDK treats as an absent library that BootLoader.loadLibrary ignores. On
;; macOS ClassLoaderHelper.loadLibraryOnlyIfPresent() is false, so the JDK
;; calls this without checking for the file first, and the first use of
;; java.nio.file, or MessageDigest.getInstance through java.security.Security,
;; failed with an undefined function. Linux checks for the file and never
;; gets here.
(defun |jdk/internal/loader/NativeLibraries.load(Ljdk/internal/loader/NativeLibraries$NativeLibraryImpl;Ljava/lang/String;ZZ)|
    (impl name is-builtin throw-exception-if-fail)
  (declare (ignore impl name is-builtin throw-exception-if-fail))
  0)

;; From src/zlib-native.lisp. Upstream loads the system zlib as "libz.so.1",
;; the Linux name, so on macOS the first java.util.zip native call (CRC32,
;; Deflater, Inflater, GZIP) signalled "Error opening shared object", a Lisp
;; error Java code can't catch. macOS's zlib is libz.1.dylib, in dyld's
;; shared cache.
(defun %load-zlib ()
  (unless *zlib-loaded*
    (sb-alien:load-shared-object #+darwin "libz.1.dylib" #-darwin "libz.so.1")
    (setf *zlib-loaded* t)))

;; Missing from src/native/frames.lisp upstream: the native behind
;; Clock.currentInstant, so Instant.now() signalled an undefined function on
;; every platform. Date.toString reaches it too, through CLDR's GMT-format
;; fallback, in a time zone with no display name (GMT+07:00, say). Answers as
;; HotSpot does: nanoseconds from the given epoch offset to now, or -1 when the
;; offset is 2^32 seconds or more away.
(defun |jdk/internal/misc/VM.getNanoTimeAdjustment(J)| (offset-in-seconds)
  (assert (eq org.shirakumo.precise-time:precise-time-units-per-second
              1000000000))
  (multiple-value-bind (universal-time nanoseconds)
      (org.shirakumo.precise-time:get-precise-time)
    (let ((diff (- (local-time:timestamp-to-unix
                    (local-time:universal-to-timestamp universal-time))
                   offset-in-seconds)))
      (if (< (- (expt 2 32)) diff (expt 2 32))
          (+ (* diff 1000000000) nanoseconds)
          -1))))

;;; --- descriptors ---------------------------------------------------------

(defun %ldk-field-end (desc start)
  "The index just past the field descriptor that starts at START in DESC, or
NIL when there is none there. A field descriptor is one of B C D F I J S Z,
Lpkg/Name; with a slashed, non-empty name, or [ followed by one."
  (when (< start (length desc))
    (case (char desc start)
      ((#\B #\C #\D #\F #\I #\J #\S #\Z) (1+ start))
      (#\[ (%ldk-field-end desc (1+ start)))
      (#\L (let ((semi (position #\; desc :start start)))
             (when (and semi
                        (> semi (1+ start))
                        (not (find-if (lambda (c) (find c ".[()")) desc
                                      :start (1+ start) :end semi)))
               (1+ semi))))
      (t nil))))

(defun %ldk-check-field (desc)
  "DESC, if it is exactly one field descriptor; an error naming it otherwise."
  (let ((end (%ldk-field-end desc 0)))
    (unless (and end (= end (length desc)))
      (error "bad type descriptor ~S: want one of B C D F I J S Z, ~
              Lpkg/Name; with slashes, or [ followed by one of those" desc))
    desc))

(defun %ldk-split-descriptor (desc)
  "\"(ILjava/lang/String;[I)V\" -> (values (\"I\" \"Ljava/lang/String;\" \"[I\") \"V\").
Every part is checked, so a typo fails here with the descriptor in the message."
  (let ((close (position #\) desc))
        (params '()))
    (unless (and (plusp (length desc)) (char= (char desc 0) #\() close)
      (error "malformed method descriptor ~S" desc))
    (let ((i 1))
      (loop while (< i close)
            do (let ((end (%ldk-field-end desc i)))
                 (unless (and end (<= end close))
                   (error "malformed method descriptor ~S at position ~D" desc i))
                 (push (subseq desc i end) params)
                 (setf i end))))
    (let ((ret (subseq desc (1+ close))))
      (unless (string= ret "V") (%ldk-check-field ret))
      (values (nreverse params) ret))))

(defun %ldk-method-key (name desc)
  "OpenLDK names a method by its name and parameter descriptor, without the
return type: add(II)."
  (concatenate 'string name (subseq desc 0 (1+ (position #\) desc)))))

;;; --- handles -------------------------------------------------------------

(defvar *ldk-handles* (make-hash-table))
(defvar *ldk-next-handle* 0)
(defvar *ldk-handle-lock* (sb-thread:make-mutex :name "jolt-openldk handles"))

(defvar *ldk-borrowed* :none
  "While a callback's arguments are converted, a list collecting the handles
made for them, released once Clojure has replied. :none outside a callback.")

(defun %ldk-handle (object)
  (let ((id (sb-thread:with-mutex (*ldk-handle-lock*)
              (let ((id (incf *ldk-next-handle*)))
                (setf (gethash id *ldk-handles*) object)
                id))))
    (unless (eq *ldk-borrowed* :none) (push id *ldk-borrowed*))
    id))

(defun %ldk-deref (id)
  (multiple-value-bind (object found)
      (sb-thread:with-mutex (*ldk-handle-lock*) (gethash id *ldk-handles*))
    (unless found (error "no live Java object behind handle ~A" id))
    object))

(defun %ldk-release (id)
  (unless (sb-thread:with-mutex (*ldk-handle-lock*) (remhash id *ldk-handles*))
    (error "handle ~A is not live: released twice, or never issued" id)))

;;; --- classes and methods ---------------------------------------------------

(defun %ldk-class (classname)
  (let ((class (classload (substitute #\/ #\. classname))))
    (unless class (error "class not found: ~A" classname))
    (%clinit class)
    class))

(defun %ldk-static-symbol (class key)
  "The function for static method KEY on CLASS, walking up to superclasses."
  (when class
    (let* ((pkg (if (and (slot-boundp class 'ldk-loader) (slot-value class 'ldk-loader))
                    (loader-package (slot-value class 'ldk-loader))
                    (find-package "OPENLDK.SYSTEM")))
           (sym (find-symbol (format nil "~A.~A" (name class) key) pkg)))
      (if (and sym (fboundp sym))
          sym
          (%ldk-static-symbol (gethash (super class) *ldk-classes-by-bin-name*) key)))))

(defun %ldk-call-static (classname key &rest args)
  (let ((sym (or (%ldk-static-symbol (%ldk-class classname) key)
                 (error "no static method ~A on ~A" key classname))))
    (apply sym args)))

;;; --- values in ---------------------------------------------------------------

(defparameter *ldk-boxes*
  '(("Ljava/lang/Object;" :i :d :z :c :s)
    ("Ljava/io/Serializable;" :i :d :z :c :s)
    ("Ljava/lang/Comparable;" :i :d :z :c :s)
    ("Ljava/lang/Number;" :i :d)
    ("Ljava/lang/Long;" :i)
    ("Ljava/lang/Integer;" :i)
    ("Ljava/lang/Double;" :d)
    ("Ljava/lang/Boolean;" :z)
    ("Ljava/lang/Character;" :c)
    ("Ljava/lang/String;" :s)
    ("Ljava/lang/CharSequence;" :s))
  "Which Clojure scalars a reference parameter takes, by its descriptor. A
scalar becomes the box Java would give it (an integer is a Long, or an
Integer for an Integer parameter) and a string a java.lang.String, so it can
only go where that class fits. Any other reference parameter takes a JavaRef
or nil, and %ldk-ref-arg checks the JavaRef's class.")

(defun %ldk-noun (tag)
  (case tag
    (:i "an integer") (:d "a double") (:z "a boolean") (:c "a char")
    (:s "a string") (:array "a vector") (:ref "an object") (:null "null")
    (:raw-array "a primitive array")
    (t (string-downcase (string tag)))))

(defun %ldk-wire-double (value)
  (ecase (first value)
    (:d (coerce (second value) 'double-float))
    (:nan (- sb-ext:double-float-positive-infinity sb-ext:double-float-positive-infinity))
    (:inf (if (minusp (second value))
              sb-ext:double-float-negative-infinity
              sb-ext:double-float-positive-infinity))))

(defun %ldk-wire-char (value)
  ;; A Clojure char is a code point; a Java char is one UTF-16 unit, so
  ;; anything past U+FFFF does not fit one.
  (%ldk-fit (second value) '(unsigned-byte 16)
            "char (a code point past U+FFFF needs two Java chars)"))

(defun %ldk-primitive-arg (param tag value)
  (let ((kind (char param 0)))
    (flet ((refuse () (error "~A for parameter ~A" (%ldk-noun tag) param)))
      (case tag
        (:i (let ((n (second value)))
              (case kind
                (#\I (%ldk-fit n '(signed-byte 32) "int"))
                (#\S (%ldk-fit n '(signed-byte 16) "short"))
                (#\B (%ldk-fit n '(signed-byte 8) "byte"))
                (#\C (%ldk-fit n '(unsigned-byte 16) "char"))
                (#\J (%ldk-fit n '(signed-byte 64) "long"))
                (#\F (coerce n 'single-float))
                (#\D (coerce n 'double-float))
                (t (refuse)))))
        (:d (case kind
              (#\D (%ldk-wire-double value))
              (#\F (coerce (%ldk-wire-double value) 'single-float))
              (t (refuse))))
        (:z (if (char= kind #\Z) (ecase (second value) (:true 1) (:false 0)) (refuse)))
        (:c (if (char= kind #\C) (%ldk-wire-char value) (refuse)))
        (t (refuse))))))

(defun %ldk-boxed-arg (param tag value)
  (unless (member tag (cdr (assoc param *ldk-boxes* :test #'string=)))
    (error "~A cannot be passed for parameter ~A~:[~;; build the array with new-array and pass its handle~]"
           (%ldk-noun tag) param (eq tag :array)))
  (ecase tag
    (:s (jstring (second value)))
    (:i (if (string= param "Ljava/lang/Integer;")
            (%ldk-call-static "java/lang/Integer" "valueOf(I)"
                              (%ldk-fit (second value) '(signed-byte 32) "int, boxed for an Integer parameter"))
            ;; Long.valueOf would wrap a bignum into an impossible Long.
            (%ldk-call-static "java/lang/Long" "valueOf(J)"
                              (%ldk-fit (second value) '(signed-byte 64) "long, boxed for a reference parameter"))))
    (:d (%ldk-call-static "java/lang/Double" "valueOf(D)" (%ldk-wire-double value)))
    (:z (%ldk-call-static "java/lang/Boolean" "valueOf(Z)" (ecase (second value) (:true 1) (:false 0))))
    (:c (%ldk-call-static "java/lang/Character" "valueOf(C)" (%ldk-wire-char value)))))

(defun %ldk-ref-arg (param object)
  "OBJECT, a JavaRef's object, if Java's instanceof says it fits reference
parameter PARAM. Arrays follow Java's covariance (a String[] is an Object[],
an int[] is not). A class that cannot be loaded is left to the call to report."
  (let* ((element (string-left-trim "[" param))
         (binary (and (char= (char element 0) #\L) (subseq element 1 (1- (length element)))))
         (fits (cond
                 ((and binary (null (classload binary))) t)
                 ((char= (char param 0) #\[) (%array-assignable-to-p object param))
                 ((string= binary "java/lang/Object") t)
                 (t (eql 1 (%instanceof-check object binary
                                              (intern binary (class-package binary))))))))
    (unless fits
      (error "a ~A for ~:[~;array ~]parameter ~A"
             (%ldk-class-dotted object) (char= (char param 0) #\[) param))
    object))

(defun %ldk-arg (param value)
  "Turn wire VALUE into what OpenLDK passes for a parameter of type PARAM."
  (let ((tag (if (member (first value) '(:nan :inf)) :d (first value))))
    (case (char param 0)
      ;; An array parameter: a vector built with its component type (so
      ;; (:array ((:i 1))) is an int[] for [I, a long[] for [J, and nested
      ;; vectors make [[I), an array handle, or null.
      (#\[ (case tag
             (:null nil)
             (:array (%ldk-make-array (subseq param 1) (second value)))
             (:raw-array (%ldk-make-array (subseq param 1) value))
             (:ref (%ldk-ref-arg param (%ldk-deref (second value))))
             (t (error "~A for array parameter ~A" (%ldk-noun tag) param))))
      (#\L (case tag
             (:null nil)
             (:ref (%ldk-ref-arg param (%ldk-deref (second value))))
             (t (%ldk-boxed-arg param tag value))))
      (t (%ldk-primitive-arg param tag value)))))

(defun %ldk-fit (n type what)
  (unless (typep n type)
    (error "~A does not fit a Java ~A" n what))
  n)

(defun %ldk-args (desc values)
  (let ((params (%ldk-split-descriptor desc)))
    (unless (= (length params) (length values))
      (error "~A takes ~D argument~:P, got ~D" desc (length params) (length values)))
    (mapcar #'%ldk-arg params values)))

;;; --- arrays ------------------------------------------------------------------
;;;
;;; A Java array is OpenLDK's JAVA-ARRAY struct: a java/lang/Class for the
;;; component and a Lisp vector. Elements are stored as OpenLDK's own compiled
;;; code stores them: char[] holds Lisp characters (castore does code-char),
;;; boolean[] holds 0 and 1, byte[] holds whatever was written, signed from
;;; Java code and unsigned from JSTRING, which is why reads normalise.

(defparameter *ldk-primitive-names*
  '(("int" . #\I) ("long" . #\J) ("short" . #\S) ("byte" . #\B) ("char" . #\C)
    ("float" . #\F) ("double" . #\D) ("boolean" . #\Z)))

(defun %ldk-array-component-name (array)
  "\"int\", \"java.lang.String\" or \"[I\": Class.getName of the component."
  (%ldk-lstring (|getName()| (%array-component-class array))))

(defun %ldk-raw-values (carrier address n)
  "The N elements a (:raw-array CARRIER ADDRESS N) value names, as the wire
values the text path would have sent for them, so %ldk-make-array checks them
and words its errors exactly as it does for a vector. The client never
narrows a value before the check. The carriers: :i8 :i16 :i32 :i64 signed
integers (a vector's integers as :i64, a typed array's at its own width),
:f64 doubles, :f32 floats (a float-array), :z8 booleans as 0 or not, :c32
chars as int32 code points."
  (unless (and (integerp n) (>= n 0))
    (error "a raw array needs a length of 0 or more, got ~D" n))
  (when (and (plusp n) (eql address 0))
    (error "a raw array of ~D elements cannot be at address 0" n))
  (let ((sap (sb-sys:int-sap address)))
    (flet ((each (width read)
             (loop for i below n collect (funcall read (* i width)))))
      (case carrier
        (:i8 (each 1 (lambda (o) (list :i (sb-sys:signed-sap-ref-8 sap o)))))
        (:i16 (each 2 (lambda (o) (list :i (sb-sys:signed-sap-ref-16 sap o)))))
        (:i32 (each 4 (lambda (o) (list :i (sb-sys:signed-sap-ref-32 sap o)))))
        (:i64 (each 8 (lambda (o) (list :i (sb-sys:signed-sap-ref-64 sap o)))))
        (:f32 (each 4 (lambda (o) (list :d (coerce (sb-sys:sap-ref-single sap o) 'double-float)))))
        (:f64 (each 8 (lambda (o) (list :d (sb-sys:sap-ref-double sap o)))))
        (:z8 (each 1 (lambda (o) (list :z (if (zerop (sb-sys:sap-ref-8 sap o)) :false :true)))))
        (:c32 (each 4 (lambda (o) (list :c (sb-sys:signed-sap-ref-32 sap o)))))
        (t (error "unknown raw array carrier ~S" carrier))))))

(defun %ldk-raw-contents (kind carrier address n)
  "The N elements of a (:raw-array CARRIER ADDRESS N) value as the data of a
Java array of primitive KIND, read straight from memory. NIL when an element
doesn't fit KIND, or CARRIER isn't one a KIND argument takes, so the caller
can take the element-by-element path, which says which element and why."
  (let ((sap (sb-sys:int-sap address))
        (out (make-array n)))
    (macrolet ((fill-from (width read type convert)
                 `(dotimes (i n out)
                    (let ((x (,read sap (* i ,width))))
                      (unless (typep x ',type) (return nil))
                      (setf (svref out i) ,convert)))))
      (handler-case
          (case carrier
            ((:i8 :i16 :i32 :i64)
             (macrolet ((each-width (type convert)
                          `(ecase carrier
                             (:i8 (fill-from 1 sb-sys:signed-sap-ref-8 ,type ,convert))
                             (:i16 (fill-from 2 sb-sys:signed-sap-ref-16 ,type ,convert))
                             (:i32 (fill-from 4 sb-sys:signed-sap-ref-32 ,type ,convert))
                             (:i64 (fill-from 8 sb-sys:signed-sap-ref-64 ,type ,convert)))))
               (case kind
                 (#\I (each-width (signed-byte 32) x))
                 (#\J (each-width (signed-byte 64) x))
                 (#\S (each-width (signed-byte 16) x))
                 (#\B (each-width (signed-byte 8) x))
                 (#\C (each-width (unsigned-byte 16) (code-char x)))
                 (#\F (each-width integer (coerce x 'single-float)))
                 (#\D (each-width integer (coerce x 'double-float))))))
            (:f32 (case kind
                    (#\F (fill-from 4 sb-sys:sap-ref-single single-float x))
                    (#\D (fill-from 4 sb-sys:sap-ref-single single-float (coerce x 'double-float)))))
            (:f64 (case kind
                    (#\D (fill-from 8 sb-sys:sap-ref-double double-float x))
                    (#\F (fill-from 8 sb-sys:sap-ref-double double-float (coerce x 'single-float)))))
            (:z8 (when (char= kind #\Z)
                   (fill-from 1 sb-sys:sap-ref-8 (unsigned-byte 8) (if (zerop x) 0 1))))
            (:c32 (when (char= kind #\C)
                    (fill-from 4 sb-sys:signed-sap-ref-32 (unsigned-byte 16) (code-char x)))))
        ;; A float trap (a double too big for a float) or a bad address:
        ;; the element-by-element path meets the same thing and reports it
        ;; with the element's index. Anything else is a bug here, and shows.
        ((or arithmetic-error sb-sys:memory-fault-error) () nil)))))

(defun %ldk-make-array (component values)
  "A Java array whose component has descriptor COMPONENT (I, Ljava/lang/String;,
[I ...), holding wire VALUES converted as arguments of that type. VALUES is a
list of wire values or a (:raw-array carrier address n) form."
  (%ldk-check-field component)
  (when (eq (first values) :raw-array)
    (destructuring-bind (carrier address n) (rest values)
      (let ((data (and (= (length component) 1) (integerp n) (plusp n) (not (eql address 0))
                       (%ldk-raw-contents (char component 0) carrier address n))))
        (when data
          (return-from %ldk-make-array
            (make-java-array-default :component-class (%bin-type-name-to-class component)
                                     :data data)))))
    (setf values (apply #'%ldk-raw-values (rest values))))
  (let ((elements (loop for v in values
                        for i from 0
                        collect (handler-case (%ldk-arg component v)
                                  (error (c)
                                    (error "element ~D of the vector for [~A: ~A" i component c))))))
    (make-java-array :component-class (%bin-type-name-to-class component)
                     :size (length elements)
                     :initial-contents (if (char= (char component 0) #\C)
                                           (mapcar #'code-char elements)
                                           elements))))

(defun %ldk-array (id)
  (let ((object (%ldk-deref id)))
    (unless (java-array-p object)
      (error "handle ~A is a ~A, not an array" id (%ldk-class-dotted object)))
    object))

(defun %ldk-element (kind x)
  (case kind
    (#\Z (%ldk-boolean x))
    ((#\I #\J #\S) (list :i x))
    (#\B (list :i (if (> x 127) (- x 256) x)))
    (#\C (list :c (if (characterp x) (char-code x) x)))
    ((#\F #\D) (%ldk-double x))
    (t (%ldk-object x))))

(defvar *ldk-raw-out* nil
  "The reply buffer %ldk-raw-elements malloc'd for the request in flight, so
LDK_ENTRY can free it when the reply that names it is never sent.")

(defun %ldk-raw-elements (kind data)
  "(:raw letter address n): DATA's N elements written at their Java width into
malloc'd memory, for the caller to copy and free with ldk_free."
  (let* ((n (length data))
         (width (ecase kind ((#\Z #\B) 1) ((#\C #\S) 2) ((#\I #\F) 4) ((#\J #\D) 8))))
    (if (zerop n)
        (list :raw (string kind) 0 0)
        (let* ((buffer (sb-alien:make-alien (sb-alien:unsigned 8) (* n width)))
               (sap (sb-alien:alien-sap buffer)))
          (setf *ldk-raw-out* buffer)
          (dotimes (i n)
            ;; OpenLDK's Array.newInstance fills even a primitive array with
            ;; NIL, which its compiled Java code reads as zero, so NIL is
            ;; written as the type's zero (false, 0, 0.0, U+0000) too.
            (let ((x (or (aref data i) 0))
                  (o (* i width)))
              (ecase kind
                (#\Z (setf (sb-sys:sap-ref-8 sap o) (if (eql x 0) 0 1)))
                (#\B (setf (sb-sys:sap-ref-8 sap o) (logand x #xFF)))
                (#\C (setf (sb-sys:sap-ref-16 sap o) (if (characterp x) (char-code x) x)))
                (#\S (setf (sb-sys:signed-sap-ref-16 sap o) x))
                (#\I (setf (sb-sys:signed-sap-ref-32 sap o) x))
                (#\J (setf (sb-sys:signed-sap-ref-64 sap o) x))
                (#\F (setf (sb-sys:sap-ref-single sap o) (coerce x 'single-float)))
                (#\D (setf (sb-sys:sap-ref-double sap o) (coerce x 'double-float))))))
          (list :raw (string kind) (sb-sys:sap-int sap) n)))))

(defun %ldk-elements (array)
  "Every element. A primitive array's come back as raw memory
(%ldk-raw-elements). Other elements are tagged and come back as values or
handles, as any returned object does; a nested array is a handle of its own."
  (let ((kind (or (cdr (assoc (%ldk-array-component-name array) *ldk-primitive-names*
                              :test #'string=))
                  #\L)))
    (if (char= kind #\L)
        (cons :vec (map 'list (lambda (x) (%ldk-element kind x)) (java-array-data array)))
        (%ldk-raw-elements kind (java-array-data array)))))

;;; --- values out --------------------------------------------------------------

(defun %ldk-lstring (string)
  "OpenLDK's LSTRING, with each byte read as unsigned. A Java byte[] holds
signed bytes, so a String built by Java code (toUpperCase, concatenation of a
char) stores e.g. É as -55, and LSTRING hands that to CODE-CHAR unmasked.
Strings built by JSTRING store unsigned bytes, which is why only some fail."
  (let* ((value (slot-value string '|value|))
         (data (map 'vector (lambda (b) (logand b #xFF)) (java-array-data value)))
         (coder (or (ignore-errors (slot-value string '|coder|)) 0)))
    (if (zerop coder)
        (map 'string #'code-char data)
        (let ((units (loop for k below (floor (length data) 2)
                           collect (+ (aref data (* k 2)) (ash (aref data (1+ (* k 2))) 8))))
              (out (make-string-output-stream)))
          (loop while units
                do (let ((u (pop units)))
                     (if (and (<= #xD800 u #xDBFF) units (<= #xDC00 (first units) #xDFFF))
                         (write-char (code-char (+ #x10000 (ash (- u #xD800) 10)
                                                   (- (pop units) #xDC00)))
                                     out)
                         (write-char (code-char u) out))))
          (get-output-stream-string out)))))

(defun %ldk-double (x)
  (let ((x (coerce x 'double-float)))
    (cond ((sb-ext:float-nan-p x) '(:nan))
          ((sb-ext:float-infinity-p x) (list :inf (if (plusp x) 1 -1)))
          (t (list :d x)))))

(defun %ldk-boolean (value)
  "A Java boolean is 0 or 1 here; the wire says :true or :false."
  (list :z (if (and value (not (eql value 0))) :true :false)))

(defun %ldk-class-dotted (object)
  (if (java-array-p object)
      (%array-type-name-for-component (%ldk-array-component-name object))
      (substitute #\. #\/ (string (class-name (class-of object))))))

(defun %ldk-object (object)
  "Strings and boxed scalars come back as values, anything else as a handle."
  (cond ((null object) '(:null))
        ((typep object '|java/lang/String|) (list :s (%ldk-lstring object)))
        ((stringp object) (list :s object))
        ((typep object '|java/lang/Boolean|) (%ldk-boolean (|booleanValue()| object)))
        ((typep object '|java/lang/Character|) (list :c (|charValue()| object)))
        ((or (typep object '|java/lang/Double|) (typep object '|java/lang/Float|))
         (%ldk-double (|doubleValue()| object)))
        ((or (typep object '|java/lang/Long|) (typep object '|java/lang/Integer|)
             (typep object '|java/lang/Short|) (typep object '|java/lang/Byte|))
         (list :i (|longValue()| object)))
        (t (list :ref (%ldk-handle object) (%ldk-class-dotted object)))))

(defun %ldk-result (ret value)
  (case (char ret 0)
    (#\V '(:void))
    (#\Z (%ldk-boolean value))
    ((#\I #\J #\S #\B) (list :i value))
    (#\C (list :c value))
    ((#\F #\D) (%ldk-double value))
    (t (%ldk-object value))))

;;; --- callbacks: Java calling Clojure ----------------------------------------
;;;
;;; A proxy is an instance of a class made here at run time, the way OpenLDK
;;; makes classes for Java lambdas (%ensure-dynamic-lambda-class): its
;;; superclasses are java/lang/Object and the interfaces, so CHECKCAST and
;;; INSTANCEOF pass, and each abstract method is a method on the generic
;;; function OpenLDK dispatches that Java method through. The method sends the
;;; call to Clojure as text through ldk_upcall and converts the reply by the
;;; method's return type.

(defparameter *ldk-object-methods*
  '("equals(Ljava/lang/Object;)Z" "hashCode()I" "toString()Ljava/lang/String;")
  "Methods an interface may redeclare abstract (Comparator does equals) that
every object already has from java.lang.Object.")

(defvar *ldk-proxy-classes* (make-hash-table :test 'equal))
(defvar *ldk-proxy-lock* (sb-thread:make-mutex :name "jolt-openldk proxies"))

(defun %ldk-class-symbol (binary)
  "The CLOS class symbol OpenLDK uses for loaded class BINARY (slashed)."
  (%ldk-class binary)
  (let ((sym (or (let ((s (find-symbol binary :openldk))) (and s (find-class s nil) s))
                 (let ((s (find-symbol binary (class-package binary)))) (and s (find-class s nil) s)))))
    (or sym (error "no CLOS class for ~A" binary))))

(defun %ldk-abstract-methods (interfaces)
  "The (name descriptor) pairs a class implementing INTERFACES (binary names)
must define: the abstract methods they and their superinterfaces leave
unimplemented, less Object's. Resolved as for a subclass
(%ldk-interface-methods), so a default that overrides an abstract method
inherited along another path is left to run."
  (dolist (i interfaces)
    (unless (interface-p (%ldk-class i))
      (error "~A is a class, not an interface" (substitute #\. #\/ i))))
  (let ((seen (make-hash-table :test 'equal)))
    (dolist (m *ldk-object-methods*)
      (let ((open (position #\( m)))
        (setf (gethash (%ldk-method-key (subseq m 0 open) (subseq m open)) seen) t)))
    (loop for (nil name desc m) in (%ldk-interface-methods interfaces seen)
          when (abstract-p m) collect (list name desc))))

(defun %ldk-method-gf (name desc)
  "The generic function OpenLDK dispatches Java method NAME DESC through."
  (intern (lispize-method-name (concatenate 'string name desc)) :openldk))

(defun %ldk-define-upcall-methods (class-sym methods this-first)
  "For each (name desc) in METHODS, a method on its generic function,
specialised on CLASS-SYM, that calls Clojure (%ldk-upcall), with this as the
first argument when THIS-FIRST."
  (loop for (name desc) in methods
        for gf = (%ldk-method-gf name desc)
        for params = (loop for i below (length (%ldk-split-descriptor desc))
                           collect (intern (format nil "P~D" i) :openldk))
        do (unless (and (fboundp gf) (typep (fdefinition gf) 'generic-function))
             (ensure-generic-function gf :generic-function-class 'java-generic-function
                                         :lambda-list (cons '|this| params)))
           (eval `(defmethod ,gf ((|this| ,class-sym) ,@params)
                    (%ldk-upcall |this| ,name ,desc (list ,@params) ,this-first)))))

(defun %ldk-proxy-class (interfaces)
  "The proxy class for INTERFACES (slashed names), made once and cached."
  (sb-thread:with-mutex (*ldk-proxy-lock*)
    (or (gethash interfaces *ldk-proxy-classes*)
        (let* ((class-sym (intern (format nil "jolt-openldk/Proxy[~{~A~^,~}]" interfaces) :openldk))
               (methods (%ldk-abstract-methods interfaces))
               (supers (cons '|java/lang/Object| (mapcar #'%ldk-class-symbol interfaces))))
          (eval `(defclass ,class-sym ,supers ((fn-id :initarg :fn-id))))
          ;; getClass() looks the object's class up by this name: answer with
          ;; Object's Class, since no Java class stands behind this one.
          (setf (gethash (symbol-name class-sym) *java-classes-by-bin-name*)
                (%get-java-class-by-bin-name "java/lang/Object" t))
          ;; Object.toString would print that as java.lang.Object@...; say
          ;; what the object is instead.
          (let ((label (format nil "jolt-openldk proxy for ~{~A~^, ~}"
                               (mapcar (lambda (i) (substitute #\. #\/ i)) interfaces))))
            (eval `(defmethod |toString()| ((|this| ,class-sym))
                     (jstring (format nil "~A #~D" ,label (slot-value |this| 'fn-id))))))
          (%ldk-define-upcall-methods class-sym methods nil)
          (setf (gethash interfaces *ldk-proxy-classes*) class-sym)))))

(defun %ldk-new-proxy (interfaces fn-id)
  (let* ((binaries (mapcar (lambda (i) (substitute #\/ #\. i)) interfaces))
         (object (make-instance (%ldk-proxy-class binaries) :fn-id fn-id)))
    (list :ref (%ldk-handle object) (%ldk-class-dotted object))))

;;; --- subclasses: a Java class extended from Clojure ---------------------------
;;;
;;; The proxy mechanism with a class for superclass: a CLOS class made here
;;; whose superclass is the Java class's, with a method on OpenLDK's generic
;;; function for each method defined, upcalling with this first. The instance
;;; runs a superclass constructor through invoke-special, as a Java subclass's
;;; constructor would.

(defvar *ldk-subclass-classes* (make-hash-table :test 'equal))
(defvar *ldk-subclass-count* 0)
(defvar *ldk-subclass-supers* (make-hash-table :test 'eq)
  "Each generated subclass's CLOS class symbol, to its superclass's binary name.")

(defun %ldk-flag-p (thing flag)
  (not (zerop (logand flag (slot-value thing 'access-flags)))))

(defun %ldk-superclass-chain (binary)
  "Class BINARY and its superclasses, nearest first, loaded."
  (loop for c = (%ldk-class binary) then (and (super c) (%ldk-class (super c)))
        while c collect c))

(defun %ldk-superinterfaces (name)
  "Every interface interface NAME extends, directly or not, by binary name."
  (let ((out '()))
    (labels ((walk (n)
               (loop for super across (or (slot-value (%ldk-class n) 'interfaces) #())
                     unless (member super out :test #'string=)
                       do (push super out) (walk super))))
      (walk name))
    out))

(defun %ldk-instance-method-p (m)
  "M is an instance method something can override: not static, a constructor
or initializer, or private."
  (not (or (static-p m) (char= (char (name m) 0) #\<) (%ldk-flag-p m #x2))))

(defun %ldk-interface-methods (interfaces seen)
  "Entries (key name desc method), one per key not in SEEN, for the instance
methods of INTERFACES (binary names) and their superinterfaces, the
declaration that wins as in Java: one in an interface that another declaring
interface extends is overridden by it, so a default there beats the abstract
one it overrides, and the key is abstract only if what remains is."
  (let ((all '()) (candidates (make-hash-table :test 'equal)) (order '()) (out '()))
    (dolist (i interfaces)
      (dolist (n (cons i (%ldk-superinterfaces i)))
        (pushnew n all :test #'string=)))
    (dolist (iname (reverse all))
      (let ((methods (slot-value (%ldk-class iname) 'methods)))
        (flet ((key-of (m) (%ldk-method-key (name m) (descriptor m))))
          (loop for m across methods
                for key = (key-of m)
                when (and (%ldk-instance-method-p m) (not (gethash key seen))
                          ;; A covariant redeclaration comes with javac's bridge
                          ;; method, a default under the same key that calls the
                          ;; real one; picked, it would call itself.
                          (not (and (%ldk-flag-p m #x40)
                                    (find-if (lambda (o) (and (not (%ldk-flag-p o #x40))
                                                              (string= (key-of o) key)))
                                             methods))))
                  do (unless (gethash key candidates) (push key order))
                     (push (cons iname m) (gethash key candidates))))))
    (dolist (key (reverse order))
      (let* ((cands (gethash key candidates))
             ;; Drop a declaration some other candidate's interface overrides.
             (live (remove-if (lambda (c)
                                (some (lambda (other)
                                        (and (not (eq other c))
                                             (member (car c) (%ldk-superinterfaces (car other))
                                                     :test #'string=)))
                                      cands))
                              cands))
             (pick (or (find-if-not (lambda (c) (abstract-p (cdr c))) live) (first live))))
        (push (list key (name (cdr pick)) (descriptor (cdr pick)) (cdr pick)) out)))
    (nreverse out)))

(defun %ldk-class-method-table (binary)
  "Every instance method an object of class BINARY has, as a list of
(key name desc method), one per KEY (OpenLDK's method key), the declaration
that wins as in Java. The superclass chain comes first, nearest class winning,
then the interfaces for keys no class declares (%ldk-interface-methods).
Private methods are left out, since they override nothing and nothing
overrides them."
  (let ((seen (make-hash-table :test 'equal))
        (out '())
        (chain (%ldk-superclass-chain binary)))
    (dolist (c chain)
      (loop for m across (slot-value c 'methods)
            for key = (%ldk-method-key (name m) (descriptor m))
            when (and (%ldk-instance-method-p m) (not (gethash key seen)))
              do (setf (gethash key seen) t)
                 (push (list key (name m) (descriptor m) m) out)))
    (append (nreverse out)
            (%ldk-interface-methods
             (loop for c in chain append (coerce (or (slot-value c 'interfaces) #()) 'list))
             seen))))

(defun %ldk-private-method-named (binary name desc)
  "A private method NAME (with DESC, when given) declared on BINARY or a superclass."
  (loop for c in (%ldk-superclass-chain binary)
        thereis (find-if (lambda (m) (and (%ldk-flag-p m #x2) (not (static-p m)) (string= (name m) name)
                                          (or (null desc) (string= (descriptor m) desc))))
                         (slot-value c 'methods))))

(defun %ldk-static-method-named (binary name desc)
  "A static method NAME (with DESC, when given) on BINARY or a superclass."
  (loop for c in (%ldk-superclass-chain binary)
        thereis (find-if (lambda (m) (and (static-p m) (string= (name m) name)
                                          (or (null desc) (string= (descriptor m) desc))))
                         (slot-value c 'methods))))

(defun %ldk-subclass-methods (binary keys)
  "The (name desc) pairs a subclass of BINARY defines for map KEYS: every
abstract method, plus what each key names. Signals for a key that names
nothing overridable, or a bare name matching several overloads."
  (let* ((table (%ldk-class-method-table binary))
         (dotted (substitute #\. #\/ binary))
         (overridable (remove-if (lambda (e) (%ldk-flag-p (fourth e) #x10)) table))
         (abstract (remove-if-not (lambda (e) (abstract-p (fourth e))) overridable))
         (chosen (copy-list abstract)))
    (flet ((why-not (m what)
             (error "~A~A is ~A, so a subclass of ~A can't override it"
                    (name m) (descriptor m) what dotted)))
      (dolist (key keys)
        (let* ((open (position #\( key))
               (name (if open (subseq key 0 open) key))
               (desc (and open (subseq key open)))
               (named (remove-if-not (lambda (e) (and (string= (second e) name)
                                                      (or (null desc) (string= (third e) desc))))
                                     table))
               (open-named (intersection named overridable))
               (abstract-named (intersection named abstract)))
          (cond
            (abstract-named nil)
            ((and open-named (or desc (null (rest open-named))))
             (pushnew (first open-named) chosen))
            (open-named
             (error "~A matches several methods of ~A; pick one by descriptor: ~{~A~^, ~}"
                    name dotted (mapcar (lambda (e) (concatenate 'string (second e) (third e))) open-named)))
            (named (why-not (fourth (first named)) "final"))
            ((%ldk-private-method-named binary name desc)
             (why-not (%ldk-private-method-named binary name desc) "private"))
            ((%ldk-static-method-named binary name desc)
             (why-not (%ldk-static-method-named binary name desc) "static"))
            (t (error "no method ~A on ~A" key dotted))))))
    (mapcar (lambda (e) (list (second e) (third e)))
            (remove-duplicates chosen :key #'first :test #'string= :from-end t))))

(defun %ldk-subclass-class (binary methods)
  "The generated subclass of BINARY defining METHODS, made once per set."
  (sb-thread:with-mutex (*ldk-proxy-lock*)
    (let ((cache-key (cons binary (sort (mapcar (lambda (m) (apply #'concatenate 'string m)) methods)
                                        #'string<))))
      (or (gethash cache-key *ldk-subclass-classes*)
          (let* ((super-sym (%ldk-class-symbol binary))
                 (class-sym (intern (format nil "jolt-openldk/Subclass~D[~A]"
                                            (incf *ldk-subclass-count*) binary)
                                    :openldk)))
            (eval `(defclass ,class-sym (,super-sym) ((fn-id :initarg :fn-id))))
            ;; getClass() looks the object's class up by this name: answer
            ;; with the superclass's Class, since no Java class stands behind
            ;; this one.
            (setf (gethash (symbol-name class-sym) *java-classes-by-bin-name*)
                  (%get-java-class-by-bin-name binary t))
            (%ldk-define-upcall-methods class-sym methods t)
            (setf (gethash class-sym *ldk-subclass-supers*) binary)
            (setf (gethash cache-key *ldk-subclass-classes*) class-sym))))))

(defun %ldk-new-subclass (classname desc values fn-id keys)
  (when (or (zerop (length classname)) (char= (char classname 0) #\[)
            (assoc classname *ldk-primitive-names* :test #'string=))
    (error "~A is not a class, so it can't be subclassed" classname))
  (let* ((binary (substitute #\/ #\. classname))
         (dotted (substitute #\. #\/ binary))
         (class (%ldk-class dotted)))
    (cond ((interface-p class) (error "~A is an interface; use implement" dotted))
          ((%ldk-flag-p class #x4000) (error "~A is an enum, so it can't be subclassed" dotted))
          ((%ldk-flag-p class #x10) (error "~A is final, so it can't be subclassed" dotted)))
    (let ((ctor (find-if (lambda (m) (and (string= (name m) "<init>") (string= (descriptor m) desc)))
                         (slot-value class 'methods))))
      (unless ctor (error "no constructor ~A on ~A" desc dotted))
      (when (%ldk-flag-p ctor #x2) (error "constructor ~A of ~A is private" desc dotted)))
    (let* ((args (%ldk-args desc values))
           (class-sym (%ldk-subclass-class binary (%ldk-subclass-methods binary keys)))
           (object (make-instance class-sym :fn-id fn-id)))
      (%ldk-construct object binary (%ldk-class-symbol binary) desc args)
      (list :ref (%ldk-handle object) (%ldk-class-dotted object)))))

(defun %ldk-call-super (object name desc values)
  "The superclass's NAME DESC on OBJECT, an instance of a generated subclass,
through invoke-special so its override is skipped."
  (let ((binary (gethash (class-name (class-of object)) *ldk-subclass-supers*)))
    (unless binary
      (error "call-super needs an object made by subclass, not a ~A" (%ldk-class-dotted object)))
    (let* ((dotted (substitute #\. #\/ binary))
           (key (%ldk-method-key name desc))
           (entry (find key (%ldk-class-method-table binary) :key #'first :test #'string=)))
      (unless entry (error "no method ~A~A on ~A" name desc dotted))
      (when (abstract-p (fourth entry)) (error "~A~A is abstract in ~A" name desc dotted))
      (let ((args (%ldk-args desc values)))
        (%ldk-result (nth-value 1 (%ldk-split-descriptor desc))
                     (invoke-special (%ldk-method-gf name desc)
                                     (%ldk-class-symbol binary)
                                     (cons object args)))))))

(defun %ldk-sap-utf8 (sap)
  "The NUL-terminated UTF-8 string at SAP."
  (let ((n (loop for i from 0 until (zerop (sb-sys:sap-ref-8 sap i)) finally (return i)))
        (octets nil))
    (setf octets (make-array n :element-type '(unsigned-byte 8)))
    (dotimes (i n) (setf (aref octets i) (sb-sys:sap-ref-8 sap i)))
    (sb-ext:octets-to-string octets :external-format :utf-8)))

(defun %ldk-c-upcall (text)
  "Send TEXT to Clojure through ldk_upcall and return its reply text. The
reply was malloc'd on the far side (ldk_strdup) and is freed here."
  (let ((fp (sb-alien:extern-alien "ldk_upcall" sb-sys:system-area-pointer)))
    (when (zerop (sb-sys:sap-int fp))
      (error "no Clojure callback is installed (ldk_upcall is null)"))
    (let ((octets (sb-ext:string-to-octets text :external-format :utf-8 :null-terminate t)))
      (sb-alien:with-alien ((out sb-sys:system-area-pointer))
        (setf out (sb-sys:int-sap 0))
        (let ((rc (sb-sys:with-pinned-objects (octets)
                    (sb-alien:alien-funcall
                     (sb-alien:sap-alien fp (function sb-alien:int sb-sys:system-area-pointer
                                                      (* sb-sys:system-area-pointer)))
                     (sb-sys:vector-sap octets) (sb-alien:addr out)))))
          (when (or (/= rc 0) (zerop (sb-sys:sap-int out)))
            (error "the Clojure callback trampoline failed (status ~D)" rc))
          (unwind-protect (%ldk-sap-utf8 out)
            (sb-alien:alien-funcall
             (sb-alien:extern-alien "ldk_free" (function sb-alien:void sb-sys:system-area-pointer))
             out)))))))

(defun %ldk-throw-runtime (message)
  (%ldk-class "java/lang/RuntimeException")
  (let ((exc (%make-java-instance "java/lang/RuntimeException")))
    (|<init>(Ljava/lang/String;)| exc (jstring message))
    (error (%lisp-condition exc))))

(defun %ldk-upcall (proxy name desc args &optional this-first)
  "Run Clojure fn behind PROXY for Java method NAME DESC with ARGS. Objects
among ARGS reach Clojure as borrowed handles, released when it replies. With
THIS-FIRST, PROXY itself goes first, borrowed the same way: a subclass's fns
take this."
  (multiple-value-bind (params ret) (%ldk-split-descriptor desc)
    ;; Only the handles made for ARGS are borrowed. The Clojure fn runs with
    ;; collection off, so objects it gets from its own calls into Java are its
    ;; to keep, like any other JavaRef.
    (let* ((borrowed '())
           (wire-args (let ((*ldk-borrowed* '()))
                        (prog1 (append (and this-first (list (%ldk-object proxy)))
                                       (mapcar #'%ldk-result params args))
                          (setf borrowed *ldk-borrowed*)))))
      (unwind-protect
           (let* ((*ldk-borrowed* :none)
                  (reply (%ldk-read-request
                          (%ldk-c-upcall
                           (%ldk-print-reply
                            (list :call (slot-value proxy 'fn-id) name desc wire-args))))))
             (ecase (first reply)
               (:ok (if (string= ret "V")
                        nil
                        ;; A result that does not fit the return type is the
                        ;; callback's fault, so Java gets an exception it can
                        ;; catch, not a Lisp error.
                        (let ((converted nil) (problem nil))
                          (handler-case (setf converted (%ldk-arg ret (second reply)))
                            (error (c) (setf problem (princ-to-string c))))
                          (if problem
                              (%ldk-throw-runtime
                               (format nil "jolt-openldk callback ~A~A returned a bad result: ~A"
                                       name desc problem))
                              converted))))
               (:throw (%ldk-throw-runtime
                        (format nil "~A [jolt-openldk callback error ~D]" (second reply) (third reply))))
               (:error (%ldk-throw-runtime (second reply)))))
        (dolist (id borrowed)
          (sb-thread:with-mutex (*ldk-handle-lock*) (remhash id *ldk-handles*)))))))

;;; --- exit: Java's System.exit without ending the host --------------------------
;;;
;;; System.exit, Runtime.exit and Runtime.halt reach Shutdown.exit(I) or
;;; Shutdown.halt(I); both are replaced, before Shutdown logs, takes its lock or
;;; runs hooks, by a function that signals instead. On a thread inside a bridge
;;; request the signal is LDK-JAVA-EXIT, a serious-condition but not an error,
;;; so nothing between it and %ldk-handle-request stops it: OpenLDK compiles
;;; Java's catch and finally as handlers for Java's own conditions, and %clinit
;;; would wrap any error in ExceptionInInitializerError. On a thread Java
;;; started, no request is waiting to hear about it, so the signal is an error,
;;; which OpenLDK's thread runner catches: that thread ends, with a line on
;;; stderr, and the process carries on.

(define-condition ldk-java-exit (serious-condition)
  ((status :initarg :status :reader ldk-exit-status))
  (:report (lambda (c s) (format s "Java called exit(~D)" (ldk-exit-status c)))))

(define-condition ldk-java-exit-on-thread (error)
  ((status :initarg :status :reader ldk-exit-status))
  (:report (lambda (c s) (format s "Java called exit(~D) on a thread of its own; that thread ended"
                                 (ldk-exit-status c)))))

(defvar *ldk-in-request* nil
  "T on a thread while it serves a bridge request (%ldk-handle-request).")

(defun %ldk-signal-exit (status)
  (error (if *ldk-in-request* 'ldk-java-exit 'ldk-java-exit-on-thread) :status status))

;; The table applies when OpenLDK installs Shutdown's methods; setting the
;; functions too covers a core where that already happened.
(dolist (name '("exit" "halt"))
  (let ((key (format nil "java/lang/Shutdown.~A(I)" name)))
    (setf (gethash (concatenate 'string key "V") *native-overrides*) #'%ldk-signal-exit)
    (let ((sym (find-symbol key :openldk)))
      (when (and sym (fboundp sym))
        (setf (symbol-function sym) #'%ldk-signal-exit)))))

;;; --- requests ----------------------------------------------------------------

(defun %ldk-invoke (receiver name desc values)
  (when (null receiver) (error "invoke on a null receiver"))
  (let* ((args (%ldk-args desc values))
         (gf (find-symbol (%ldk-method-key name desc) :openldk)))
    (unless (and gf (fboundp gf))
      (error "no instance method ~A~A on ~A" name desc (%ldk-class-dotted receiver)))
    (%ldk-result (nth-value 1 (%ldk-split-descriptor desc))
                 (apply gf (%box-if-native receiver) args))))

(defun %ldk-construct (object binary owner desc args)
  "Run constructor DESC of class OWNER (a CLOS class symbol) on OBJECT, a
fresh instance whose Java class is BINARY's."
  (let ((java-class (%get-java-class-by-bin-name binary t)))
    (when (and java-class (slot-exists-p object '|clazz|))
      (setf (slot-value object '|clazz|) java-class)))
  (invoke-special (intern (%ldk-method-key "<init>" desc) :openldk) owner (cons object args)))

(defun %ldk-new (classname desc values)
  (let* ((binary (substitute #\/ #\. classname))
         (args (%ldk-args desc values))
         (class (%ldk-class binary))
         (object (%make-java-instance binary)))
    (declare (ignore class))
    (%ldk-construct object binary (class-name (class-of object)) desc args)
    (list :ref (%ldk-handle object) (substitute #\. #\/ binary))))

(defun %ldk-main (classname args)
  (let* ((class (%ldk-class classname))
         (sym (or (%ldk-static-symbol class "main([Ljava/lang/String;)")
                  (error "no main method on ~A" classname)))
         (argv (make-java-array
                :component-class (%get-java-class-by-bin-name "java/lang/String")
                :initial-contents (mapcar #'jstring args))))
    (%eval (list sym argv))
    '(:void)))

;;; --- fields --------------------------------------------------------------------
;;;
;;; A field is found in OpenLDK's class metadata, never by its slot: every
;;; instance also carries its class's static slots (they're :class allocated),
;;; so a slot existing says nothing about which kind of field it is. An
;;; instance field is a slot on the object, named by %resolve-field-slot, which
;;; knows a shadowing field's own slot; a static one is a slot on the class's
;;; +static-<binary>+ instance.

(defun %ldk-find-field (binary name static-p)
  "Field NAME as Java resolves it from class BINARY, and the binary name of the
class declaring it, or NIL. An instance lookup walks loaded superclasses; a
static one loads and walks the class, then its superinterfaces, then its
superclass (JVMS 5.4.3.2)."
  (labels ((walk (b)
             (let ((class (and b (if static-p
                                     (classload b)
                                     (gethash b *ldk-classes-by-bin-name*)))))
               (when class
                 (let ((f (find-if (lambda (f) (and f (string= (name f) name))) (fields class))))
                   (when f (return-from walk (values f b))))
                 (when static-p
                   (loop for i across (or (slot-value class 'interfaces) #())
                         do (multiple-value-bind (f d) (walk i)
                              (when f (return-from walk (values f d))))))
                 (walk (super class))))))
    (walk binary)))

(defun %ldk-field-owner (declaring) (substitute #\. #\/ declaring))

(defun %ldk-instance-field (object name op)
  "Instance field NAME of OBJECT for request OP (:get-field or :set-field),
and the binary name of the class declaring it. A subclass object's fields are
its superclass's."
  (when (java-array-p object)
    (if (string= name "length")
        (error "arrays have no fields; use array-length")
        (error "no field ~A on ~A" name (%ldk-class-dotted object))))
  (let ((sym (class-name (class-of object))))
    (multiple-value-bind (field declaring)
        (%ldk-find-field (or (gethash sym *ldk-subclass-supers*) (symbol-name sym)) name nil)
      (unless field (error "no field ~A on ~A" name (%ldk-class-dotted object)))
      (when (%ldk-flag-p field #x8)
        (error "~A is a static field of ~A; use ~A" name (%ldk-field-owner declaring)
               (if (eq op :get-field) "get-static" "set-static!")))
      (values field declaring))))

(defun %ldk-check-writable (field name declaring)
  (when (%ldk-flag-p field #x10)
    (error "~A is final on ~A" name (%ldk-field-owner declaring))))

(defun %ldk-get-field (object name)
  (multiple-value-bind (field declaring) (%ldk-instance-field object name :get-field)
    (%ldk-result (descriptor field) (slot-value object (%resolve-field-slot declaring name)))))

(defun %ldk-set-field (object name value)
  (multiple-value-bind (field declaring) (%ldk-instance-field object name :set-field)
    (%ldk-check-writable field name declaring)
    (setf (slot-value object (%resolve-field-slot declaring name)) (%ldk-arg (descriptor field) value))
    '(:void)))

(defun %ldk-static-field (classname name op)
  "Static field NAME of class CLASSNAME for request OP (:get-static or
:set-static), the binary name of the class declaring it, initialized, and the
instance holding its slot."
  (let ((binary (substitute #\/ #\. classname)))
    ;; Loaded, not initialized: Java initializes only the declaring class
    ;; (JLS 12.4.1), below.
    (unless (classload binary) (error "class not found: ~A" classname))
    (multiple-value-bind (field declaring) (%ldk-find-field binary name t)
      (unless field (error "no static field ~A on ~A" name (%ldk-field-owner binary)))
      (unless (%ldk-flag-p field #x8)
        (error "~A is an instance field of ~A; use ~A" name (%ldk-field-owner declaring)
               (if (eq op :get-static) "get-field" "set-field!")))
      (%ldk-class declaring)
      (values field declaring
              (symbol-value (find-symbol (format nil "+static-~A+" declaring) (class-package declaring)))))))

(defun %ldk-get-static (classname name)
  (multiple-value-bind (field declaring holder) (%ldk-static-field classname name :get-static)
    (declare (ignore declaring))
    (%ldk-result (descriptor field) (slot-value holder (intern (mangle-field-name name) :openldk)))))

(defun %ldk-set-static (classname name value)
  (multiple-value-bind (field declaring holder) (%ldk-static-field classname name :set-static)
    (%ldk-check-writable field name declaring)
    (setf (slot-value holder (intern (mangle-field-name name) :openldk)) (%ldk-arg (descriptor field) value))
    '(:void)))

(defvar *ldk-set-up* nil)

;; The classpath a baked core was compiled for, or NIL in the shared core.
(defvar *ldk-baked-classpath* nil)

(defun %ldk-dispatch (request)
  (destructuring-bind (op &rest rest) request
    (unless (or *ldk-set-up* (eq op :setup))
      (error "call (:setup classpath) first"))
    (ecase op
      (:setup
       (when *ldk-set-up* (error "already set up; OpenLDK sets its classpath once"))
       ;; OpenLDK itself takes any classpath here without complaint, so on a
       ;; baked core this comparison is the only thing that refuses a mismatch.
       (when (and *ldk-baked-classpath* (not (equal (first rest) *ldk-baked-classpath*)))
         (error "this core was baked for classpath ~S but setup asked for ~S"
                *ldk-baked-classpath* (first rest)))
       (%main-runtime-setup (first rest) nil nil)
       (setf *ldk-set-up* t)
       '(:void))
      (:static
       (destructuring-bind (classname name desc values) rest
         (%ldk-result (nth-value 1 (%ldk-split-descriptor desc))
                      (apply #'%ldk-call-static (substitute #\/ #\. classname)
                             (%ldk-method-key name desc) (%ldk-args desc values)))))
      (:new (destructuring-bind (classname desc values) rest
              (%ldk-new classname desc values)))
      (:invoke (destructuring-bind (id name desc values) rest
                 (%ldk-invoke (%ldk-deref id) name desc values)))
      (:main (destructuring-bind (classname args) rest (%ldk-main classname args)))
      (:class-name (list :s (%ldk-class-dotted (%ldk-deref (first rest)))))
      (:release (%ldk-release (first rest)) '(:void))
      (:new-array (destructuring-bind (component values) rest
                    (%ldk-object (%ldk-make-array component values))))
      (:elements (%ldk-elements (%ldk-array (first rest))))
      (:length (list :i (length (java-array-data (%ldk-array (first rest))))))
      (:proxy (destructuring-bind (interfaces fn-id) rest
                (%ldk-new-proxy interfaces fn-id)))
      (:super (destructuring-bind (id name desc values) rest
                (%ldk-call-super (%ldk-deref id) name desc values)))
      (:subclass (destructuring-bind (classname desc values fn-id keys) rest
                   (%ldk-new-subclass classname desc values fn-id keys)))
      (:get-field (destructuring-bind (id name) rest (%ldk-get-field (%ldk-deref id) name)))
      (:set-field (destructuring-bind (id name value) rest
                    (%ldk-set-field (%ldk-deref id) name value)))
      (:get-static (destructuring-bind (classname name) rest (%ldk-get-static classname name)))
      (:set-static (destructuring-bind (classname name value) rest
                     (%ldk-set-static classname name value))))))

(defun %ldk-callback-cause-id (throwable)
  "The error id of the first of THROWABLE's causes that is the RuntimeException
a callback's throw became (%ldk-upcall), or NIL. THROWABLE itself is not
looked at: the client already knows that case by its class and message."
  (let ((marker "[jolt-openldk callback error "))
    (loop repeat 64
          for cause = (|getCause()| throwable) then (|getCause()| cause)
          while cause
          do (let* ((m (and (string= (%ldk-class-dotted cause) "java.lang.RuntimeException")
                            (|getMessage()| cause)))
                    (text (and m (%ldk-lstring m)))
                    (at (and text (search marker text :from-end t))))
               (when (and at (char= #\] (char text (1- (length text)))))
                 (let ((id (ignore-errors
                            (parse-integer text :start (+ at (length marker)) :end (1- (length text))))))
                   (when id (return id))))))))

(defun %ldk-throwable-reply (condition)
  (let ((throwable (and (slot-boundp condition '|objref|) (slot-value condition '|objref|))))
    (if throwable
        (list* :throw
               (%ldk-class-dotted throwable)
               (let ((m (|getMessage()| throwable))) (if m (%ldk-lstring m) :null))
               (%ldk-lstring (|toString()| throwable))
               ;; A cause whose getCause or getMessage throws costs the id,
               ;; never the exception itself.
               (let ((id (ignore-errors (%ldk-callback-cause-id throwable)))) (and id (list id))))
        (list :throw "java.lang.Throwable" :null (format nil "~A" condition)))))

(defun %ldk-read-request (text)
  "Read exactly one form, with nothing evaluated and no symbols interned
outside KEYWORD: requests are data."
  (with-standard-io-syntax
    (let ((*read-eval* nil)
          (*read-default-float-format* 'double-float)
          (*package* (find-package :keyword)))
      (multiple-value-bind (form end) (read-from-string text)
        (unless (null (read-from-string text nil nil :start end))
          (error "trailing text after the request"))
        form))))

(defun %ldk-write-string (string stream)
  "A string literal clojure.edn reads back to the same characters, as far as
jolt can hold them. NUL and other control characters are written as \\uXXXX,
because the reply crosses as a C string and a raw NUL would end it. A lone
UTF-16 surrogate, which a Java String may legally hold, becomes U+FFFD: it
cannot be encoded as UTF-8 and a jolt string cannot hold one either."
  (write-char #\" stream)
  (loop for c across string
        for code = (char-code c)
        do (cond ((char= c #\") (write-string "\\\"" stream))
                 ((char= c #\\) (write-string "\\\\" stream))
                 ((char= c #\Newline) (write-char c stream))
                 ((< code #x20) (format stream "\\u~4,'0X" code))
                 ((<= #xD800 code #xDFFF) (write-char (code-char #xFFFD) stream))
                 (t (write-char c stream))))
  (write-char #\" stream))

(defun %ldk-write (x stream)
  "Write a reply form: lists, keywords, integers, doubles and strings are all
a reply ever holds."
  (etypecase x
    (list (write-char #\( stream)
          (loop for (item . more) on x
                do (%ldk-write item stream)
                   (when more (write-char #\Space stream)))
          (write-char #\) stream))
    (keyword (write-char #\: stream)
             (write-string (string-downcase (symbol-name x)) stream))
    (integer (format stream "~D" x))
    (double-float (let ((*read-default-float-format* 'double-float))
                    (prin1 x stream)))
    (string (%ldk-write-string x stream))))

(defun %ldk-print-reply (reply)
  (with-output-to-string (s) (%ldk-write reply s)))

(defun %ldk-handle-request (text)
  (%ldk-print-reply
   (handler-case
       (let ((reply (let ((*ldk-in-request* t)) (%ldk-dispatch (%ldk-read-request text)))))
         (finish-output)
         (list :ok reply))
     (ldk-java-exit (c)
       (finish-output)
       (list :exit (ldk-exit-status c)))
     (|condition-java/lang/Throwable| (c)
       (finish-output)
       (handler-case (%ldk-throwable-reply c)
         (serious-condition () (list :throw "java.lang.Throwable" :null "unprintable Java exception"))))
     (serious-condition (c)
       (finish-output)
       (list :error (handler-case (format nil "~A" c)
                      (serious-condition () "unprintable Lisp condition")))))))

(defun %ldk-ok-reply-p (text)
  (and (> (length text) 4) (string= "(:ok " text :end2 5)))

(sb-alien:define-alien-callable ldk_entry sb-alien:int
    ((request sb-alien:c-string) (out (* (* sb-alien:char))))
  ;; The reply is malloc'd by make-alien-string; the caller frees it with
  ;; ldk_free. If the reply itself cannot be made, say so in an :error reply
  ;; rather than a bare status, and return -1 only when even that fails.
  ;; A raw reply buffer (*ldk-raw-out*) belongs to the caller only once the
  ;; :ok reply naming it has been sent. On any other way out it is freed here.
  (let ((*ldk-raw-out* nil)
        (sent-raw nil))
    (flet ((send (text)
             (setf (sb-alien:deref out)
                   (sb-alien:make-alien-string text :external-format :utf-8))
             0))
      (unwind-protect
           (handler-case (let ((text (%ldk-handle-request request)))
                           (prog1 (send text)
                             (setf sent-raw (and *ldk-raw-out* (%ldk-ok-reply-p text)))))
             (serious-condition (c)
               (handler-case
                   (send (%ldk-print-reply
                          (list :error (format nil "the bridge could not write its reply: ~A"
                                               (handler-case (princ-to-string c)
                                                 (serious-condition () "unprintable condition"))))))
                 (serious-condition () -1))))
        (when (and *ldk-raw-out* (not sent-raw))
          (sb-alien:free-alien *ldk-raw-out*))))))

(defun %ldk-warm-invoke ()
  "Compile OpenLDK's java.lang.invoke machinery now, so no later method pays for
it. The first invokedynamic (any string + or lambda) otherwise loads about 38
JDK classes and compiles about 145 JDK methods, roughly 650 ms, once per process.
A method with a lambda still pays OpenLDK's lambda metafactory at run time on its
first call (about 110 ms measured on 2026-10-12), which this does not touch."
  (classload "java/lang/invoke/MethodType")
  (|java/lang/invoke/MethodType.fromMethodDescriptorString(Ljava/lang/String;Ljava/lang/ClassLoader;)|
   (jstring "(J)Ljava/lang/String;") nil)
  ;; same descriptor again: the interned-MethodType lookup (MethodType.equals etc.)
  (|java/lang/invoke/MethodType.fromMethodDescriptorString(Ljava/lang/String;Ljava/lang/ClassLoader;)|
   (jstring "(J)Ljava/lang/String;") nil)
  (classload "java/lang/invoke/StringConcatFactory")
  (classload "java/lang/invoke/LambdaMetafactory")
  (classload "java/lang/invoke/MethodHandles$Lookup"))

(defun %ldk-prepare-dump ()
  "Reset the runtime state that must not survive into a dumped core: debug
flags, the VM start time, monitors, the thread map, and every thread but this
one (save-lisp-and-die refuses to save with others running, including the
watchdog %main-runtime-setup starts)."
  ;; %parse-ldk-debug-flags only ever sets flags, and a dumped T would print
  ;; ; LOADING/; COMPILING in every later run.
  (setf *debug-codegen* nil *debug-load* nil *debug-compile* nil *debug-slynk* nil
        *debug-trace* nil *debug-trace-args* nil *debug-bytecode* nil
        *debug-exceptions* nil *debug-x* nil *debug-unmuffle* nil *debug-propagation* nil)
  ;; %main-runtime-setup stamps this only when NIL.
  (setf *vm-start-time-millis* nil)
  (clrhash *monitors*)
  (clrhash *lisp-to-java-threads*)
  (setf *current-thread* nil)
  (loop for thread in (bt:all-threads)
        unless (eq thread (bt:current-thread))
          do (bt:destroy-thread thread))
  ;; bt:destroy-thread is asynchronous: wait until the others are really gone.
  (loop repeat 100
        until (null (remove (bt:current-thread) (bt:all-threads)))
        do (sleep 0.05))
  (let ((left (remove (bt:current-thread) (bt:all-threads))))
    (when left
      (error "threads still alive after 5 s, so the core cannot be saved: ~S"
             (mapcar #'bt:thread-name left)))))

(defun make-jolt-openldk-core (path)
  "Dump a library core: OpenLDK warmed as its own make-image warms it, plus the
invokedynamic path, no toplevel, and LDK_ENTRY exported for initialize_lisp
to publish."
  (initialize)
  (%ldk-warm-invoke)
  (%ldk-prepare-dump)
  (sb-ext:save-lisp-and-die path :callable-exports '(ldk_entry)))

;;; --- Baking: load every class on a classpath, compile every method ------------

(defun %ldk-bake-log (fmt &rest args)
  (apply #'format *error-output* (concatenate 'string "~&bake: " fmt "~%") args)
  (finish-output *error-output*))

(defun %ldk-compile-failure-line (text)
  "The first line of TEXT that reports a failed compile, or NIL. %compile-method
does not signal when a method fails: it prints ;; JIT-ERROR (or COMPILE-ERROR /
FALLBACK-ERROR), installs a stub that errors when called, and returns normally."
  (with-input-from-string (in text)
    (loop for line = (read-line in nil)
          while line
          when (some (lambda (marker) (search marker line))
                     '("JIT-ERROR" "COMPILE-ERROR" "FALLBACK-ERROR"))
            return (string-trim " " line))))

(defun %ldk-bake-self-test ()
  "Exercise the failure classifier, which no real method has ever tripped."
  (flet ((check (what ok)
           (unless ok (error "bake self-test failed: ~A" what))))
    (check "JIT-ERROR" (%ldk-compile-failure-line (format nil ";; JIT-ERROR Foo.bar: boom~%more")))
    (check "COMPILE-ERROR" (%ldk-compile-failure-line (format nil "noise~%;; COMPILE-ERROR x")))
    (check "FALLBACK-ERROR" (%ldk-compile-failure-line ";; FALLBACK-ERROR x"))
    (check "first line wins"
           (string= (%ldk-compile-failure-line (format nil ";; JIT-ERROR one~%;; JIT-ERROR two"))
                    ";; JIT-ERROR one"))
    (check "clean text" (null (%ldk-compile-failure-line (format nil "; COMPILING Foo~%done"))))
    (check "empty text" (null (%ldk-compile-failure-line ""))))
  (%ldk-bake-log "self-test ok"))

(defun %ldk-bake-class-names (entry)
  "Binary names (slashed) of every class in one classpath entry (a jar or a
directory), without module-info and the multi-release META-INF/versions copies."
  (flet ((wanted (name)
           (not (or (search "META-INF/versions/" name)
                    (string= (file-namestring name) "module-info.class")))))
    (cond
      ((and (> (length entry) 4) (string= ".jar" entry :start2 (- (length entry) 4)))
       ;; A private entry, closed again so no open stream reaches the dump.
       (let ((jar (make-instance 'jar-classpath-entry :jarfile entry)))
         (unwind-protect
              (loop for n in (list-jar-classes jar)
                    when (wanted n) collect (subseq n 0 (- (length n) 6)))
           (when (slot-boundp jar 'zipfile-entries)
             (zip:close-zipfile (slot-value jar 'zipfile))))))
      (t
       (let ((base (truename (uiop:ensure-directory-pathname entry))))
         (loop for f in (directory (merge-pathnames "**/*.class" base))
               for rel = (enough-namestring f base)
               when (wanted rel) collect (subseq rel 0 (- (length rel) 6))))))))

(defun %ldk-bake-compile-class (bin-name counts)
  "Compile every method of BIN-NAME, adding to the plist in the cons COUNTS."
  (let* ((class (%get-ldk-class-by-bin-name bin-name))
         (methods (slot-value class 'methods)))
    (dotimes (i (length methods))
      (let* ((m (aref methods i))
             (code (gethash "Code" (slot-value m 'attributes))))
        (if (null code)
            (incf (getf (car counts) :methods-no-code))
            (let* ((err (make-string-output-stream))
                   (failure
                     (handler-case
                         (progn
                           (let ((*error-output* (make-broadcast-stream *error-output* err)))
                             (%compile-method bin-name (1+ i)))
                           (%ldk-compile-failure-line (get-output-stream-string err)))
                       (error (e) (format nil "~A" e)))))
              (when (> (length (slot-value code 'code)) 5000)
                (incf (getf (car counts) :methods-interpreted)))
              (cond (failure
                     (incf (getf (car counts) :methods-failed))
                     (%ldk-bake-log "failed ~A.~A: ~A" bin-name (slot-value m 'name) failure))
                    (t (incf (getf (car counts) :methods-compiled))))))))))

(defun %ldk-bake-load-and-compile (bin-name counts)
  "Load BIN-NAME and compile its methods. Returns NIL, after logging, when the
class cannot be loaded: classload returns NIL for a missing superclass instead
of signalling."
  (handler-case
      (progn
        (unless (classload bin-name) (error "classload returned NIL"))
        (incf (getf (car counts) :classes-loaded))
        (%ldk-bake-compile-class bin-name counts)
        t)
    (error (e)
      (incf (getf (car counts) :classes-skipped))
      (%ldk-bake-log "skipped ~A: ~A" bin-name e)
      nil)))

(defun make-jolt-openldk-baked-core (core-path classpath warm-classes counts-path)
  "Dump a core for CLASSPATH (a colon-joined string): OpenLDK warmed as in
make-jolt-openldk-core, every class on the classpath loaded and every method
compiled, then WARM-CLASSES (binary names such as java/time/LocalDate) the same
way. Writes an EDN map of counts to COUNTS-PATH. A warm class that does not
load, or no loadable class at all, is an error and nothing is dumped."
  (initialize)
  (%ldk-warm-invoke)
  (%main-runtime-setup classpath nil nil)
  (let ((counts (list (list :classes-loaded 0 :classes-skipped 0 :methods-compiled 0
                            :methods-failed 0 :methods-interpreted 0 :methods-no-code 0))))
    ;; Enumerate from the string: setup appends the JDK's own entries to
    ;; *classpath*, and those are not jars or directories.
    (dolist (entry (remove "" (split-sequence:split-sequence #\: classpath) :test #'string=))
      (let ((names (%ldk-bake-class-names entry)))
        (%ldk-bake-log "~A: ~D classes" entry (length names))
        (dolist (n names) (%ldk-bake-load-and-compile n counts))))
    (dolist (w warm-classes)
      (let ((n (substitute #\/ #\. w)))
        (unless (%ldk-bake-load-and-compile n counts)
          (%ldk-bake-log "unknown warm class ~A" w)
          (error "bake: unknown warm class ~A" w))))
    (with-open-file (o counts-path :direction :output :if-exists :supersede)
      ;; EDN, so keywords are lower case.
      (loop initially (write-char #\{ o)
            for (k v) on (car counts) by #'cddr
            for first = t then nil
            do (format o "~:[ ~;~]:~(~A~) ~D" first k v)
            finally (format o "}~%")))
    (%ldk-bake-log "counts ~S" (car counts))
    (when (zerop (getf (car counts) :classes-loaded))
      (error "bake: no class on ~S could be loaded" classpath))
    (setf *ldk-baked-classpath* classpath)
    (%ldk-prepare-dump)
    (sb-ext:save-lisp-and-die core-path :callable-exports '(ldk_entry))))
