package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class BranchPoFlowTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private ResultActions send(MockHttpSession s, String url, String json) throws Exception {
		return mvc.perform(post(url).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions advance(MockHttpSession admin, long po, String to) throws Exception {
		return send(admin, "/api/admin/branch-pos/" + po + "/advance", "{\"to\":\"" + to + "\",\"note\":\"test\"}");
	}

	@Test
	void orderWalksThroughAllNineStagesWithHistoryAndPartialReceiving() throws Exception {
		var manager = login("manager");
		var admin = login("admin");
		long beans = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Espresso%'", Long.class);
		long branch = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-01'", Long.class);
		long before = jdbc.queryForObject("SELECT coalesce(sum(qty),0) FROM stock_movements WHERE branch_id=? AND item_id=?", Long.class, branch, beans);

		String c = send(manager, "/api/branch/purchase-orders", "{\"lines\":[{\"itemId\":" + beans + ",\"qty\":10}]}").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		long po = ((Number) JsonPath.read(c, "$.id")).longValue();
		long line = jdbc.queryForObject("SELECT id FROM branch_po_lines WHERE po_id=?", Long.class, po);

		// steps cannot be skipped, and only HQ moves them
		advance(admin, po, "PROCESSING").andExpect(status().isConflict());          // still REQUESTED
		send(manager, "/api/admin/branch-pos/" + po + "/advance", "{\"to\":\"PENDING\"}").andExpect(status().isForbidden());
		send(admin, "/api/admin/branch-pos/" + po + "/approve", "{\"lines\":[]}").andExpect(status().isOk());   // REQUESTED -> PENDING
		advance(admin, po, "DELIVERED").andExpect(status().isConflict());
		advance(admin, po, "NONSENSE").andExpect(status().isConflict());
		advance(admin, po, "TO_PAY").andExpect(status().isOk());
		// the branch cannot receive until HQ has prepared it
		send(manager, "/api/branch/purchase-orders/" + po + "/receive", "{\"lines\":[]}").andExpect(status().isConflict());
		advance(admin, po, "PROCESSING").andExpect(status().isOk());
		advance(admin, po, "FOR_PICKUP").andExpect(status().isOk());
		advance(admin, po, "IN_TRANSIT").andExpect(status().isOk());
		advance(admin, po, "PICKED_UP").andExpect(status().isOk());
		advance(admin, po, "TO_PAY").andExpect(status().isConflict());              // no going back

		// first delivery: 6 of 10. Stock +6, order DELIVERED, invoice for 6 bags
		send(manager, "/api/branch/purchase-orders/" + po + "/receive", "{\"lines\":[{\"lineId\":" + line + ",\"qty\":6}]}").andExpect(status().isOk());
		long cost = jdbc.queryForObject("SELECT unit_cost FROM branch_po_lines WHERE id=?", Long.class, line);
		mvc.perform(get("/api/branch/purchase-orders/" + po).session(manager)).andExpect(jsonPath("$.po.status").value("DELIVERED"));
		assertThat(jdbc.queryForObject("SELECT amount FROM invoices WHERE po_id=?", Long.class, po)).isEqualTo(6 * cost);

		// cannot take more than is still due; nothing changes
		send(manager, "/api/branch/purchase-orders/" + po + "/receive", "{\"lines\":[{\"lineId\":" + line + ",\"qty\":5}]}").andExpect(status().isBadRequest());
		send(manager, "/api/branch/purchase-orders/" + po + "/receive", "{\"lines\":[{\"lineId\":" + line + ",\"qty\":0}]}").andExpect(status().isBadRequest());

		// a payment on the first part, then the rest arrives: the same invoice grows, keeps its payment, and the order completes
		long inv = jdbc.queryForObject("SELECT id FROM invoices WHERE po_id=?", Long.class, po);
		send(admin, "/api/admin/receivables/" + inv + "/payments", "{\"amount\":\"100\",\"method\":\"CASH\"}").andExpect(status().is2xxSuccessful());
		send(manager, "/api/branch/purchase-orders/" + po + "/receive", "{\"lines\":[]}").andExpect(status().isOk());   // unlisted = everything still due
		assertThat(jdbc.queryForObject("SELECT amount FROM invoices WHERE po_id=?", Long.class, po)).isEqualTo(10 * cost);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE po_id=?", Integer.class, po)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT coalesce(sum(qty),0) FROM stock_movements WHERE branch_id=? AND item_id=?", Long.class, branch, beans) - before).isEqualTo(10_000);
		mvc.perform(get("/api/branch/purchase-orders/" + po).session(manager)).andExpect(jsonPath("$.po.status").value("COMPLETED"));
		// the branch can see its payments on the invoice
		mvc.perform(get("/api/branch/payables/" + inv).session(manager)).andExpect(status().isOk()).andExpect(jsonPath("$.payments.length()").value(1));
		send(manager, "/api/branch/purchase-orders/" + po + "/receive", "{\"lines\":[]}").andExpect(status().isConflict());

		// the history shows every step in order, with who and when
		String d = mvc.perform(get("/api/branch/purchase-orders/" + po).session(manager)).andReturn().getResponse().getContentAsString();
		java.util.List<String> steps = JsonPath.read(d, "$.history[*].to");
		assertThat(steps).containsExactly("REQUESTED", "PENDING", "TO_PAY", "PROCESSING", "FOR_PICKUP", "IN_TRANSIT", "PICKED_UP", "DELIVERED", "COMPLETED");

		jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, reason) VALUES (?, ?, -10000, 'ADJUST', 'test reset')", branch, beans);
	}

	@Test
	void rejectingClosingShortAndSkippingPaymentWork() throws Exception {
		var manager = login("manager");
		var admin = login("admin");
		long beans = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Espresso%'", Long.class);
		long branch = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-01'", Long.class);

		// HQ can reject while the order is still being prepared for payment, but not after it is being processed
		String c = send(manager, "/api/branch/purchase-orders", "{\"lines\":[{\"itemId\":" + beans + ",\"qty\":2}]}").andReturn().getResponse().getContentAsString();
		long po = ((Number) JsonPath.read(c, "$.id")).longValue();
		send(admin, "/api/admin/branch-pos/" + po + "/approve", "{}").andExpect(status().isOk());
		advance(admin, po, "TO_PAY").andExpect(status().isOk());
		send(admin, "/api/admin/branch-pos/" + po + "/reject", "{\"reason\":\"Not paid\"}").andExpect(status().isOk());
		mvc.perform(get("/api/admin/branch-pos/" + po).session(admin)).andExpect(jsonPath("$.po.status").value("REJECTED")).andExpect(jsonPath("$.rejectReason").value("Not paid"));

		// no payment step needed: PENDING can go straight to PROCESSING; branch picks up itself (FOR_PICKUP -> receive)
		String c2 = send(manager, "/api/branch/purchase-orders", "{\"lines\":[{\"itemId\":" + beans + ",\"qty\":4}]}").andReturn().getResponse().getContentAsString();
		long po2 = ((Number) JsonPath.read(c2, "$.id")).longValue();
		long line2 = jdbc.queryForObject("SELECT id FROM branch_po_lines WHERE po_id=?", Long.class, po2);
		send(admin, "/api/admin/branch-pos/" + po2 + "/approve", "{}").andExpect(status().isOk());
		advance(admin, po2, "PROCESSING").andExpect(status().isOk());
		send(admin, "/api/admin/branch-pos/" + po2 + "/reject", "{\"reason\":\"too late\"}").andExpect(status().isConflict());
		advance(admin, po2, "FOR_PICKUP").andExpect(status().isOk());
		send(manager, "/api/branch/purchase-orders/" + po2 + "/receive", "{\"lines\":[{\"lineId\":" + line2 + ",\"qty\":1}]}").andExpect(status().isOk());
		mvc.perform(get("/api/branch/purchase-orders/" + po2).session(manager)).andExpect(jsonPath("$.po.status").value("DELIVERED"));
		send(manager, "/api/branch/purchase-orders/" + po2 + "/close", "{}").andExpect(status().isOk());
		mvc.perform(get("/api/branch/purchase-orders/" + po2).session(manager)).andExpect(jsonPath("$.po.status").value("COMPLETED"));
		assertThat(jdbc.queryForObject("SELECT amount FROM invoices WHERE po_id=?", Long.class, po2)).isGreaterThan(0L);

		jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, reason) VALUES (?, ?, -1000, 'ADJUST', 'test reset')", branch, beans);
	}
}
