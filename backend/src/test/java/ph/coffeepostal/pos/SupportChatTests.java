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
class SupportChatTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private String json(MockHttpSession s, String method, String url, String body) throws Exception {
		var req = method.equals("GET") ? get(url) : post(url);
		req.session(s).with(csrf());
		if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
		return mvc.perform(req).andReturn().getResponse().getContentAsString();
	}

	@Test
	void aBranchAsksHqAnswersAndEachSideSeesTheUnreadCount() throws Exception {
		var cashier = login("cashier");
		var admin = login("admin");
		mvc.perform(post("/api/branch/support").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"subject\":\"Printer\",\"body\":\"The receipt printer is jammed.\"}"))
			.andExpect(status().isCreated());
		long id = jdbc.queryForObject("SELECT max(id) FROM support_threads", Long.class);

		// HQ has one unread, the branch none
		assertThat(((Number) JsonPath.read(json(admin, "GET", "/api/admin/support/unread", null), "$.count")).intValue()).isGreaterThanOrEqualTo(1);
		mvc.perform(get("/api/admin/support/" + id).session(admin)).andExpect(status().isOk())
			.andExpect(jsonPath("$.messages.length()").value(1)).andExpect(jsonPath("$.messages[0].fromHq").value(false));
		assertThat(jdbc.queryForObject("SELECT hq_unread FROM support_threads WHERE id = ?", Integer.class, id)).isZero();

		// HQ replies: the branch has one unread until it opens the thread
		mvc.perform(post("/api/admin/support/" + id + "/messages").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\":\"Try the paper door, we will send a new roll.\"}")).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT branch_unread FROM support_threads WHERE id = ?", Integer.class, id)).isEqualTo(1);
		mvc.perform(get("/api/branch/support/" + id).session(cashier)).andExpect(status().isOk())
			.andExpect(jsonPath("$.messages.length()").value(2)).andExpect(jsonPath("$.messages[1].fromHq").value(true));
		assertThat(jdbc.queryForObject("SELECT branch_unread FROM support_threads WHERE id = ?", Integer.class, id)).isZero();
	}

	@Test
	void closedThreadsReopenWhenTheBranchWritesAndABranchCannotOpenAnotherBranchsThread() throws Exception {
		var cashier = login("cashier");
		var admin = login("admin");
		mvc.perform(post("/api/branch/support").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"subject\":\"Menu\",\"body\":\"Where is the new menu?\"}")).andExpect(status().isCreated());
		long id = jdbc.queryForObject("SELECT max(id) FROM support_threads", Long.class);
		mvc.perform(post("/api/admin/support/" + id + "/close").session(admin).with(csrf())).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT status FROM support_threads WHERE id = ?", String.class, id)).isEqualTo("CLOSED");
		mvc.perform(post("/api/branch/support/" + id + "/messages").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"Still need it.\"}")).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT status FROM support_threads WHERE id = ?", String.class, id)).isEqualTo("OPEN");

		// a thread from another branch is not found for this cashier
		long mine = jdbc.queryForObject("SELECT branch_id FROM support_threads WHERE id = ?", Long.class, id);
		long other = jdbc.queryForObject("SELECT id FROM branches WHERE id <> ? ORDER BY id LIMIT 1", Long.class, mine);
		long admin1 = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", Long.class);
		long foreign = jdbc.queryForObject("INSERT INTO support_threads(branch_id, subject, created_by) VALUES (?, 'Other', ?) RETURNING id", Long.class, other, admin1);
		mvc.perform(get("/api/branch/support/" + foreign).session(cashier)).andExpect(status().isNotFound());
		// a cashier cannot use the HQ side
		mvc.perform(get("/api/admin/support").session(cashier)).andExpect(status().isForbidden());
	}
}
