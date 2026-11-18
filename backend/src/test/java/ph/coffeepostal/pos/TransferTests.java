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
import ph.coffeepostal.pos.inventory.TransferService;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class TransferTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired TransferService transfers;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private long stock(String branch, long item) {
		return jdbc.queryForObject("SELECT coalesce(sum(qty),0) FROM stock_movements WHERE item_id=? AND branch_id=(SELECT id FROM branches WHERE code=?)", Long.class, item, branch);
	}

	private long send(MockHttpSession s, String json, int expect) throws Exception {
		var r = mvc.perform(post("/api/branch/transfers").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
		return expect == 201 ? ((Number) JsonPath.read(r, "$.id")).longValue() : -1;
	}

	@Test
	void stockLeavesTheSenderAtOnceAndReachesTheOtherBranchOnlyWhenItConfirms() throws Exception {
		var manager = login("manager");
		long beans = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Espresso%'", Long.class);
		long cebu2 = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-02'", Long.class);
		long cebu1 = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-01'", Long.class);
		long cebu2User = jdbc.queryForObject("SELECT id FROM users WHERE username='manager'", Long.class);
		long a0 = stock("CEB-01", beans), b0 = stock("CEB-02", beans);

		mvc.perform(get("/api/branch/transfers").session(login("cashier"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/transfers").session(manager)).andExpect(status().isForbidden());
		mvc.perform(get("/api/branch/transfers/branches").session(manager)).andExpect(jsonPath("$[?(@.id==" + cebu2 + ")]").exists())
			.andExpect(jsonPath("$[?(@.id==" + cebu1 + ")]").isEmpty());

		// rules: not to yourself, something to send, more than you have
		send(manager, "{\"toBranchId\":" + cebu1 + ",\"lines\":[{\"itemId\":" + beans + ",\"qty\":1}]}", 400);
		send(manager, "{\"toBranchId\":" + cebu2 + ",\"lines\":[]}", 400);
		send(manager, "{\"toBranchId\":" + cebu2 + ",\"lines\":[{\"itemId\":" + beans + ",\"qty\":0}]}", 400);
		send(manager, "{\"toBranchId\":" + cebu2 + ",\"lines\":[{\"itemId\":" + beans + ",\"qty\":99999}]}", 409);
		assertThat(stock("CEB-01", beans)).isEqualTo(a0);   // a refused transfer takes nothing

		// send 2 and a half: the sender drops now, the receiver has not got it yet
		long id = send(manager, "{\"toBranchId\":" + cebu2 + ",\"note\":\"top up\",\"lines\":[{\"itemId\":" + beans + ",\"qty\":2.5}]}", 201);
		assertThat(stock("CEB-01", beans)).isEqualTo(a0 - 2500);
		assertThat(stock("CEB-02", beans)).isEqualTo(b0);
		mvc.perform(get("/api/admin/transfers").session(login("admin"))).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("SENT")).andExpect(jsonPath("$[0].lines[0].qty").value(2500));

		// the sending branch cannot confirm its own transfer; the other branch can, once
		mvc.perform(post("/api/branch/transfers/" + id + "/receive").session(manager).with(csrf())).andExpect(status().isNotFound());
		transfers.receive(id, cebu2, cebu2User);
		assertThat(stock("CEB-02", beans)).isEqualTo(b0 + 2500);
		org.junit.jupiter.api.Assertions.assertThrows(ph.coffeepostal.pos.shared.ApiException.class, () -> transfers.receive(id, cebu2, cebu2User));
		assertThat(stock("CEB-02", beans)).isEqualTo(b0 + 2500);   // not counted twice
		mvc.perform(post("/api/branch/transfers/" + id + "/cancel").session(manager).with(csrf())).andExpect(status().isConflict());

		// a cancelled transfer returns the stock to the sender and can no longer be received
		long id2 = send(manager, "{\"toBranchId\":" + cebu2 + ",\"lines\":[{\"itemId\":" + beans + ",\"qty\":1}]}", 201);
		assertThat(stock("CEB-01", beans)).isEqualTo(a0 - 3500);
		mvc.perform(post("/api/branch/transfers/" + id2 + "/cancel").session(manager).with(csrf())).andExpect(status().isOk());
		assertThat(stock("CEB-01", beans)).isEqualTo(a0 - 2500);
		org.junit.jupiter.api.Assertions.assertThrows(ph.coffeepostal.pos.shared.ApiException.class, () -> transfers.receive(id2, cebu2, cebu2User));

		// put the shared stock back so other tests see what they expect
		jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, reason) VALUES (?, ?, 2500, 'ADJUST', 'test reset')", cebu1, beans);
		jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, reason) VALUES (?, ?, -2500, 'ADJUST', 'test reset')", cebu2, beans);
	}
}
