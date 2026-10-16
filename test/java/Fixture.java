// SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

// Compiled by `bb test` into target/test-classes; exercised by openldk_test.
public class Fixture {
    public static int add(int a, int b) { return a + b; }
    public static long mul(long a, long b) { return a * b; }
    public static double half(double x) { return x / 2; }
    public static float third(float x) { return x / 3; }
    public static boolean even(int n) { return n % 2 == 0; }
    public static char next(char c) { return (char) (c + 1); }
    public static String upper(String s) { return s.toUpperCase(); }
    public static String shout(String s) { return s.toUpperCase() + "!"; }
    public static String nothing() { return null; }
    public static double nan() { return Double.NaN; }
    public static Object boxed() { return Integer.valueOf(7); }
    public static String describe(Object o) { return o == null ? "null" : o.getClass().getName() + ":" + o; }
    public static void fail(String why) { throw new IllegalStateException("boom: " + why); }
    public static void npe() { Object o = null; o.hashCode(); }

    // Arrays, both directions.
    public static int sum(int[] xs) { int t = 0; for (int x : xs) t += x; return t; }
    public static long total(long[] xs) { long t = 0; for (long x : xs) t += x; return t; }
    public static double avg(double[] xs) { double t = 0; for (double x : xs) t += x; return t / xs.length; }
    public static String join(String[] xs) { return String.join("|", xs); }
    public static int trues(boolean[] xs) { int n = 0; for (boolean x : xs) if (x) n++; return n; }
    public static char last(char[] xs) { return xs[xs.length - 1]; }
    public static int byteSum(byte[] xs) { int t = 0; for (byte x : xs) t += x; return t; }
    public static int deep(int[][] xss) { int t = 0; for (int[] xs : xss) t += sum(xs); return t; }
    public static void squares(int[] xs) { for (int i = 0; i < xs.length; i++) xs[i] = i * i; }
    public static byte[] bytes() { return new byte[] {-1, 0, 127, -128}; }
    public static boolean[] flags() { return new boolean[] {true, false, true}; }
    public static char[] letters() { return "h\u00e9y".toCharArray(); }
    public static float[] floats() { return new float[] {0.5f, -1.25f}; }
    public static int[][] matrix() { return new int[][] {{1, 2}, {3}}; }
    public static String[] names() { return new String[] {"ada", null, "\u00e9"}; }
    public static Object[] mixed() { return new Object[] {"s", 1, 2.5, true, null, new Fixture(3)}; }
    public static int[] none() { return new int[0]; }
    public static String boxedInt(Integer i) { return i.getClass().getName() + ":" + i; }

    // Callbacks: Java calling Clojure through interfaces.
    public static void run(Runnable r) { r.run(); }
    public static String twice(java.util.function.Supplier<String> s) { return s.get() + s.get(); }
    public static int applyInt(java.util.function.IntBinaryOperator op, int a, int b) { return op.applyAsInt(a, b); }
    public static Object apply(java.util.function.Function<Object, Object> f, Object x) { return f.apply(x); }
    public static String drain(java.util.Iterator<String> it) {
        StringBuilder sb = new StringBuilder();
        while (it.hasNext()) sb.append(it.next()).append(';');
        return sb.toString();
    }
    public static void onThread(Runnable r) throws InterruptedException {
        Thread t = new Thread(r);
        t.start();
        t.join();
    }
    public static String tryRun(Runnable r) {
        try { r.run(); return "no exception"; }
        catch (RuntimeException e) { return "caught: " + e.getMessage(); }
    }
    public interface Base { int f(); int g(); }
    public interface Sub extends Base { default int f() { return 7; } }
    public static int callF(Base b) { return b.f(); }
    public static int callG(Base b) { return b.g(); }
    public static void wrap(Runnable r) {
        try { r.run(); }
        catch (RuntimeException e) { throw new IllegalStateException("wrapped: " + e.getMessage(), e); }
    }
    public static void wrapTwice(Runnable r) {
        try { wrap(r); }
        catch (IllegalStateException e) { throw new UnsupportedOperationException("outer", e); }
    }
    public static class Nasty extends RuntimeException {
        public Nasty() { super("nasty"); }
        @Override public synchronized Throwable getCause() { throw new IllegalStateException("no cause for you"); }
    }
    public static void nasty() { throw new IllegalArgumentException("outer", new Nasty()); }
    public static void quote(Runnable r) {
        try { r.run(); }
        catch (RuntimeException e) { throw new IllegalStateException("quoted: " + e.getMessage()); }
    }
    public static String tryInt(java.util.function.IntSupplier s) {
        try { return "got " + s.getAsInt(); }
        catch (RuntimeException e) { return "caught: " + e.getMessage(); }
    }

    // Used with ldk/subclass.
    public static long sumList(java.util.List<? extends Number> l) { long t = 0; for (Number x : l) t += x.longValue(); return t; }
    public static int readAll(java.io.InputStream in) throws java.io.IOException {
        byte[] buf = new byte[16]; int n = in.read(buf, 0, buf.length); int t = 0;
        for (int i = 0; i < n; i++) t += buf[i];
        return n * 1000 + t;
    }
    public static String writeSome(java.io.Writer w) throws java.io.IOException { w.write("abc"); w.write('d'); w.flush(); return "ok"; }
    public static long asLong(Number n) { return n.longValue() + n.intValue(); }
    public static boolean runTask(java.util.TimerTask t) { t.run(); return t.cancel(); }

