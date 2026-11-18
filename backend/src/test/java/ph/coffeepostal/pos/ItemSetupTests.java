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
import ph.coffeepostal.pos.inventory.InventoryService;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ItemSetupTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired InventoryService inventory;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private String json(org.springframework.test.web.servlet.RequestBuilder b) throws Exception {
		return mvc.perform(b).andReturn().getResponse().getContentAsString();
	}

	@Test
	void categoriesUnitsAndConversionsWork() throws Exception {
		var admin = login("admin");
		String suffix = String.valueOf(System.nanoTime() % 100000);
		String cat = "Packaging " + suffix;

		// only HQ staff with the module can set these up
		mvc.perform(get("/api/admin/item-categories").session(login("manager"))).andExpect(status().isForbidden());

		String c = json(post("/api/admin/item-categories").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + cat + "\"}"));
		long catId = ((Number) JsonPath.read(c, "$.id")).longValue();
		mvc.perform(post("/api/admin/item-categories").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + cat.toUpperCase() + "\"}"))
			.andExpect(status().isConflict());

		// an item in that category; its unit joins the units list
		String name = "Cup lids " + suffix;
		String it = json(post("/api/admin/items").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + name + "\",\"unit\":\"pc" + suffix + "\",\"cost\":\"2.50\",\"reorderLevel\":0,\"categoryId\":" + catId + "}"));
		long itemId = ((Number) JsonPath.read(it, "$.id")).longValue();
		mvc.perform(get("/api/admin/items").session(admin)).andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id==" + itemId + ")].category").value(cat));
		mvc.perform(get("/api/admin/units").session(admin)).andExpect(jsonPath("$[?(@=='pc" + suffix + "')]").exists());
		// a category that does not exist is refused
		mvc.perform(post("/api/admin/items/" + itemId).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"unit\":\"pc" + suffix + "\",\"cost\":\"2.50\",\"reorderLevel\":0,\"categoryId\":999999}")).andExpect(status().isBadRequest());

		// 1 sleeve = 50 pcs, 1 box = 0.5 of a pc is nonsense but allowed to be fractional; zero is not
		mvc.perform(put("/api/admin/items/" + itemId + "/conversions").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("[{\"unit\":\"sleeve\",\"factor\":\"0\"}]")).andExpect(status().isBadRequest());
		mvc.perform(put("/api/admin/items/" + itemId + "/conversions").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("[{\"unit\":\"pc" + suffix + "\",\"factor\":\"1\"}]")).andExpect(status().isBadRequest());
		mvc.perform(put("/api/admin/items/" + itemId + "/conversions").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("[{\"unit\":\"sleeve\",\"factor\":\"50\"},{\"unit\":\"SLEEVE\",\"factor\":\"40\"}]")).andExpect(status().isBadRequest());
		mvc.perform(put("/api/admin/items/" + itemId + "/conversions").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("[{\"unit\":\"sleeve\",\"factor\":\"50\"},{\"unit\":\"carton\",\"factor\":\"1000.5\"}]"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

		// 3 sleeves = 150 pcs = 150000 thousandths; 2.5 sleeves = 125 pcs; the item's own unit passes straight through
		assertThat(inventory.toBase(itemId, 3000, "sleeve")).isEqualTo(150_000);
		assertThat(inventory.toBase(itemId, 2500, "Sleeve")).isEqualTo(125_000);
		assertThat(inventory.toBase(itemId, 7000, "pc" + suffix)).isEqualTo(7000);
		assertThat(inventory.toBase(itemId, 7000, null)).isEqualTo(7000);
		org.junit.jupiter.api.Assertions.assertThrows(ph.coffeepostal.pos.shared.ApiException.class, () -> inventory.toBase(itemId, 1000, "pallet"));

		// leave the shared catalogue as it was
		jdbc.update("UPDATE items SET active = FALSE WHERE id = ?", itemId);
	}
}
