package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

/** Orders that waited offline are uploaded later with the time they were really sold. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OfflineOrderTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession cashier() throws Exception {
		return (MockHttpSession) mvc.perform(formLogin("/api/auth/login").user("cashier").password("coffee123")).andReturn().getRequest().getSession(false);
	}

	private String body(UUID id, Instant soldAt) {
		long product = jdbc.queryForObject("SELECT id FROM products WHERE name = 'Americano'", Long.class);
		return "{\"id\":\"" + id + "\",\"orderType\":\"TAKEOUT\",\"discountType\":\"NONE\",\"paymentMethod\":\"CASH\",\"tendered\":100000,"
			+ "\"lines\":[{\"productId\":" + product + ",\"qty\":1}]" + (soldAt == null ? "" : ",\"soldAt\":\"" + soldAt + "\"") + "}";
	}

	private String send(MockHttpSession s, String json, int expect) throws Exception {
		return mvc.perform(post("/api/pos/orders").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
	}

	@Test
	void anOfflineSaleKeepsItsRealTimeAndItsShiftAndIsSafeToResend() throws Exception {
		var s = cashier();
		long cashierId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'cashier'", Long.class);
		long branch = jdbc.queryForObject("SELECT branch_id FROM user_branches WHERE user_id = ?", Long.class, cashierId);
		Instant open = Instant.now().minus(Duration.ofHours(5)), close = Instant.now().minus(Duration.ofHours(3));
		long shift = jdbc.queryForObject("""
			INSERT INTO shifts(branch_id, cashier_id, opened_at, opening_cash, closed_at, expected_cash, counted_cash) VALUES (?, ?, ?, 0, ?, 0, 0) RETURNING id
			""", Long.class, branch, cashierId, Timestamp.from(open), Timestamp.from(close));

		UUID id = UUID.randomUUID();
		Instant sold = Instant.now().minus(Duration.ofHours(4));
		String first = send(s, body(id, sold), 200);
		var row = jdbc.queryForMap("SELECT created_at, shift_id FROM orders WHERE id = ?", id);
		assertThat(((Timestamp) row.get("created_at")).toInstant()).isBetween(sold.minusSeconds(1), sold.plusSeconds(1));
		assertThat(((Number) row.get("shift_id")).longValue()).isEqualTo(shift);   // the shift open when it was sold, not today's

		// sending it again (the phone was not sure it got through) gives the same order number and no second sale
		String again = send(s, body(id, sold), 200);
		assertThat((Number) JsonPath.read(again, "$.orderNo")).isEqualTo(JsonPath.read(first, "$.orderNo"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Integer.class, id)).isEqualTo(1);
	}

	@Test
	void anOrderWithoutATimeIsSoldNowAndBadTimesAreRefused() throws Exception {
		var s = cashier();
		UUID id = UUID.randomUUID();
		send(s, body(id, null), 200);
		Instant at = jdbc.queryForObject("SELECT created_at FROM orders WHERE id = ?", Timestamp.class, id).toInstant();
		assertThat(at).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(5));

		String tooOld = send(s, body(UUID.randomUUID(), Instant.now().minus(Duration.ofDays(8))), 400);
		assertThat((String) JsonPath.read(tooOld, "$.message")).contains("7 days");
		send(s, body(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(1))), 400);   // the future
	}
}
