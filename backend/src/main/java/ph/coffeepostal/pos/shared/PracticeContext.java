package ph.coffeepostal.pos.shared;

/**
 * Practice mode, per request. While it is on, the database calls of that request go to the practice pool, whose connections
 * look in the "practice" schema first (see PracticeDataSourceConfig). Set by PracticeFilter and always cleared after the request.
 */
public final class PracticeContext {
    private static final ThreadLocal<Boolean> ON = new ThreadLocal<>();

    private PracticeContext() {}

    public static boolean on() { return Boolean.TRUE.equals(ON.get()); }

    static void enter() { ON.set(Boolean.TRUE); }

    static void leave() { ON.remove(); }
}