    public static abstract class Shape {
        protected Shape() {}
        public abstract double area();
        public final String kind() { return "shape"; }
        private int secret() { return 1; }
        public static int count() { return 0; }
        public String describe() { return kind() + " of area " + area(); }
    }
    public static abstract class Greeter {
        private final String greeting;
        protected Greeter() { greeting = greet(); }
        public abstract String greet();
        public String greeting() { return greeting; }
    }
    public static String startJoin(Thread t) throws InterruptedException { t.start(); t.join(); return t.getName(); }
    // A default inherited through one superinterface path, abstract through another.
    public interface J { int n(); }
    public interface K extends J { default int n() { return 42; } }
    public interface L extends J {}
    public static abstract class Both implements L, K {}
    public interface LK extends L, K {}
    // A covariant redeclaration: javac adds a bridge default get()Object to StrGetter.
    public interface Getter { Object get(); }
    public interface StrGetter extends Getter { String get(); }
    public static Object callGet(Getter g) { return g.get(); }
    public static abstract class Strs implements StrGetter {}
    public static int callN(J j) { return j.n(); }
    // A private superclass method doesn't implement an interface method.
    public static class Y { private int m() { return 1; } }
    public interface I { int m(); }
    public static abstract class X extends Y implements I {}
    public static int callM(I i) { return i.m(); }
    public static boolean lockIsSelf(java.io.Writer w) throws Exception {
        java.lang.reflect.Field f = java.io.Writer.class.getDeclaredField("lock"); f.setAccessible(true); return f.get(w) == w;
    }
    public static final class Sealed {}
    public static class Hidden { private Hidden() {} public Hidden(int x) {} }

    // Fields read and written from Clojure.
    public interface Konst { int KONST = 7; }
    public static class FieldsBase { public int shared = 1; public int inherited = 5; public static int bstat = 11; }
    public static class Fields extends FieldsBase implements Konst {
        public int shared = 2;
        public int n = 42; public long l = 1L << 40; public boolean z = true; public char c = 'x';
        public float f = 1.5f; public double d = 2.5; public byte b = -3; public short s = -300;
        public String str = "hi"; public Object o; public int[] arr = {1, 2}; public Runnable task;
        public int type = 4; private int secret = 9; public final int fin = 3;
        public static int counter = 100; public static final int MAX = 1 << 20; public static final String NAME = "F!";
        public static long big = Long.MIN_VALUE;
        public int readShared() { return shared; }
        public int readSuperShared() { return super.shared; }
        public int sumArr() { int t = 0; for (int x : arr) t += x; return t; }
        public static int readBstat() { return FieldsBase.bstat; }
        public static int readCounter() { return counter; }
    }
    public static abstract class Counted { protected int count; public abstract void bump(); public int count() { return count; } }
    public static boolean lazyRan;
    public static boolean lazyInitRan() { return lazyRan; }
    public static class Lazy { public static int ran = mark(); static int mark() { lazyRan = true; return 1; } }
    public static boolean subInitRan;
    public static boolean subInitDone() { return subInitRan; }
    public static class InitBase { public static int bs = 3; }
    public static class InitSub extends InitBase { static { subInitRan = true; } }
    public static class Broken { public static int x = 1 / zero(); static int zero() { return 0; } }

    // Java exiting, which comes back to Clojure as an exception.
    public static boolean afterExit, exitCaught, exitFinally, hookRan, callbackCaught;
    public static void exitWith(int n) { System.exit(n); afterExit = true; }
    public static void exitInTry() { try { System.exit(3); } catch (Throwable t) { exitCaught = true; } finally { exitFinally = true; } }
    public static void haltWith(int n) { Runtime.getRuntime().halt(n); }
    public static void exitInThread() throws InterruptedException { Thread t = new Thread(() -> System.exit(7)); t.start(); t.join(); }
    public static void addHook() { Runtime.getRuntime().addShutdownHook(new Thread(() -> hookRan = true)); }
    public static synchronized void exitLocked(int n) { System.exit(n); }
    public static boolean lockFreeFromAnotherThread() throws InterruptedException {
        boolean[] got = {false};
        Thread t = new Thread(() -> { synchronized (Fixture.class) { got[0] = true; } });
        t.start(); t.join(5000); return got[0];
    }
    public static void runCatching(Runnable r) { try { r.run(); } catch (Throwable t) { callbackCaught = true; } }
    public static class ExitInit { static { System.exit(9); } public static int x = 1; }
    public static class ExitMain { public static void main(String[] args) { System.exit(2); } }

    private static Runnable stored;
    public static void store(Runnable r) { stored = r; }
    public static void runStored() { stored.run(); }

    private int count;
    public Fixture(int start) { count = start; }
    public int bump() { return ++count; }
    @Override public String toString() { return "Fixture(" + count + ")"; }

    public static void main(String[] args) {
        System.out.println("Fixture.main " + String.join(",", args));
    }
}
