package android.util;

/** JVM request tests do not have Android's logging service. */
public final class Log {
    private Log() {}

    public static boolean isLoggable(String tag, int level) { return false; }
    public static int println(int priority, String tag, String message) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int d(String tag, String message) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable error) { return 0; }
}
