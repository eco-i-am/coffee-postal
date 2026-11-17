package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
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


	@Test
	void cashierSeesTheMenuForTheirBranch() throws Exception {
		mvc.perform(get("/api/pos/menu").session(login("cashier")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(13))
			.andExpect(jsonPath("$.categories[0]").value("Coffee"));
	}

	@Test
	void orderIsPricedByTheServerAndRetriesDoNotDoubleCharge() throws Exception {
		var session = login("cashier");
		long latte = jdbc.queryForObject("SELECT id FROM products WHERE name='Cafe Latte'", Long.class);
		long large = jdbc.queryForObject("SELECT id FROM product_variants WHERE product_id=? AND name='Large'", Long.class, latte);
		long shot = jdbc.queryForObject("SELECT id FROM addons WHERE name='Extra shot'", Long.class);
		String id = java.util.UUID.randomUUID().toString();
		// 99.00 + 20.00 (Large) + 25.00 (Extra shot) = 144.00; pays 150.00 cash -> change 6.00
		String json = "{\"id\":\"" + id + "\",\"orderType\":\"DINE_IN\",\"discountType\":\"NONE\","
			+ "\"paymentMethod\":\"CASH\",\"tendered\":15000,\"lines\":[{\"productId\":" + latte
			+ ",\"variantId\":" + large + ",\"addonIds\":[" + shot + "],\"qty\":1}]}";
		for (int i = 0; i < 2; i++) {   // the second call is a retry of the same sale
			mvc.perform(post("/api/pos/orders").session(session).with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(14400))
				.andExpect(jsonPath("$.change").value(600));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE id=?::uuid", Integer.class, id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT sum(unit_price) FROM order_lines WHERE order_id=?::uuid", Long.class, id)).isEqualTo(14400L);
	}

	@Test
	void cashShortOfTheTotalIsRefused() throws Exception {
		var session = login("cashier");
		long latte = jdbc.queryForObject("SELECT id FROM products WHERE name='Americano'", Long.class);
		String json = "{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"orderType\":\"TAKEOUT\",\"discountType\":\"NONE\","
			+ "\"paymentMethod\":\"CASH\",\"tendered\":100,\"lines\":[{\"productId\":" + latte + ",\"qty\":1}]}";
		mvc.perform(post("/api/pos/orders").session(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isBadRequest());
	}

	@Test
	void adminAddsAProductAndItAppearsOnTheMenu() throws Exception {
		var session = login("admin");
		long cat = jdbc.queryForObject("SELECT id FROM product_categories WHERE name='Coffee'", Long.class);
		mvc.perform(post("/admin/products").session(session).with(csrf())
				.param("name", "Hazelnut Latte").param("categoryId", String.valueOf(cat))
				.param("price", "125.50").param("cost", "45").param("art", "hot").param("hasSizes", "true"))
			.andExpect(redirectedUrl("/admin/products"));
		assertThat(jdbc.queryForObject("SELECT price FROM products WHERE name='Hazelnut Latte'", Long.class)).isEqualTo(12550L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM product_variants v JOIN products p ON p.id=v.product_id WHERE p.name='Hazelnut Latte'", Integer.class)).isEqualTo(2);
	}

}
