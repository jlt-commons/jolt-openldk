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
;;;   (:elements id)                            (:ok (:vec v ...))
;;;   (:length id)                              (:ok (:i n))
;;;   (:release id)                             (:ok (:void))
;;;
;;; Values: (:i n) (:d x) (:nan) (:inf 1|-1) (:z :true|:false) (:c code) (:s "text")
;;; (:null) (:ref id) (:array (v ...)) on the way in, the same plus (:ref id "class") and
;;; (:void) on the way out. A Java exception is
;;; (:throw "java.lang.Class" "message"|:null "toString()").

(in-package :openldk)

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

(defun %ldk-handle (object)
  (sb-thread:with-mutex (*ldk-handle-lock*)
    (let ((id (incf *ldk-next-handle*)))
      (setf (gethash id *ldk-handles*) object)
      id)))

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
or nil. A JavaRef's class is not checked against the parameter's here.")

(defun %ldk-noun (tag)
  (case tag
    (:i "an integer") (:d "a double") (:z "a boolean") (:c "a char")
    (:s "a string") (:array "a vector") (:ref "an object") (:null "null")
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
             (:ref (let ((object (%ldk-deref (second value))))
                     (unless (java-array-p object)
                       (error "a ~A for array parameter ~A" (%ldk-class-dotted object) param))
                     object))
             (t (error "~A for array parameter ~A" (%ldk-noun tag) param))))
      (#\L (case tag
             (:null nil)
             (:ref (%ldk-deref (second value)))
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

(defun %ldk-make-array (component values)
  "A Java array whose component has descriptor COMPONENT (I, Ljava/lang/String;,
[I ...), holding wire VALUES converted as arguments of that type."
  (%ldk-check-field component)
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

(defun %ldk-elements (array)
  "Every element, tagged by the component type. Elements that are objects
come back as values or handles, as any returned object does; a nested array
is a handle of its own."
  (let ((kind (or (cdr (assoc (%ldk-array-component-name array) *ldk-primitive-names*
                              :test #'string=))
                  #\L)))
    (cons :vec (map 'list (lambda (x) (%ldk-element kind x)) (java-array-data array)))))

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

;;; --- requests ----------------------------------------------------------------

(defun %ldk-invoke (receiver name desc values)
  (when (null receiver) (error "invoke on a null receiver"))
  (let* ((args (%ldk-args desc values))
         (gf (find-symbol (%ldk-method-key name desc) :openldk)))
    (unless (and gf (fboundp gf))
      (error "no instance method ~A~A on ~A" name desc (%ldk-class-dotted receiver)))
    (%ldk-result (nth-value 1 (%ldk-split-descriptor desc))
                 (apply gf (%box-if-native receiver) args))))

(defun %ldk-new (classname desc values)
  (let* ((binary (substitute #\/ #\. classname))
         (args (%ldk-args desc values))
         (class (%ldk-class binary))
         (object (%make-java-instance binary))
         (java-class (%get-java-class-by-bin-name binary t)))
    (declare (ignore class))
    (when (and java-class (slot-exists-p object '|clazz|))
      (setf (slot-value object '|clazz|) java-class))
    (invoke-special (intern (%ldk-method-key "<init>" desc) :openldk)
                    (class-name (class-of object))
                    (cons object args))
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

(defvar *ldk-set-up* nil)

(defun %ldk-dispatch (request)
  (destructuring-bind (op &rest rest) request
    (unless (or *ldk-set-up* (eq op :setup))
      (error "call (:setup classpath) first"))
    (ecase op
      (:setup
       (when *ldk-set-up* (error "already set up; OpenLDK sets its classpath once"))
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
      (:length (list :i (length (java-array-data (%ldk-array (first rest)))))))))

(defun %ldk-throwable-reply (condition)
  (let ((throwable (and (slot-boundp condition '|objref|) (slot-value condition '|objref|))))
    (if throwable
        (list :throw
              (%ldk-class-dotted throwable)
              (let ((m (|getMessage()| throwable))) (if m (%ldk-lstring m) :null))
              (%ldk-lstring (|toString()| throwable)))
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
       (let ((reply (%ldk-dispatch (%ldk-read-request text))))
         (finish-output)
         (list :ok reply))
     (|condition-java/lang/Throwable| (c)
       (finish-output)
       (handler-case (%ldk-throwable-reply c)
         (serious-condition () (list :throw "java.lang.Throwable" :null "unprintable Java exception"))))
     (serious-condition (c)
       (finish-output)
       (list :error (handler-case (format nil "~A" c)
                      (serious-condition () "unprintable Lisp condition")))))))

(sb-alien:define-alien-callable ldk_entry sb-alien:int
    ((request sb-alien:c-string) (out (* (* sb-alien:char))))
  ;; The reply is malloc'd by make-alien-string; the caller frees it with
  ;; ldk_free. If the reply itself cannot be made, say so in an :error reply
  ;; rather than a bare status, and return -1 only when even that fails.
  (flet ((send (text)
           (setf (sb-alien:deref out)
                 (sb-alien:make-alien-string text :external-format :utf-8))
           0))
    (handler-case (send (%ldk-handle-request request))
      (serious-condition (c)
        (handler-case
            (send (%ldk-print-reply
                   (list :error (format nil "the bridge could not write its reply: ~A"
                                        (handler-case (princ-to-string c)
                                          (serious-condition () "unprintable condition"))))))
          (serious-condition () -1))))))

(defun make-jolt-openldk-core (path)
  "Dump a library core: OpenLDK warmed as its own make-image warms it, no
toplevel, and LDK_ENTRY exported for initialize_lisp to publish."
  (initialize)
  (clrhash *monitors*)
  (clrhash *lisp-to-java-threads*)
  (setf *current-thread* nil)
  (loop for thread in (bt:all-threads)
        when (and (not (eq thread (bt:current-thread)))
                  (search "Java-Thread" (bt:thread-name thread)))
          do (bt:destroy-thread thread))
  (sb-ext:save-lisp-and-die path :callable-exports '(ldk_entry)))
