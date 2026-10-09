/* SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0 */

/*
 * The C side of jolt-openldk: the functions jolt binds by name.
 *
 *   int   ldk_init(const char *core)             start SBCL on the OpenLDK core, once
 *   int   ldk_call(const char *request, char **reply)
 *   void  ldk_free(char *reply)
 *   void  ldk_set_upcall(void *fn)               install jolt's callback, for Java calling Clojure
 *   char *ldk_strdup(const char *s)              a reply the callback hands back, freed by ldk_free
 *
 * The Lisp side (bridge.lisp) defines the alien callable LDK_ENTRY, and the
 * core is saved with :callable-exports '(ldk_entry). When initialize_lisp
 * starts that core, SBCL writes the callable's address into the C global of
 * the same name, below, and returns instead of running a REPL. jolt binds C
 * functions by name and cannot call through a pointer, so ldk_call is the
 * named function that does.
 */
/* glibc declares dladdr, Dl_info and RTLD_NOLOAD only under _GNU_SOURCE. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define EXPORT __attribute__((visibility("default")))

extern int initialize_lisp(int argc, char **argv, char **envp);
extern char **environ;

/* SBCL fills this at startup: :callable-exports '(ldk_entry). */
EXPORT int (*ldk_entry)(const char *request, char **reply) = 0;

/*
 * SBCL finds ldk_entry with a process-wide dlsym. A library jolt.ffi loads is
 * RTLD_LOCAL, invisible to that lookup, and SBCL then dies at startup with
 * UNDEFINED-ALIEN-VARIABLE-ERROR, taking the host process with it. So promote
 * this library, and libsbcl beside it, to RTLD_GLOBAL first: dlopen with
 * RTLD_NOLOAD returns the already-loaded image and applies the new flag.
 */
static int promote_global(const void *addr) {
  Dl_info info;
  if (!dladdr(addr, &info) || !info.dli_fname) return -1;
  return dlopen(info.dli_fname, RTLD_NOW | RTLD_NOLOAD | RTLD_GLOBAL) ? 0 : -1;
}

EXPORT int ldk_init(const char *core) {
  static int initialized = 0;
  static int attempted = 0;
  if (initialized) return 1;
  /* SBCL makes no promise about a second initialize_lisp in a process where
     the first one failed part-way, so don't try. Restart the process. */
  if (attempted) return -5;
  /* initialize_lisp exits the process when it cannot read its core; check
     first so a wrong path is an error code rather than a vanished host. */
  FILE *f = fopen(core, "rb");
  if (!f) return -2;
  fclose(f);
  if (promote_global((const void *)&ldk_init) != 0) return -3;
  if (promote_global((const void *)&initialize_lisp) != 0) return -3;
  attempted = 1;
  char *argv[] = {"jolt-openldk", "--core", (char *)core,
                  "--dynamic-space-size", "8192", "--noinform", 0};
  if (initialize_lisp(6, argv, environ) != 0) return -1;
  if (!ldk_entry) return -4;
  initialized = 1;
  return 0;
}

EXPORT int ldk_call(const char *request, char **reply) {
  if (!ldk_entry) return -4;
  return ldk_entry(request, reply);
}

EXPORT void ldk_free(char *reply) { free(reply); }

/*
 * The other direction: Java calling Clojure. jolt installs one ffi/callback
 * here; bridge.lisp calls it with a request and gets back a reply that the
 * callback made with ldk_strdup, which bridge.lisp then frees with ldk_free.
 * Allocating through this library on both sides means the reply is freed by
 * the malloc that made it, whatever jolt's own allocator is.
 */
EXPORT int (*ldk_upcall)(const char *request, char **reply) = 0;

EXPORT void ldk_set_upcall(void *fn) {
  ldk_upcall = (int (*)(const char *, char **))fn;
}

EXPORT char *ldk_strdup(const char *s) { return strdup(s); }
