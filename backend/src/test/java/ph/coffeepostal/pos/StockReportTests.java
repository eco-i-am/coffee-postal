package ph.coffeepostal.pos;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class StockReportTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private void move(long branch, long item, long qty, String kind, String when) {
		// times are Manila local, so the day boundaries match what the report uses
		jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, created_at) VALUES (?, ?, ?, ?, (?::timestamp AT TIME ZONE 'Asia/Manila'))", branch, item, qty, kind, when);
	}

	@Test
	void beginningPlusMovementsEqualsEndingAndHqCanAddUpAllBranches() throws Exception {
		long b1 = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-01'", Long.class);
		long b2 = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-02'", Long.class);
		String name = "Ledger test " + System.nanoTime();
		long item = jdbc.queryForObject("INSERT INTO items(name, unit, cost) VALUES (?, 'kg', 10000) RETURNING id", Long.class, name);

		move(b1, item, 10000, "OPENING", "2026-03-05 09:00:00");          // before the range: counts toward beginning
		move(b1, item, 5000, "PO_RECEIVE", "2026-03-10 00:00:00");        // first instant of the range
		move(b1, item, 2000, "TRANSFER_IN", "2026-03-11 12:00:00");
		move(b1, item, -1000, "TRANSFER_OUT", "2026-03-11 13:00:00");
		move(b1, item, -3000, "SALE", "2026-03-11 14:00:00");
		move(b1, item, -500, "WASTE", "2026-03-12 23:59:59");             // last second of the range
		move(b1, item, 250, "ADJUST", "2026-03-12 10:00:00");
		move(b1, item, -999, "SALE", "2026-03-13 00:00:00");              // after the range: not in this report
		move(b2, item, 4000, "PO_RECEIVE", "2026-03-11 09:00:00");        // another branch

		var manager = login("manager");
		// branch view: this branch only
		mvc.perform(get("/api/branch/reports/stock?from=2026-03-10&to=2026-03-12").session(manager)).andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].beginning").value(10000))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].received").value(5000))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].transferIn").value(2000))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].transferOut").value(1000))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].used").value(3000))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].wasted").value(500))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].adjusted").value(250))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].ending").value(12750))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].value").value(127500));   // 12.75 kg at 100.00

		// a range that starts later: yesterday's ending is today's beginning
		mvc.perform(get("/api/branch/reports/stock?from=2026-03-12&to=2026-03-12").session(manager))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].beginning").value(13000))   // 10000+5000+2000-1000-3000
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].ending").value(12750));

		// HQ with no branch adds every branch; with a branch it matches that branch
		var admin = login("admin");
		mvc.perform(get("/api/admin/reports/stock?from=2026-03-10&to=2026-03-12").session(admin)).andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].ending").value(16750))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].received").value(9000));
		mvc.perform(get("/api/admin/reports/stock?from=2026-03-10&to=2026-03-12&branchId=" + b2).session(admin))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].ending").value(4000))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].beginning").value(0));

		jdbc.update("UPDATE items SET active = FALSE WHERE id = ?", item);
	}
}
