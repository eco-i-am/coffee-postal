package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
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
class PosApplicationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	@Test
	void migrationsRunAndSeedRolesAndPermissions() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM roles", Integer.class)).isEqualTo(5);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM modules", Integer.class)).isGreaterThan(30);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM role_permissions rp JOIN roles r ON r.id=rp.role_id WHERE r.name='Cashier'",
			Integer.class)).isEqualTo(6);
	}

	@Test
	void loginPageIsPublicAndAdminRequiresSignIn() throws Exception {
		mvc.perform(get("/login")).andExpect(status().isOk());
		mvc.perform(get("/admin")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
	}

	@Test
	void adminSignsInAndIsRoutedToHq() throws Exception {
		mvc.perform(formLogin("/login").user("admin").password("coffee123")).andExpect(redirectedUrl("/"));
		mvc.perform(get("/admin").session(login("admin"))).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE username='admin' AND success", Integer.class))
			.isGreaterThanOrEqualTo(1);
	}

	@Test
	void wrongPasswordIsRejectedAndRecorded() throws Exception {
		mvc.perform(formLogin("/login").user("admin").password("nope")).andExpect(redirectedUrl("/login?error"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE username='admin' AND NOT success", Integer.class))
			.isGreaterThanOrEqualTo(1);
	}

	@Test
	void cashierCannotOpenHqScreens() throws Exception {
		mvc.perform(get("/admin").session(login("cashier"))).andExpect(status().isForbidden());
		mvc.perform(get("/admin/branches").session(login("cashier"))).andExpect(status().isForbidden());
	}

	@Test
	void adminCreatesBranchAndItIsAudited() throws Exception {
		var session = login("admin");
		mvc.perform(post("/admin/branches").session(session).with(csrf())
				.param("code", "ceb-09").param("name", "Test Branch").param("address", "Cebu").param("phone", ""))
			.andExpect(redirectedUrl("/admin/branches"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM branches WHERE code='CEB-09'", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action='BRANCH_CREATE'", Integer.class))
			.isGreaterThanOrEqualTo(1);
		// duplicate code is refused, not saved twice
		mvc.perform(post("/admin/branches").session(session).with(csrf())
				.param("code", "CEB-09").param("name", "Again"))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM branches WHERE code='CEB-09'", Integer.class)).isEqualTo(1);
	}

}
