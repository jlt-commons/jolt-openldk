// A class of our own, compiled by `bb tour` with the JDK's javac. OpenLDK runs
// the .class file; no JVM ever starts.
public class Counter {
    private final String name;
    private long total;

    public Counter(String name) { this.name = name; }

    public long add(long n) {
        if (n < 0) throw new IllegalArgumentException("negative: " + n);
        total += n;
        return total;
    }

    @Override public String toString() { return name + "=" + total; }
}
