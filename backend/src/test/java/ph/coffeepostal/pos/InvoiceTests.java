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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class InvoiceTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}


	/** Orders 2 bags of beans (PHP 850 each), HQ approves, sends, branch receives `received`. Returns the PO id. */
	private long poReceived(long received) throws Exception {
		var manager = login("manager"); var admin = login("admin");
		long beans = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Espresso%'", Long.class);
		String body = mvc.perform(post("/api/branch/purchase-orders").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"lines\":[{\"itemId\":" + beans + ",\"qty\":2}]}")).andReturn().getResponse().getContentAsString();
		long po = ((Number) JsonPath.read(body, "$.id")).longValue();
		long line = jdbc.queryForObject("SELECT id FROM branch_po_lines WHERE po_id=?", Long.class, po);
		mvc.perform(post("/api/admin/branch-pos/" + po + "/approve").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"lines\":[]}")).andExpect(status().isOk());
		mvc.perform(post("/api/admin/branch-pos/" + po + "/ship").session(admin).with(csrf())).andExpect(status().isOk());
		mvc.perform(post("/api/branch/purchase-orders/" + po + "/receive").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"lines\":[{\"lineId\":" + line + ",\"qty\":" + received + "}]}")).andExpect(status().isOk());
		return po;
	}

	@Test
	void invoiceIsForWhatArrivedAndNothingArrivedMeansNoInvoice() throws Exception {
		long full = poReceived(2);
		assertThat(jdbc.queryForObject("SELECT amount FROM invoices WHERE po_id=?", Long.class, full)).isEqualTo(170000L);   // 2 x 850.00
		assertThat(jdbc.queryForObject("SELECT due_date - (now() AT TIME ZONE 'Asia/Manila')::date FROM invoices WHERE po_id=?", Integer.class, full)).isEqualTo(15);

		long short1 = poReceived(1);
		assertThat(jdbc.queryForObject("SELECT amount FROM invoices WHERE po_id=?", Long.class, short1)).isEqualTo(85000L);

		long none = poReceived(0);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE po_id=?", Integer.class, none)).isZero();
	}

	@Test
	void partialPaymentsReduceTheBalanceAndOverpayingIsRefused() throws Exception {
		long po = poReceived(2);
		long inv = jdbc.queryForObject("SELECT id FROM invoices WHERE po_id=?", Long.class, po);
		var admin = login("admin"); var manager = login("manager");

		mvc.perform(post("/api/admin/receivables/" + inv + "/payments").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":\"1000\",\"method\":\"BANK\",\"reference\":\"TRX-1\"}")).andExpect(status().isOk());
		mvc.perform(get("/api/admin/receivables/" + inv).session(admin))
			.andExpect(jsonPath("$.invoice.paid").value(100000))
			.andExpect(jsonPath("$.invoice.balance").value(70000))
			.andExpect(jsonPath("$.invoice.status").value("PARTIAL"))
			.andExpect(jsonPath("$.lines[0].qty").value(2));

		// more than the balance, a future date, and a bad method are all refused
		mvc.perform(post("/api/admin/receivables/" + inv + "/payments").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":\"701\",\"method\":\"CASH\"}")).andExpect(status().isConflict());
		mvc.perform(post("/api/admin/receivables/" + inv + "/payments").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":\"100\",\"method\":\"CASH\",\"paidOn\":\"2999-01-01\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/admin/receivables/" + inv + "/payments").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":\"100\",\"method\":\"BITCOIN\"}")).andExpect(status().isBadRequest());

		// the branch can see it but not record payments
		mvc.perform(get("/api/branch/payables/" + inv).session(manager)).andExpect(status().isOk()).andExpect(jsonPath("$.payments.length()").value(1));
		mvc.perform(post("/api/admin/receivables/" + inv + "/payments").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":\"100\",\"method\":\"CASH\"}")).andExpect(status().isForbidden());

		// pay the rest: fully paid, and nothing more can be taken
		mvc.perform(post("/api/admin/receivables/" + inv + "/payments").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":\"700\",\"method\":\"CASH\"}")).andExpect(status().isOk());
		mvc.perform(get("/api/admin/receivables/" + inv).session(admin)).andExpect(jsonPath("$.invoice.status").value("PAID"));
		mvc.perform(post("/api/admin/receivables/" + inv + "/payments").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":\"1\",\"method\":\"CASH\"}")).andExpect(status().isConflict());
	}

	@Test
	void overdueInvoicesAreFlaggedAndCashiersSeeNoPayables() throws Exception {
		long po = poReceived(2);
		jdbc.update("UPDATE invoices SET due_date = (now() AT TIME ZONE 'Asia/Manila')::date - 4 WHERE po_id=?", po);
		long inv = jdbc.queryForObject("SELECT id FROM invoices WHERE po_id=?", Long.class, po);
		var admin = login("admin");

		mvc.perform(get("/api/admin/receivables/" + inv).session(admin))
			.andExpect(jsonPath("$.invoice.overdue").value(true))
			.andExpect(jsonPath("$.invoice.daysOverdue").value(4));
		mvc.perform(get("/api/admin/receivables?filter=overdue").session(admin))
			.andExpect(jsonPath("$[?(@.id==" + inv + ")]").isNotEmpty());
		mvc.perform(get("/api/admin/dashboard").session(admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.receivables.overdueCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
		mvc.perform(get("/api/branch/payables").session(login("cashier"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/receivables").session(login("manager"))).andExpect(status().isForbidden());
	}
}
