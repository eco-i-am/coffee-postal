package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
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
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	@Test
	void migrationsRunAndSeedRolesAndPermissions() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM roles WHERE name IN ('Super Admin','HQ Staff','Branch Owner','Branch Manager','Cashier')", Integer.class)).isEqualTo(5);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM modules", Integer.class)).isGreaterThan(30);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM role_permissions rp JOIN roles r ON r.id=rp.role_id WHERE r.name='Cashier'",
			Integer.class)).isEqualTo(11);   // 7 plus announcements, the support chat, customers and receiving deliveries
	}

	@Test
	void apiRequiresSignIn() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/branches")).andExpect(status().isUnauthorized());
	}

	@Test
	void adminSignsInAndMeShowsRoleAndPermissions() throws Exception {
		mvc.perform(formLogin("/api/auth/login").user("admin").password("coffee123")).andExpect(status().isNoContent());
		mvc.perform(get("/api/me").session(login("admin")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scope").value("ADMIN"))
			.andExpect(jsonPath("$.permissions").isArray());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE username='admin' AND success", Integer.class))
			.isGreaterThanOrEqualTo(1);
	}

	@Test
	void wrongPasswordIsRejectedAndRecorded() throws Exception {
		mvc.perform(formLogin("/api/auth/login").user("admin").password("nope")).andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE username='admin' AND NOT success", Integer.class))
			.isGreaterThanOrEqualTo(1);
	}

	@Test
	void cashierCannotUseHqApi() throws Exception {
		mvc.perform(get("/api/admin/branches").session(login("cashier"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/products").session(login("cashier"))).andExpect(status().isForbidden());
	}

	@Test
	void adminCreatesBranchAndItIsAudited() throws Exception {
		var session = login("admin");
		String json = "{\"code\":\"ceb-09\",\"name\":\"Test Branch\",\"address\":\"Cebu\",\"phone\":\"\",\"type\":\"kiosk\"}";
		mvc.perform(post("/api/admin/branches").session(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("CEB-09"))
			.andExpect(jsonPath("$.type").value("KIOSK"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action='BRANCH_CREATE'", Integer.class))
			.isGreaterThanOrEqualTo(1);
		// duplicate code is refused, not saved twice
		mvc.perform(post("/api/admin/branches").session(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.fields.code").exists());
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
		String json = "{\"name\":\"Hazelnut Latte\",\"categoryId\":" + cat + ",\"price\":\"125.50\",\"cost\":\"45\",\"art\":\"hot\",\"hasSizes\":true}";
		mvc.perform(post("/api/admin/products").session(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated());
		assertThat(jdbc.queryForObject("SELECT price FROM products WHERE name='Hazelnut Latte'", Long.class)).isEqualTo(12550L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM product_variants v JOIN products p ON p.id=v.product_id WHERE p.name='Hazelnut Latte'", Integer.class)).isEqualTo(2);
	}

	@Test
	void dashboardAndOrderListReflectASaleAndRespectPermissions() throws Exception {
		var cashier = login("cashier");
		long americano = jdbc.queryForObject("SELECT id FROM products WHERE name='Americano'", Long.class);
		String id = java.util.UUID.randomUUID().toString();
		String json = "{\"id\":\"" + id + "\",\"orderType\":\"TAKEOUT\",\"discountType\":\"NONE\","
			+ "\"paymentMethod\":\"GCASH\",\"reference\":\"R1\",\"lines\":[{\"productId\":" + americano + ",\"qty\":2}]}";
		mvc.perform(post("/api/pos/orders").session(cashier).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());

		// cashiers see the order list but not the dashboard
		mvc.perform(get("/api/branch/dashboard").session(cashier)).andExpect(status().isForbidden());
		mvc.perform(get("/api/branch/orders").session(cashier))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='" + id + "')].total").value(15800));
		mvc.perform(get("/api/branch/orders/" + id).session(cashier))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lines[0].qty").value(2))
			.andExpect(jsonPath("$.payments[0].method").value("GCASH"));
		mvc.perform(get("/api/branch/orders/" + java.util.UUID.randomUUID()).session(cashier)).andExpect(status().isNotFound());

		// the manager sees today's numbers
		mvc.perform(get("/api/branch/dashboard").session(login("manager")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.orders").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
			.andExpect(jsonPath("$.sales").value(org.hamcrest.Matchers.greaterThanOrEqualTo(15800)))
			.andExpect(jsonPath("$.hourly.length()").value(17));
		mvc.perform(get("/api/branch/orders?date=not-a-date").session(cashier)).andExpect(status().isBadRequest());
	}

}
