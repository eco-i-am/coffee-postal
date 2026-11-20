package ph.coffeepostal.pos.shared;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The practice copies of the tables the purchase order flow writes to. Each one is made from the real table's current shape, and
 * rebuilt (emptying it) at start-up if the real table has changed since, so a later migration cannot leave practice behind.
 */
@Component
public class PracticeSchema implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PracticeSchema.class);

    /** Parents first. Nothing else is written while practising: other writes are refused by PracticeFilter. */
    public static final List<String> TABLES = List.of("branch_pos", "branch_po_lines", "branch_po_status_history", "branch_po_receipts",
        "invoices", "invoice_payments", "stock_movements");

    private final JdbcTemplate jdbc;

    PracticeSchema(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) { sync(); }

    public void sync() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS practice");
        for (String t : TABLES) {
            if (!shape("public", t).equals(shape("practice", t))) {
                jdbc.execute("DROP TABLE IF EXISTS practice." + t + " CASCADE");
                jdbc.execute("CREATE TABLE practice." + t + " (LIKE public." + t + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES)");
                log.info("Practice copy of {} (re)built", t);
            }
        }
    }

    /** Column names and types, in order: two tables with the same shape are the same for our purposes. */
    public String shape(String schema, String table) {
        return jdbc.queryForObject("""
            SELECT coalesce(string_agg(column_name || ':' || data_type, ',' ORDER BY ordinal_position), '')
            FROM information_schema.columns WHERE table_schema = ? AND table_name = ?
            """, String.class, schema, table);
    }

    /** Empties practice data. branchId null = everyone's. Names are fully qualified, so this can never touch a real table. */
    public void reset(Long branchId) {
        if (branchId == null) {
            jdbc.execute("TRUNCATE " + String.join(", ", TABLES.stream().map((t) -> "practice." + t).toList()));
            return;
        }
        String pos = "(SELECT id FROM practice.branch_pos WHERE branch_id = ?)";
        jdbc.update("DELETE FROM practice.invoice_payments WHERE invoice_id IN (SELECT id FROM practice.invoices WHERE branch_id = ?)", branchId);
        jdbc.update("DELETE FROM practice.invoices WHERE branch_id = ?", branchId);
        jdbc.update("DELETE FROM practice.branch_po_receipts WHERE line_id IN (SELECT id FROM practice.branch_po_lines WHERE po_id IN " + pos + ")", branchId);
        jdbc.update("DELETE FROM practice.branch_po_status_history WHERE po_id IN " + pos, branchId);
        jdbc.update("DELETE FROM practice.branch_po_lines WHERE po_id IN " + pos, branchId);
        jdbc.update("DELETE FROM practice.branch_pos WHERE branch_id = ?", branchId);
        jdbc.update("DELETE FROM practice.stock_movements WHERE branch_id = ?", branchId);
    }
}
