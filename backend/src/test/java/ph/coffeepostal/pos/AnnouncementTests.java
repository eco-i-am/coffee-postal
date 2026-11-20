package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class AnnouncementTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private long create(MockHttpSession admin, String json) throws Exception {
		String body = mvc.perform(post("/api/admin/announcements").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private boolean cashierSees(MockHttpSession cashier, long id) throws Exception {
		String body = mvc.perform(get("/api/branch/announcements").session(cashier)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return !((java.util.List<?>) JsonPath.read(body, "$[?(@.id==" + id + ")]")).isEmpty();
	}

	@Test
	void hqSendsToAllAndTheBranchReadsIt() throws Exception {
		var admin = login("admin");
		var cashier = login("cashier");
		long id = create(admin, "{\"title\":\"New menu\",\"body\":\"Matcha is in.\",\"allBranches\":true}");
		assertThat(cashierSees(cashier, id)).isTrue();
		mvc.perform(get("/api/branch/announcements").session(cashier))
			.andExpect(jsonPath("$[?(@.id==" + id + ")].read").value(false));
		mvc.perform(post("/api/branch/announcements/" + id + "/read").session(cashier).with(csrf())).andExpect(status().isOk());
		mvc.perform(get("/api/branch/announcements").session(cashier))
			.andExpect(jsonPath("$[?(@.id==" + id + ")].read").value(true));
		mvc.perform(get("/api/admin/announcements").session(admin))
			.andExpect(jsonPath("$[?(@.id==" + id + ")].readers").value(1));
	}

	@Test
	void anAnnouncementForAnotherBranchIsNotSeen() throws Exception {
		var admin = login("admin");
		var cashier = login("cashier");
		long mine = jdbc.queryForObject("SELECT ub.branch_id FROM user_branches ub JOIN users u ON u.id = ub.user_id WHERE u.username = 'cashier' LIMIT 1", Long.class);
		long other = jdbc.queryForObject("SELECT id FROM branches WHERE id <> ? ORDER BY id LIMIT 1", Long.class, mine);
		long forOther = create(admin, "{\"title\":\"Elsewhere\",\"body\":\"Not for you.\",\"allBranches\":false,\"branchIds\":[" + other + "]}");
		long forMine = create(admin, "{\"title\":\"Yours\",\"body\":\"For you.\",\"allBranches\":false,\"branchIds\":[" + mine + "]}");
		assertThat(cashierSees(cashier, forOther)).isFalse();
		assertThat(cashierSees(cashier, forMine)).isTrue();
		// reading one that is not theirs is refused
		mvc.perform(post("/api/branch/announcements/" + forOther + "/read").session(cashier).with(csrf())).andExpect(status().isNotFound());
	}

	@Test
	void takingItDownHidesItAndChosenBranchesAreRequired() throws Exception {
		var admin = login("admin");
		var cashier = login("cashier");
		long id = create(admin, "{\"title\":\"Oops\",\"body\":\"Wrong.\",\"allBranches\":true}");
		mvc.perform(delete("/api/admin/announcements/" + id).session(admin).with(csrf())).andExpect(status().isOk());
		assertThat(cashierSees(cashier, id)).isFalse();
		mvc.perform(post("/api/admin/announcements").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"x\",\"body\":\"y\",\"allBranches\":false,\"branchIds\":[]}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.branchIds").exists());
		// a cashier cannot send
		mvc.perform(post("/api/admin/announcements").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"x\",\"body\":\"y\",\"allBranches\":true}"))
			.andExpect(status().isForbidden());
	}
}
