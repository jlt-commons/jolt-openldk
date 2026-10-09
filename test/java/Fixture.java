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

    private int count;
    public Fixture(int start) { count = start; }
    public int bump() { return ++count; }
    @Override public String toString() { return "Fixture(" + count + ")"; }

    public static void main(String[] args) {
        System.out.println("Fixture.main " + String.join(",", args));
    }
}
