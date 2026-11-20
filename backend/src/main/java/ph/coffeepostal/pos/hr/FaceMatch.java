package ph.coffeepostal.pos.hr;

import org.springframework.http.HttpStatus;
import ph.coffeepostal.pos.shared.ApiException;

/**
 * Compares a face "descriptor" (128 numbers made on the tablet) with the enrolled one.
 * The result only flags a punch for a manager to look at; it never stops a punch.
 */
public final class FaceMatch {
    private FaceMatch() {}

    public static final int SIZE = 128;
    /** Distance at or under this counts as the same person. 0.6 is the usual limit for this model; 0.5 is a little stricter. */
    public static final double MATCH_MAX = 0.5;

    /** Signing a punch in by face alone (no PIN) is stricter than flagging one: a closer match is required. */
    public static final double LOGIN_MAX = 0.45;

    /** What the tablet saw: state is "ok" (one clear face, descriptor set), "none", "multi", or "off" (the check could not run). */
    public record Face(String state, double[] descriptor) {}
    public record Result(String status, Double score) {}

    public static boolean valid(double[] d) {
        if (d == null || d.length != SIZE) return false;
        double sum = 0;
        for (double v : d) {
            if (!Double.isFinite(v) || Math.abs(v) > 5) return false;
            sum += v * v;
        }
        return sum > 0.01;   // an all-zero vector is not a face
    }

    public static void requireValid(double[] d) {
        if (!valid(d)) throw new ApiException(HttpStatus.BAD_REQUEST, "That face reading is not usable. Try again with good light.");
    }

    public static double distance(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < SIZE; i++) { double x = a[i] - b[i]; s += x * x; }
        return Math.sqrt(s);
    }

    /** The result for one punch. enrolled = null when the employee has no face on file. */
    public static Result check(double[] enrolled, Face seen) {
        if (enrolled == null) return new Result("NOT_ENROLLED", null);
        if (seen == null || seen.state() == null || "off".equals(seen.state())) return new Result("NOT_CHECKED", null);
        if (!"ok".equals(seen.state()) || !valid(seen.descriptor())) return new Result("NO_FACE", null);
        double d = distance(enrolled, seen.descriptor());
        return new Result(d <= MATCH_MAX ? "MATCH" : "REVIEW", d);
    }

    /** "{0.1,0.2,...}" as written by Postgres for a real[]. */
    public static double[] parse(String pg) {
        if (pg == null) return null;
        String s = pg.trim();
        if (s.startsWith("{")) s = s.substring(1, s.length() - 1);
        if (s.isBlank()) return null;
        String[] p = s.split(",");
        double[] d = new double[p.length];
        for (int i = 0; i < p.length; i++) d[i] = Double.parseDouble(p[i].trim());
        return d;
    }

    /** The Postgres array literal for a descriptor. */
    public static String literal(double[] d) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < d.length; i++) { if (i > 0) b.append(','); b.append((float) d[i]); }
        return b.append('}').toString();
    }
}
