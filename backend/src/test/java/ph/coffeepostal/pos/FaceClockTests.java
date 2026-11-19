package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Random;
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

/** Face check on the time clock: enrolling needs consent, a punch is only ever flagged, and leaving clears the face. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class FaceClockTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	private static final Random RANDOM = new Random();

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private String pin() {
		while (true) {
			String p = String.format("%06d", 100000 + RANDOM.nextInt(899999));
			if (p.chars().distinct().count() > 2 && !"0123456789".contains(p) && !"9876543210".contains(p)) return p;
		}
	}

	private long addEmployee(MockHttpSession manager, String name, String pin) throws Exception {
		String body = mvc.perform(post("/api/branch/employees").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"fullName\":\"" + name + "\",\"position\":\"Barista\",\"pin\":\"" + pin + "\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	/** 128 numbers; the seed makes a different "face". */
	private static String vec(double seed, double nudge) {
		StringBuilder b = new StringBuilder("[");
		for (int i = 0; i < 128; i++) {
			if (i > 0) b.append(',');
			b.append(Math.sin(seed + i) * 0.1 + (i == 0 ? nudge : 0));
		}
		return b.append(']').toString();
	}

	private String punch(MockHttpSession tablet, String pin, String faceJson) throws Exception {
		String body = "{\"pin\":\"" + pin + "\"" + (faceJson == null ? "" : ",\"face\":" + faceJson) + "}";
		return mvc.perform(post("/api/clock/punch").session(tablet).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private void older(long employeeId) {
		jdbc.update("UPDATE attendance_logs SET punched_at = punched_at - interval '8 hours' WHERE employee_id = ?", employeeId);
	}

	@Test
	void enrollingNeedsConsentAndAPunchIsOnlyEverFlagged() throws Exception {
		var manager = login("manager");
		var tablet = login("cashier");
		String pin = pin();
		long id = addEmployee(manager, "Face Fiona", pin);

		// nothing on file yet
		assertThat((String) JsonPath.read(punch(tablet, pin, "{\"state\":\"ok\",\"descriptor\":" + vec(1, 0) + "}"), "$.faceStatus")).isEqualTo("NOT_ENROLLED");
		older(id);

		// consent is required, and the reading must be a usable one
		mvc.perform(post("/api/branch/employees/" + id + "/face").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"descriptor\":" + vec(1, 0) + ",\"consent\":false}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/branch/employees/" + id + "/face").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"descriptor\":[0.1,0.2],\"consent\":true}")).andExpect(status().isBadRequest());
		// the tablet (cashier) account cannot enroll anyone
		mvc.perform(post("/api/branch/employees/" + id + "/face").session(tablet).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"descriptor\":" + vec(1, 0) + ",\"consent\":true}")).andExpect(status().isForbidden());
		mvc.perform(post("/api/branch/employees/" + id + "/face").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"descriptor\":" + vec(1, 0) + ",\"consent\":true}")).andExpect(status().isOk());
		String list = mvc.perform(get("/api/branch/employees").session(manager)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat((Boolean) ((java.util.List<?>) JsonPath.read(list, "$[?(@.id==" + id + ")].faceEnrolled")).get(0)).isTrue();
		assertThat(jdbc.queryForObject("SELECT face_consent_by IS NOT NULL FROM employees WHERE id = ?", Boolean.class, id)).isTrue();

		// every outcome is a 200: a punch is never refused over a face
		assertThat((String) JsonPath.read(punch(tablet, pin, "{\"state\":\"ok\",\"descriptor\":" + vec(1, 0.05) + "}"), "$.faceStatus")).isEqualTo("MATCH");
		older(id);
		assertThat((String) JsonPath.read(punch(tablet, pin, "{\"state\":\"ok\",\"descriptor\":" + vec(40, 0) + "}"), "$.faceStatus")).isEqualTo("REVIEW");
		older(id);
		assertThat((String) JsonPath.read(punch(tablet, pin, "{\"state\":\"none\"}"), "$.faceStatus")).isEqualTo("NO_FACE");
		older(id);
		assertThat((String) JsonPath.read(punch(tablet, pin, null), "$.faceStatus")).isEqualTo("NOT_CHECKED");
		older(id);

		// the manager sees it on the attendance report
		String report = mvc.perform(get("/api/branch/attendance").session(manager)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(report).contains("\"REVIEW\"");

		// removing the face, or the employee leaving, clears it
		mvc.perform(delete("/api/branch/employees/" + id + "/face").session(manager).with(csrf())).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT face_template IS NULL FROM employees WHERE id = ?", Boolean.class, id)).isTrue();
		mvc.perform(post("/api/branch/employees/" + id + "/face").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"descriptor\":" + vec(1, 0) + ",\"consent\":true}")).andExpect(status().isOk());
		mvc.perform(put("/api/branch/employees/" + id).session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"fullName\":\"Face Fiona\",\"position\":\"Barista\",\"status\":\"INACTIVE\"}")).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT face_template IS NULL FROM employees WHERE id = ?", Boolean.class, id)).isTrue();
		jdbc.update("DELETE FROM clock_attempts");
	}

	@Test
	void leavingClockOnlyModeNeedsAManagerPin() throws Exception {
		var tablet = login("cashier");
		mvc.perform(post("/api/clock/exit").session(tablet).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"managerPin\":\"000111\"}")).andExpect(status().isForbidden());
		mvc.perform(post("/api/clock/exit").session(tablet).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"managerPin\":\"9999\"}")).andExpect(status().isOk());
		jdbc.update("DELETE FROM pin_attempts");
	}
}
