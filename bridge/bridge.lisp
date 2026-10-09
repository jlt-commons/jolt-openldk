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
;;;   (:release id)                             (:ok (:void))
;;;
;;; Values: (:i n) (:d x) (:nan) (:inf 1|-1) (:z :true|:false) (:c code) (:s "text")
;;; (:null) (:ref id) on the way in, the same plus (:ref id "class") and
;;; (:void) on the way out. A Java exception is
;;; (:throw "java.lang.Class" "message"|:null "toString()").

(in-package :openldk)

;;; --- descriptors ---------------------------------------------------------

(defun %ldk-split-descriptor (desc)
  "\"(ILjava/lang/String;[I)V\" -> (values (\"I\" \"Ljava/lang/String;\" \"[I\") \"V\")."
  (let ((close (position #\) desc))
        (params '()))
    (unless (and (plusp (length desc)) (char= (char desc 0) #\() close)
      (error "malformed method descriptor ~S" desc))
    (let ((i 1))
      (loop while (< i close)
            do (let ((start i))
                 (loop while (char= (char desc i) #\[) do (incf i))
                 (if (char= (char desc i) #\L)
                     (setf i (1+ (position #\; desc :start i)))
                     (incf i))
                 (push (subseq desc start i) params))))
    (values (nreverse params) (subseq desc (1+ close)))))

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

(defun %ldk-box (classname key value)
  (%ldk-call-static classname key value))

(defun %ldk-arg (param value)
  "Turn wire VALUE into what OpenLDK passes for a parameter of type PARAM."
  (let ((tag (first value))
        (ref-param (member (char param 0) '(#\L #\[))))
    (ecase tag
      (:null (if ref-param nil (error "null for primitive parameter ~A" param)))
      (:ref (if ref-param (%ldk-deref (second value))
                (error "an object for primitive parameter ~A" param)))
      (:s (if ref-param (jstring (second value))
              (error "a string for primitive parameter ~A" param)))
      (:i (let ((n (second value)))
            (case (char param 0)
              (#\I (check-type n (signed-byte 32)) n)
              (#\S (check-type n (signed-byte 16)) n)
              (#\B (check-type n (signed-byte 8)) n)
              (#\C (check-type n (unsigned-byte 16)) n)
              (#\J (check-type n (signed-byte 64)) n)
              (#\F (coerce n 'single-float))
              (#\D (coerce n 'double-float))
              (t (if ref-param
                     (%ldk-box "java/lang/Long" "valueOf(J)" n)
                     (error "an integer for parameter ~A" param))))))
      ((:d :nan :inf)
       (let ((x (case tag
                  (:d (coerce (second value) 'double-float))
                  (:nan (- sb-ext:double-float-positive-infinity
                           sb-ext:double-float-positive-infinity))
                  (:inf (if (minusp (second value))
                            sb-ext:double-float-negative-infinity
                            sb-ext:double-float-positive-infinity)))))
         (case (char param 0)
           (#\D x)
           (#\F (coerce x 'single-float))
           (t (if ref-param
                  (%ldk-box "java/lang/Double" "valueOf(D)" x)
                  (error "a double for parameter ~A" param))))))
      (:z (let ((b (ecase (second value) (:true 1) (:false 0))))
            (case (char param 0)
              (#\Z b)
              (t (if ref-param
                     (%ldk-box "java/lang/Boolean" "valueOf(Z)" b)
                     (error "a boolean for parameter ~A" param))))))
      (:c (if (char= (char param 0) #\C) (second value)
              (error "a char for parameter ~A" param))))))

(defun %ldk-args (desc values)
  (let ((params (%ldk-split-descriptor desc)))
    (unless (= (length params) (length values))
      (error "~A takes ~D argument~:P, got ~D" desc (length params) (length values)))
    (mapcar #'%ldk-arg params values)))

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
  (substitute #\. #\/ (string (class-name (class-of object)))))

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
      (:release (%ldk-release (first rest)) '(:void)))))

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

(defun %ldk-print-reply (reply)
  (with-standard-io-syntax
    (let ((*read-default-float-format* 'double-float)
          (*print-case* :downcase)
          (*print-readably* nil)
          (*package* (find-package :keyword)))
      (prin1-to-string reply))))

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
  ;; ldk_free. A non-zero return means not even a reply could be made.
  (handler-case
      (progn
        (setf (sb-alien:deref out)
              (sb-alien:make-alien-string (%ldk-handle-request request)
                                          :external-format :utf-8))
        0)
    (serious-condition () -1)))

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
