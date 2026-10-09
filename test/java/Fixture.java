// Compiled by `bb test` into target/test-classes; exercised by openldk_test.
public class Fixture {
    public static int add(int a, int b) { return a + b; }
    public static long mul(long a, long b) { return a * b; }
    public static double half(double x) { return x / 2; }
    public static float third(float x) { return x / 3; }
    public static boolean even(int n) { return n % 2 == 0; }
    public static char next(char c) { return (char) (c + 1); }
    public static String upper(String s) { return s.toUpperCase(); }
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

    private int count;
    public Fixture(int start) { count = start; }
    public int bump() { return ++count; }
    @Override public String toString() { return "Fixture(" + count + ")"; }

    public static void main(String[] args) {
        System.out.println("Fixture.main " + String.join(",", args));
    }
}
