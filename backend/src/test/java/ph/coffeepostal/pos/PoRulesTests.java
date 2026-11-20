package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** The purchase order lead time: the delivery day must be at least the lead time (a setting) after the order is sent. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PoRulesTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private org.springframework.test.web.servlet.ResultActions send(MockHttpSession s, String json) throws Exception {
		return mvc.perform(post("/api/branch/purchase-orders").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	@Test
	void anEarlierDeliveryDayThanTheLeadTimeIsRefusedAndTheDefaultIsTheEarliest() throws Exception {
		jdbc.update("DELETE FROM settings WHERE key = 'po_lead_time_hours'");   // the default, 48 hours
		var manager = login("manager");
		long beans = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Espresso%'", Long.class);
		String lines = "\"lines\":[{\"itemId\":" + beans + ",\"qty\":1}]";
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Manila"));

		mvc.perform(get("/api/branch/purchase-orders/rules").session(manager)).andExpect(status().isOk())
			.andExpect(jsonPath("$.leadTimeHours").value(48));
		// tomorrow is too soon for 48 hours
		send(manager, "{" + lines + ",\"deliverOn\":\"" + today.plusDays(1) + "\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.deliverOn").exists());
		// three days out is fine, and is kept
		String ok = send(manager, "{" + lines + ",\"deliverOn\":\"" + today.plusDays(3) + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long po = ((Number) JsonPath.read(ok, "$.id")).longValue();
		assertThat(jdbc.queryForObject("SELECT deliver_on FROM branch_pos WHERE id = ?", java.sql.Date.class, po).toLocalDate()).isEqualTo(today.plusDays(3));
		// no day given: the earliest allowed one
		String d = send(manager, "{" + lines + "}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long po2 = ((Number) JsonPath.read(d, "$.id")).longValue();
		assertThat(jdbc.queryForObject("SELECT deliver_on FROM branch_pos WHERE id = ?", java.sql.Date.class, po2).toLocalDate()).isAfterOrEqualTo(today.plusDays(2));
	}

	@Test
	void hqCanShortenTheLeadTime() throws Exception {
		var manager = login("manager");
		var admin = login("admin");
		long beans = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Espresso%'", Long.class);
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Manila"));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/settings").session(admin).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"po_lead_time_hours\":\"0\"}")).andExpect(status().isOk());
		try {
			send(manager, "{\"lines\":[{\"itemId\":" + beans + ",\"qty\":1}],\"deliverOn\":\"" + today + "\"}").andExpect(status().isCreated());
		} finally {
			jdbc.update("DELETE FROM settings WHERE key = 'po_lead_time_hours'");
		}
	}
}
