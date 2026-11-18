package ph.coffeepostal.pos;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class UserManagementTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user, String password) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password(password)).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private long role(String name) { return jdbc.queryForObject("SELECT id FROM roles WHERE name = ?", Long.class, name); }
	private long branch(String code) { return jdbc.queryForObject("SELECT id FROM branches WHERE code = ?", Long.class, code); }

	private String user(String username, String name, String role, String branchCode, String password) {
		return "{\"username\":\"" + username + "\",\"fullName\":\"" + name + "\",\"roleId\":" + role(role)
			+ (branchCode == null ? "" : ",\"branchId\":" + branch(branchCode)) + ",\"password\":\"" + password + "\"}";
	}

	private long create(MockHttpSession by, String url, String json) throws Exception {
		String body = mvc.perform(post(url).session(by).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	@Test
	void hqCreatesStaffWhoCanSignInAndBadInputIsRefused() throws Exception {
		var admin = login("admin", "coffee123");
		create(admin, "/api/admin/users", user("ana.c", "Ana Cruz", "Cashier", "CEB-02", "Brew2026x1"));
		login("ana.c", "Brew2026x1");   // can sign in
		mvc.perform(get("/api/me").session(login("ana.c", "Brew2026x1"))).andExpect(status().isOk());

		String dup = user("ana.c", "Another", "Cashier", "CEB-02", "Brew2026x1");
		mvc.perform(post("/api/admin/users").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(dup)).andExpect(status().isConflict());
		String weak = user("ben.w", "Ben W", "Cashier", "CEB-02", "short");
		mvc.perform(post("/api/admin/users").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(weak))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.password").exists());
		String noBranch = user("ben.x", "Ben X", "Cashier", null, "Brew2026x1");
		mvc.perform(post("/api/admin/users").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(noBranch))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.branchId").exists());
		mvc.perform(get("/api/admin/users").session(login("cashier", "coffee123"))).andExpect(status().isForbidden());
	}

	@Test
	void fiveWrongPasswordsLockTheAccountUntilHqUnlocksIt() throws Exception {
		var admin = login("admin", "coffee123");
		long id = create(admin, "/api/admin/users", user("lock.me", "Lock Me", "Cashier", "CEB-02", "Brew2026x1"));
		for (int i = 0; i < 5; i++) mvc.perform(formLogin("/api/auth/login").user("lock.me").password("wrong")).andExpect(status().isUnauthorized());
		mvc.perform(formLogin("/api/auth/login").user("lock.me").password("Brew2026x1")).andExpect(status().isUnauthorized());   // right password, still locked
		mvc.perform(post("/api/admin/users/" + id + "/unlock").session(admin).with(csrf())).andExpect(status().isOk());
		mvc.perform(formLogin("/api/auth/login").user("lock.me").password("Brew2026x1")).andExpect(status().isNoContent());
	}

	@Test
	void disablingSignsTheUserOutAtOnceAndResetPasswordEndsTheirSessions() throws Exception {
		var admin = login("admin", "coffee123");
		long id = create(admin, "/api/admin/users", user("gone.soon", "Gone Soon", "Cashier", "CEB-02", "Brew2026x1"));
		var theirs = login("gone.soon", "Brew2026x1");
		mvc.perform(get("/api/me").session(theirs)).andExpect(status().isOk());
		mvc.perform(post("/api/admin/users/" + id + "/password").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"Newbrew2027\"}")).andExpect(status().isOk());
		mvc.perform(get("/api/me").session(theirs)).andExpect(status().isUnauthorized());   // old session ended
		var again = login("gone.soon", "Newbrew2027");
		mvc.perform(get("/api/me").session(again)).andExpect(status().isOk());

		mvc.perform(put("/api/admin/users/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"fullName\":\"Gone Soon\",\"status\":\"DISABLED\"}")).andExpect(status().isOk());
		mvc.perform(get("/api/me").session(again)).andExpect(status().isUnauthorized());
		mvc.perform(formLogin("/api/auth/login").user("gone.soon").password("Newbrew2027")).andExpect(status().isUnauthorized());
	}

	@Test
	void nobodyCanDisableThemselvesAndSuperAdminIsProtected() throws Exception {
		var admin = login("admin", "coffee123");
		long me = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", Long.class);
		mvc.perform(put("/api/admin/users/" + me).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"fullName\":\"Admin\",\"status\":\"DISABLED\"}")).andExpect(status().isForbidden());
		mvc.perform(put("/api/admin/roles/" + role("Super Admin")).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Super Admin\",\"permissions\":[]}")).andExpect(status().isForbidden());
		mvc.perform(delete("/api/admin/roles/" + role("Super Admin")).session(admin).with(csrf())).andExpect(status().isForbidden());
	}

	@Test
	void branchOwnerManagesOnlyTheirOwnBranchAndOnlyRolesNoStrongerThanTheirs() throws Exception {
		var admin = login("admin", "coffee123");
		create(admin, "/api/admin/users", user("owner.mdu", "Owner Mandaue", "Branch Owner", "CEB-02", "Brew2026x1"));
		long other = create(admin, "/api/admin/users", user("cashier.cebu", "Cebu Cashier", "Cashier", "CEB-01", "Brew2026x1"));
		var owner = login("owner.mdu", "Brew2026x1");

		long mine = create(owner, "/api/branch/users", user("mdu.cash", "Mandaue Cashier", "Cashier", null, "Brew2026x1"));   // lands in the owner's branch
		mvc.perform(get("/api/branch/users").session(owner)).andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.username=='mdu.cash')].branch").value("Mandaue"))
			.andExpect(jsonPath("$[?(@.username=='cashier.cebu')]").isEmpty());
		mvc.perform(put("/api/branch/users/" + other).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"fullName\":\"Hacked\",\"status\":\"DISABLED\"}")).andExpect(status().isNotFound());   // another branch's staff
		mvc.perform(post("/api/branch/users/" + mine + "/pin").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"pin\":\"2468\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/branch/users/" + mine + "/pin").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"pin\":\"1234\"}")).andExpect(status().isBadRequest());   // too easy

		// an HQ role is out of reach
		mvc.perform(post("/api/branch/users").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(user("sneaky", "Sneaky", "HQ Staff", null, "Brew2026x1"))).andExpect(status().isForbidden());
		// and a cashier has no user management at all
		mvc.perform(get("/api/branch/users").session(login("cashier", "coffee123"))).andExpect(status().isForbidden());
		// the assignable list never offers HQ roles
		mvc.perform(get("/api/branch/users/roles").session(owner)).andExpect(jsonPath("$[?(@.scope=='ADMIN')]").isEmpty());
	}

	@Test
	void customRolesFollowTheRules() throws Exception {
		var admin = login("admin", "coffee123");
		String body = "{\"name\":\"Barista Lead\",\"scope\":\"BRANCH\",\"description\":\"Runs the floor\",\"permissions\":["
			+ "{\"module\":\"pos\",\"view\":false,\"add\":true,\"edit\":false,\"delete\":false}]}";
		long id = create(admin, "/api/admin/roles", body);
		// add implies view
		mvc.perform(get("/api/admin/roles/" + id).session(admin)).andExpect(jsonPath("$.permissions[0].view").value(true));
		mvc.perform(post("/api/admin/roles").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());   // name taken
		String hq = "{\"name\":\"Bad Branch Role\",\"scope\":\"BRANCH\",\"permissions\":[{\"module\":\"roles\",\"view\":true}]}";
		mvc.perform(post("/api/admin/roles").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(hq)).andExpect(status().isBadRequest());

		// a role with people in it cannot be deleted; an empty one can
		create(admin, "/api/admin/users", user("lead.one", "Lead One", "Barista Lead", "CEB-02", "Brew2026x1"));
		mvc.perform(delete("/api/admin/roles/" + id).session(admin).with(csrf())).andExpect(status().isConflict());
		long empty = create(admin, "/api/admin/roles", "{\"name\":\"Temp Role\",\"scope\":\"BRANCH\",\"permissions\":[]}");
		mvc.perform(delete("/api/admin/roles/" + empty).session(admin).with(csrf())).andExpect(status().isOk());

		// editing a role's permissions signs everyone out so the change applies
		var lead = login("lead.one", "Brew2026x1");
		mvc.perform(get("/api/me").session(lead)).andExpect(status().isOk());
		mvc.perform(put("/api/admin/roles/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Barista Lead\",\"permissions\":[{\"module\":\"pos\",\"view\":true,\"add\":true,\"edit\":false,\"delete\":false}]}")).andExpect(status().isOk());
		mvc.perform(get("/api/me").session(lead)).andExpect(status().isUnauthorized());
	}

	@Test
	void changingYourOwnPasswordNeedsTheCurrentOneAndKeepsYouSignedIn() throws Exception {
		var admin = login("admin", "coffee123");
		create(admin, "/api/admin/users", user("pw.user", "Pw User", "Cashier", "CEB-02", "Brew2026x1"));
		var a = login("pw.user", "Brew2026x1");
		var b = login("pw.user", "Brew2026x1");
		mvc.perform(post("/api/me/password").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"current\":\"nope\",\"next\":\"Fresh2027abc\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/me/password").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"current\":\"Brew2026x1\",\"next\":\"short\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/me/password").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"current\":\"Brew2026x1\",\"next\":\"Fresh2027abc\"}")).andExpect(status().isOk());
		mvc.perform(get("/api/me").session(a)).andExpect(status().isOk());             // this session carries on
		mvc.perform(get("/api/me").session(b)).andExpect(status().isUnauthorized());   // the other one ends
		login("pw.user", "Fresh2027abc");
	}
}
