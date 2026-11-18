package ph.coffeepostal.pos;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class LogTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		return (MockHttpSession) mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn().getRequest().getSession(false);
	}

	@Test
	void auditTrailAndLoginHistoryAreReadableByHqOnlyAndFilterWork() throws Exception {
		var admin = login("admin");
		// make something to audit and a failed sign-in to find
		mvc.perform(post("/api/admin/roles").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Audit Probe\",\"scope\":\"BRANCH\",\"permissions\":[]}")).andExpect(status().isCreated());
		mvc.perform(formLogin("/api/auth/login").user("ghost.user").password("nope")).andExpect(status().isUnauthorized());

		mvc.perform(get("/api/admin/logs/audit?action=ROLE_CREATE").session(admin)).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").isNumber())
			.andExpect(jsonPath("$.rows[0].action").value("ROLE_CREATE"))
			.andExpect(jsonPath("$.rows[0].user").value("Head Office Admin"));
		mvc.perform(get("/api/admin/logs/audit?user=zzzz-nobody").session(admin)).andExpect(jsonPath("$.total").value(0));
		mvc.perform(get("/api/admin/logs/audit/actions").session(admin)).andExpect(jsonPath("$").isArray());

		mvc.perform(get("/api/admin/logs/logins?result=failed&user=ghost").session(admin)).andExpect(status().isOk())
			.andExpect(jsonPath("$.rows[0].username").value("ghost.user"))
			.andExpect(jsonPath("$.rows[0].success").value(false));
		mvc.perform(get("/api/admin/logs/logins?result=success").session(admin)).andExpect(jsonPath("$.rows[0].success").value(true));
		mvc.perform(get("/api/admin/logs/logins?from=2020-02-01&to=2020-01-01").session(admin)).andExpect(status().isBadRequest());

		for (String u : new String[] { "manager", "cashier" }) {
			mvc.perform(get("/api/admin/logs/audit").session(login(u))).andExpect(status().isForbidden());
			mvc.perform(get("/api/admin/logs/logins").session(login(u))).andExpect(status().isForbidden());
		}
	}
}
