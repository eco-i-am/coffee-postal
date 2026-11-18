package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
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
class CatalogEditTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private long create(MockHttpSession s, String url, String json) throws Exception {
		String body = mvc.perform(post(url).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	@Test
	void headersGroupTheMenuAndProductEditsAreAuditedAndGuarded() throws Exception {
		var admin = login("admin");
		String tag = UUID.randomUUID().toString().substring(0, 6);
		long header = create(admin, "/api/admin/headers", "{\"name\":\"Hdr " + tag + "\"}");
		mvc.perform(post("/api/admin/headers").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"hdr " + tag + "\"}")).andExpect(status().isConflict());

		long cat = create(admin, "/api/admin/categories", "{\"name\":\"Cat " + tag + "\"}");
		mvc.perform(put("/api/admin/categories/" + cat).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Cat " + tag + "\",\"headerId\":" + header + ",\"sortOrder\":99,\"active\":true}")).andExpect(status().isOk());

		long prod = create(admin, "/api/admin/products", "{\"name\":\"Prod " + tag + "\",\"categoryId\":" + cat + ",\"price\":\"100\",\"cost\":\"0\",\"hasSizes\":false,\"art\":\"hot\"}");

		// edit price and cost: audited with the old and new price
		mvc.perform(put("/api/admin/products/" + prod).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Prod " + tag + "\",\"categoryId\":" + cat + ",\"price\":\"120.50\",\"cost\":\"30\",\"art\":\"iced\",\"hasSizes\":true,\"published\":true}"))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", Long.class, prod)).isEqualTo(12050L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM product_variants WHERE product_id = ?", Integer.class, prod)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action='PRODUCT_PRICE_CHANGE' AND entity_id = ?", Integer.class, String.valueOf(prod))).isEqualTo(1);

		// published product shows on the cashier's menu with its header
		var cashier = login("cashier");
		String menu = mvc.perform(get("/api/pos/menu").session(cashier)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		java.util.List<String> headers = JsonPath.read(menu, "$.items[?(@.name=='Prod " + tag + "')].header");
		assertThat(headers).hasSize(1);
		assertThat(headers.get(0)).isEqualTo("Hdr " + tag);

		// a draft is hidden
		mvc.perform(put("/api/admin/products/" + prod).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Prod " + tag + "\",\"categoryId\":" + cat + ",\"price\":\"120.50\",\"cost\":\"30\",\"art\":\"iced\",\"hasSizes\":true,\"published\":false}"))
			.andExpect(status().isOk());
		menu = mvc.perform(get("/api/pos/menu").session(cashier)).andReturn().getResponse().getContentAsString();
		assertThat((java.util.List<?>) JsonPath.read(menu, "$.items[?(@.name=='Prod " + tag + "')]")).isEmpty();

		// bad input and missing things
		mvc.perform(put("/api/admin/products/" + prod).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Americano\",\"categoryId\":" + cat + ",\"price\":\"1\",\"hasSizes\":false,\"published\":true,\"art\":\"hot\"}")).andExpect(status().isConflict());
		mvc.perform(put("/api/admin/products/999999").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"X\",\"categoryId\":" + cat + ",\"price\":\"1\",\"hasSizes\":false,\"published\":true,\"art\":\"hot\"}")).andExpect(status().isNotFound());

		// cashiers cannot edit
		mvc.perform(put("/api/admin/products/" + prod).session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Z\",\"categoryId\":" + cat + ",\"price\":\"1\",\"hasSizes\":false,\"published\":true,\"art\":\"hot\"}")).andExpect(status().isForbidden());
	}

	@Test
	void addonsAndPerBranchPriceAndAvailabilityReachTheMenu() throws Exception {
		var admin = login("admin");
		var cashier = login("cashier");
		String tag = UUID.randomUUID().toString().substring(0, 6);
		long addon = create(admin, "/api/admin/addons", "{\"name\":\"Add " + tag + "\",\"price\":\"15.50\",\"active\":true}");
		mvc.perform(post("/api/admin/addons").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"add " + tag + "\",\"price\":\"1\",\"active\":true}")).andExpect(status().isConflict());
		assertThat(jdbc.queryForObject("SELECT price FROM addons WHERE id = ?", Long.class, addon)).isEqualTo(1550L);

		long cat = jdbc.queryForObject("SELECT id FROM product_categories WHERE name='Coffee'", Long.class);
		long prod = create(admin, "/api/admin/products", "{\"name\":\"Br " + tag + "\",\"categoryId\":" + cat + ",\"price\":\"100\",\"cost\":\"0\",\"hasSizes\":false,\"art\":\"hot\"}");
		mvc.perform(put("/api/admin/products/" + prod + "/addons").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"addonIds\":[" + addon + "]}")).andExpect(status().isOk());
		String menu = mvc.perform(get("/api/pos/menu").session(cashier)).andReturn().getResponse().getContentAsString();
		java.util.List<Integer> prices = JsonPath.read(menu, "$.items[?(@.name=='Br " + tag + "')].addons[0].price");
		assertThat(prices).containsExactly(1550);

		long branch = jdbc.queryForObject("SELECT ub.branch_id FROM user_branches ub JOIN users u ON u.id = ub.user_id WHERE u.username='cashier'", Long.class);
		mvc.perform(put("/api/admin/products/" + prod + "/branches").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"branches\":[{\"branchId\":" + branch + ",\"available\":true,\"price\":\"90\"}]}")).andExpect(status().isOk());
		menu = mvc.perform(get("/api/pos/menu").session(cashier)).andReturn().getResponse().getContentAsString();
		java.util.List<Integer> branchPrice = JsonPath.read(menu, "$.items[?(@.name=='Br " + tag + "')].price");
		assertThat(branchPrice).containsExactly(9000);

		mvc.perform(put("/api/admin/products/" + prod + "/branches").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"branches\":[{\"branchId\":" + branch + ",\"available\":false}]}")).andExpect(status().isOk());
		menu = mvc.perform(get("/api/pos/menu").session(cashier)).andReturn().getResponse().getContentAsString();
		java.util.List<?> gone = JsonPath.read(menu, "$.items[?(@.name=='Br " + tag + "')]");
		assertThat(gone).isEmpty();

		// back to the HQ price: no row left
		mvc.perform(put("/api/admin/products/" + prod + "/branches").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"branches\":[{\"branchId\":" + branch + ",\"available\":true}]}")).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM branch_products WHERE product_id = ?", Integer.class, prod)).isZero();

		mvc.perform(put("/api/admin/products/" + prod + "/branches").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"branches\":[]}")).andExpect(status().isForbidden());
		jdbc.update("UPDATE products SET active = FALSE WHERE id = ?", prod);   // the database is shared: keep other tests' menu counts steady
		mvc.perform(put("/api/admin/products/999999/addons").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"addonIds\":[]}")).andExpect(status().isNotFound());
	}
}
