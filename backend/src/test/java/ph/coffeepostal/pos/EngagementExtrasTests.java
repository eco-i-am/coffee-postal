package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

/** Branch customers, training and settings. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class EngagementExtrasTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private long idOf(String body) { return ((Number) JsonPath.read(body, "$.id")).longValue(); }

	@Test
	void aBranchKeepsItsOwnCustomerList() throws Exception {
		var cashier = login("cashier");
		String made = mvc.perform(post("/api/branch/customers").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Ana Cruz\",\"phone\":\"0917 000 0000\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long id = idOf(made);
		// the same name again is refused, whatever the capitals
		mvc.perform(post("/api/branch/customers").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"ana cruz\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.fields.name").exists());
		mvc.perform(get("/api/branch/customers?q=ana").session(cashier)).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id==" + id + ")].orders").value(0));
		mvc.perform(get("/api/branch/customers/" + id + "/orders").session(cashier)).andExpect(status().isOk());
		// a cashier can register but not edit or delete
		mvc.perform(put("/api/branch/customers/" + id).session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Ana C\"}"))
			.andExpect(status().isForbidden());
		mvc.perform(delete("/api/branch/customers/" + id).session(cashier).with(csrf())).andExpect(status().isForbidden());
	}

	@Test
	void hqPublishesTrainingAndABranchUserMarksItDone() throws Exception {
		var admin = login("admin");
		var cashier = login("cashier");
		long link = idOf(mvc.perform(post("/api/admin/training").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Latte art\",\"kind\":\"video\",\"url\":\"https://example.com/latte\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		// a video needs a proper link
		mvc.perform(post("/api/admin/training").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Bad\",\"kind\":\"video\",\"url\":\"javascript:alert(1)\"}")).andExpect(status().isBadRequest());
		// a document with a file
		long doc = idOf(mvc.perform(post("/api/admin/training").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Opening checklist\",\"kind\":\"document\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		mvc.perform(put("/api/admin/training/" + doc + "/file?name=checklist.pdf").session(admin).with(csrf()).contentType("application/pdf").content(new byte[] {37, 80, 68, 70}))
			.andExpect(status().isOk());
		mvc.perform(put("/api/admin/training/" + doc + "/file?name=x.exe").session(admin).with(csrf()).contentType("application/x-msdownload").content(new byte[] {1, 2}))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/branch/training/" + doc + "/file").session(cashier)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));

		mvc.perform(get("/api/branch/training").session(cashier)).andExpect(jsonPath("$[?(@.id==" + link + ")].mine").value(false));
		mvc.perform(post("/api/branch/training/" + link + "/complete").session(cashier).with(csrf())).andExpect(status().isOk());
		mvc.perform(get("/api/branch/training").session(cashier)).andExpect(jsonPath("$[?(@.id==" + link + ")].mine").value(true));
		mvc.perform(get("/api/admin/training/" + link + "/completions").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		// only HQ publishes
		mvc.perform(post("/api/admin/training").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\",\"kind\":\"link\",\"url\":\"https://a.b\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void hqSettingsHaveDefaultsAreValidatedAndFeedTheReceipt() throws Exception {
		var admin = login("admin");
		var cashier = login("cashier");
		mvc.perform(get("/api/settings/receipt").session(cashier)).andExpect(status().isOk()).andExpect(jsonPath("$.companyName").value("Coffee Postal"));
		mvc.perform(put("/api/admin/settings").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"po_lead_time_hours\":\"abc\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.po_lead_time_hours").exists());
		mvc.perform(put("/api/admin/settings").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"receipt_footer\":\"See you tomorrow!\",\"po_lead_time_hours\":\"24\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.po_lead_time_hours").value("24"));
		mvc.perform(get("/api/settings/receipt").session(cashier)).andExpect(jsonPath("$.footer").value("See you tomorrow!"));
		mvc.perform(get("/api/admin/settings").session(cashier)).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM settings WHERE key = 'receipt_footer'", Integer.class)).isEqualTo(1);
	}

	@Test
	void aCustomerRatesFromTheReceiptQrWithoutSigningIn() throws Exception {
		String token = jdbc.queryForObject("SELECT feedback_token FROM branches WHERE code = 'CEB-01'", String.class);
		mvc.perform(get("/api/public/feedback/" + token)).andExpect(status().isOk()).andExpect(jsonPath("$.branch").exists());
		mvc.perform(get("/api/public/feedback/not-a-real-code")).andExpect(status().isNotFound());
		mvc.perform(post("/api/public/feedback/" + token).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"comment\":\"Great\"}"))
			.andExpect(status().isCreated());
		mvc.perform(post("/api/public/feedback/" + token).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":9}"))
			.andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback WHERE source = 'QR' AND comment = 'Great'", Integer.class)).isGreaterThanOrEqualTo(1);
		// the branch sees it, marked as from a customer
		mvc.perform(get("/api/branch/feedback").session(login("manager"))).andExpect(status().isOk());
		// the same phone cannot send more than three an hour
		for (int i = 0; i < 3; i++) mvc.perform(post("/api/public/feedback/" + token).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4}"));
		mvc.perform(post("/api/public/feedback/" + token).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4}")).andExpect(status().isTooManyRequests());
		mvc.perform(get("/api/branch/feedback/link").session(login("cashier"))).andExpect(jsonPath("$.token").value(token));
	}
}
