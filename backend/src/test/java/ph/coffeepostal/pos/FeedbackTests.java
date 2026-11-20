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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class FeedbackTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	@Test
	void aCashierSavesFeedbackAndTheBranchAndHqReadIt() throws Exception {
		var cashier = login("cashier");
		var admin = login("admin");
		mvc.perform(post("/api/pos/feedback").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\":5,\"comment\":\"Great latte\",\"orderRef\":\"1042\"}")).andExpect(status().isCreated());
		mvc.perform(post("/api/pos/feedback").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\":3}")).andExpect(status().isCreated());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback WHERE comment = 'Great latte' AND order_ref = '1042'", Integer.class)).isEqualTo(1);

		// HQ sees them, with the average and the spread
		mvc.perform(get("/api/admin/feedback").session(admin)).andExpect(status().isOk())
			.andExpect(jsonPath("$.summary.count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
			.andExpect(jsonPath("$.summary.byRating[4]").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
	}

	@Test
	void ratingsOutsideOneToFiveAreRefusedAndACashierCannotReadHqFeedback() throws Exception {
		var cashier = login("cashier");
		mvc.perform(post("/api/pos/feedback").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":0}"))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/pos/feedback").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":6}"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/admin/feedback").session(cashier)).andExpect(status().isForbidden());
	}
}
