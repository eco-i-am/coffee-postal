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
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
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
class TimeClockTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ph.coffeepostal.pos.hr.PhotoService photoService;
	private static final Random RANDOM = new Random();

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	/** a valid 6-digit PIN nobody else in the shared test database is likely to hold */
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

	private String punch(MockHttpSession tablet, String json, int expected) throws Exception {
		return mvc.perform(post("/api/clock/punch").session(tablet).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
	}

	private static String check(String salt, String pin) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).getBytes()));
	}

	@Test
	void pinsAreSixDigitsUniquePerBranchAndOnlyManagersManageStaff() throws Exception {
		var manager = login("manager");
		var tablet = login("cashier");
		String pin = pin();
		long id = addEmployee(manager, "Ana Pin", pin);

		mvc.perform(post("/api/branch/employees").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"fullName\":\"Dup\",\"pin\":\"" + pin + "\"}")).andExpect(status().isConflict());
		mvc.perform(post("/api/branch/employees").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"fullName\":\"Easy\",\"pin\":\"123456\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/branch/employees").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"fullName\":\"Short\",\"pin\":\"4829\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/branch/employees").session(tablet).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"fullName\":\"Nope\",\"pin\":\"" + pin() + "\"}")).andExpect(status().isForbidden());

		// the PIN is not stored, and the roster never carries it
		assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE pin_lookup LIKE '%' || ? || '%' OR pin_check LIKE '%' || ? || '%'", Integer.class, pin, pin)).isZero();
		String roster = mvc.perform(get("/api/clock/roster").session(tablet)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(roster).doesNotContain(pin);
		assertThat((List<?>) JsonPath.read(roster, "$.employees[?(@.id==" + id + ")]")).hasSize(1);

		// a PIN freed by deactivating can be reused; the old holder cannot punch
		mvc.perform(put("/api/branch/employees/" + id).session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"fullName\":\"Ana Pin\",\"position\":\"Barista\",\"status\":\"INACTIVE\"}")).andExpect(status().isOk());
		punch(tablet, "{\"pin\":\"" + pin + "\"}", 403);
		addEmployee(manager, "New Holder", pin);
		jdbc.update("DELETE FROM clock_attempts");
	}

	@Test
	void punchesAlternateInAndOutAreIdempotentAndLockOutGuessing() throws Exception {
		var manager = login("manager");
		var tablet = login("cashier");
		String pin = pin();
		long id = addEmployee(manager, "Ben Punch", pin);

		String clientId = UUID.randomUUID().toString();
		String first = punch(tablet, "{\"pin\":\"" + pin + "\",\"clientId\":\"" + clientId + "\",\"deviceTime\":\"" + Instant.now() + "\"}", 200);
		assertThat((String) JsonPath.read(first, "$.kind")).isEqualTo("IN");
		assertThat((String) JsonPath.read(first, "$.name")).isEqualTo("Ben Punch");
		// the same punch sent again (a retry) answers the same and records nothing new
		String again = punch(tablet, "{\"pin\":\"" + pin + "\",\"clientId\":\"" + clientId + "\"}", 200);
		assertThat((String) JsonPath.read(again, "$.kind")).isEqualTo("IN");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_logs WHERE employee_id = ?", Integer.class, id)).isEqualTo(1);

		punch(tablet, "{\"pin\":\"" + pin + "\"}", 409);   // a double tap a second later is refused

		jdbc.update("UPDATE attendance_logs SET punched_at = punched_at - interval '8 hours' WHERE employee_id = ?", id);
		String out = punch(tablet, "{\"pin\":\"" + pin + "\"}", 200);
		assertThat((String) JsonPath.read(out, "$.kind")).isEqualTo("OUT");
		jdbc.update("UPDATE attendance_logs SET punched_at = punched_at - interval '1 hour' WHERE employee_id = ? AND kind = 'OUT'", id);
		assertThat((String) JsonPath.read(punch(tablet, "{\"pin\":\"" + pin + "\"}", 200), "$.kind")).isEqualTo("IN");

		// a tablet clock that is far off is flagged, and the server's time is used
		jdbc.update("DELETE FROM attendance_logs WHERE employee_id = ?", id);
		String skew = punch(tablet, "{\"pin\":\"" + pin + "\",\"deviceTime\":\"" + Instant.now().minus(30, ChronoUnit.MINUTES).plusSeconds(60) + "\"}", 200);
		assertThat((Boolean) JsonPath.read(skew, "$.offline")).isTrue();   // that old counts as a queued punch ...
		jdbc.update("DELETE FROM attendance_logs WHERE employee_id = ?", id);
		String fresh = punch(tablet, "{\"pin\":\"" + pin + "\",\"deviceTime\":\"" + Instant.now().plus(10, ChronoUnit.MINUTES) + "\"}", 200);
		assertThat((String) JsonPath.read(fresh, "$.flag")).isEqualTo("CLOCK_SKEW");   // ... a clock running ahead is flagged

		// ten wrong PINs lock the clock for the branch
		jdbc.update("DELETE FROM clock_attempts");
		for (int i = 0; i < 10; i++) punch(tablet, "{\"pin\":\"000001\"}", 403);
		punch(tablet, "{\"pin\":\"" + pin + "\"}", 429);
		jdbc.update("DELETE FROM clock_attempts");
	}

	@Test
	void aPunchKeptOnTheTabletOfflineCountsAtTheTimeItHappened() throws Exception {
		var manager = login("manager");
		var tablet = login("cashier");
		String pin = pin();
		long id = addEmployee(manager, "Cara Offline", pin);
		String roster = mvc.perform(get("/api/clock/roster").session(tablet)).andReturn().getResponse().getContentAsString();
		List<String> salts = JsonPath.read(roster, "$.employees[?(@.id==" + id + ")].salt");
		String salt = salts.get(0);
		String check = check(salt, pin);

		Instant in = Instant.now().minus(3, ChronoUnit.HOURS), out = Instant.now().minus(1, ChronoUnit.HOURS);
		String r1 = punch(tablet, "{\"employeeId\":" + id + ",\"check\":\"" + check + "\",\"clientId\":\"" + UUID.randomUUID() + "\",\"deviceTime\":\"" + in + "\"}", 200);
		assertThat((String) JsonPath.read(r1, "$.kind")).isEqualTo("IN");
		assertThat((Boolean) JsonPath.read(r1, "$.offline")).isTrue();
		assertThat((String) JsonPath.read(punch(tablet, "{\"employeeId\":" + id + ",\"check\":\"" + check + "\",\"clientId\":\"" + UUID.randomUUID() + "\",\"deviceTime\":\"" + out + "\"}", 200), "$.kind")).isEqualTo("OUT");

		punch(tablet, "{\"employeeId\":" + id + ",\"check\":\"" + "0".repeat(64) + "\",\"deviceTime\":\"" + in + "\"}", 403);                 // wrong check
		punch(tablet, "{\"employeeId\":" + id + ",\"check\":\"" + check + "\",\"deviceTime\":\"" + Instant.now().minus(9, ChronoUnit.DAYS) + "\"}", 400);   // too old

		// the manager's report shows two hours
		String rep = mvc.perform(get("/api/branch/attendance?employeeId=" + id).session(manager)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(((Number) JsonPath.read(rep, "$.sessions[0].minutes")).longValue()).isBetween(119L, 121L);
		assertThat((String) JsonPath.read(rep, "$.sessions[0].status")).isEqualTo("OK");

		// a PIN changed since the roster was saved: the old check no longer works
		String newPin = pin();
		mvc.perform(post("/api/branch/employees/" + id + "/pin").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + newPin + "\"}")).andExpect(status().isOk());
		punch(tablet, "{\"employeeId\":" + id + ",\"check\":\"" + check + "\",\"deviceTime\":\"" + Instant.now().minus(30, ChronoUnit.MINUTES) + "\"}", 403);
	}

	@Test
	void forgottenTimeOutsAreFlaggedAndManagersCorrectWithPinAndReason() throws Exception {
		var manager = login("manager");
		var tablet = login("cashier");
		String pin = pin();
		long id = addEmployee(manager, "Dan Forgot", pin);
		long branch = jdbc.queryForObject("SELECT branch_id FROM employees WHERE id = ?", Long.class, id);
		long logIn = jdbc.queryForObject("""
			INSERT INTO attendance_logs(employee_id, branch_id, kind, punched_at, device_time, source)
			VALUES (?, ?, 'IN', now() - interval '20 hours', now() - interval '20 hours', 'ONLINE') RETURNING id
			""", Long.class, id, branch);

		// 20 hours later the earlier time in is treated as a forgotten time out, and the new punch is a time in
		assertThat((String) JsonPath.read(punch(tablet, "{\"pin\":\"" + pin + "\"}", 200), "$.kind")).isEqualTo("IN");
		String rep = mvc.perform(get("/api/branch/attendance?employeeId=" + id).session(manager)).andReturn().getResponse().getContentAsString();
		assertThat((List<?>) JsonPath.read(rep, "$.sessions[?(@.status=='FORGOT_OUT')]")).hasSize(1);

		// correct the missing time out: wrong PIN, no reason, and a time in the future are all refused
		String when = Instant.now().minus(12, ChronoUnit.HOURS).toString();
		String body = "{\"employeeId\":" + id + ",\"kind\":\"OUT\",\"punchedAt\":\"" + when + "\",\"reason\":\"Forgot to time out\",\"managerPin\":\"";
		mvc.perform(post("/api/branch/attendance").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body + "0000\"}")).andExpect(status().isForbidden());
		mvc.perform(post("/api/branch/attendance").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"employeeId\":" + id + ",\"kind\":\"OUT\",\"punchedAt\":\"" + when + "\",\"reason\":\"\",\"managerPin\":\"9999\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/branch/attendance").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"employeeId\":" + id + ",\"kind\":\"OUT\",\"punchedAt\":\"" + Instant.now().plus(2, ChronoUnit.HOURS) + "\",\"reason\":\"later\",\"managerPin\":\"9999\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/branch/attendance").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body + "9999\"}")).andExpect(status().isCreated());

		// change the first time in: the original time is kept in the audit log
		Instant original = jdbc.queryForObject("SELECT punched_at FROM attendance_logs WHERE id = ?", java.sql.Timestamp.class, logIn).toInstant();
		String fixed = Instant.now().minus(19, ChronoUnit.HOURS).toString();
		mvc.perform(put("/api/branch/attendance/" + logIn).session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"punchedAt\":\"" + fixed + "\",\"reason\":\"Tablet was down\",\"managerPin\":\"9999\"}")).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT correction_reason FROM attendance_logs WHERE id = ?", String.class, logIn)).isEqualTo("Tablet was down");
		String before = jdbc.queryForObject("SELECT before_json::text FROM audit_logs WHERE action = 'ATTENDANCE_CORRECT' AND entity_id = ?", String.class, String.valueOf(logIn));
		assertThat(before).contains(original.toString().substring(0, 16));   // the original time is still on record

		// cashiers cannot see attendance or correct it
		mvc.perform(get("/api/branch/attendance").session(tablet)).andExpect(status().isForbidden());
		mvc.perform(put("/api/branch/attendance/" + logIn).session(tablet).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"punchedAt\":\"" + fixed + "\",\"reason\":\"x y z\",\"managerPin\":\"9999\"}")).andExpect(status().isForbidden());

		// HQ sees every branch's attendance
		var admin = login("admin");
		mvc.perform(get("/api/admin/attendance?employeeId=" + id).session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.sessions").isArray());
		mvc.perform(get("/api/admin/employees").session(admin)).andExpect(status().isOk());
		jdbc.update("DELETE FROM pin_attempts");
		jdbc.update("DELETE FROM clock_attempts");
	}

	/** the smallest thing that starts like a JPEG (FF D8 FF E0 ...) */
	private static final String JPEG = java.util.Base64.getEncoder().encodeToString(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0 });

	@Test
	void photoSavedWithEveryPunchIsSeenOnlyByManagersAndDeletedAfterTheRetentionPeriod() throws Exception {
		var manager = login("manager");
		var tablet = login("cashier");
		String pin = pin();
		long id = addEmployee(manager, "Eli Photo", pin);

		// time in with a photo
		String in = punch(tablet, "{\"pin\":\"" + pin + "\",\"photo\":\"" + JPEG + "\"}", 200);
		assertThat((Boolean) JsonPath.read(in, "$.photo")).isTrue();
		// time out with a photo
		jdbc.update("UPDATE attendance_logs SET punched_at = punched_at - interval '8 hours' WHERE employee_id = ?", id);
		String out = punch(tablet, "{\"pin\":\"" + pin + "\",\"photo\":\"data:image/jpeg;base64," + JPEG + "\"}", 200);
		assertThat((String) JsonPath.read(out, "$.kind")).isEqualTo("OUT");
		assertThat((Boolean) JsonPath.read(out, "$.photo")).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_photos p JOIN attendance_logs l ON l.id = p.log_id WHERE l.employee_id = ?", Integer.class, id)).isEqualTo(2);

		// the report says which punches have a photo; managers can open them, cashiers and other branches cannot
		String rep = mvc.perform(get("/api/branch/attendance?employeeId=" + id).session(manager)).andReturn().getResponse().getContentAsString();
		assertThat((Boolean) JsonPath.read(rep, "$.sessions[0].inPhoto")).isTrue();
		assertThat((Boolean) JsonPath.read(rep, "$.sessions[0].outPhoto")).isTrue();
		long inLog = ((Number) JsonPath.read(rep, "$.sessions[0].inLogId")).longValue();
		mvc.perform(get("/api/branch/attendance/photos/" + inLog).session(manager)).andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.IMAGE_JPEG));
		mvc.perform(get("/api/branch/attendance/photos/" + inLog).session(tablet)).andExpect(status().isForbidden());
		mvc.perform(get("/api/branch/attendance/photos/999999").session(manager)).andExpect(status().isNotFound());
		mvc.perform(get("/api/admin/attendance/photos/" + inLog).session(login("admin"))).andExpect(status().isOk());

		// a photo that is not a JPEG is dropped, but the punch still counts
		String pin2 = pin();
		long id2 = addEmployee(manager, "Fay Nophoto", pin2);
		String bad = punch(tablet, "{\"pin\":\"" + pin2 + "\",\"photo\":\"bm90IGEgcGljdHVyZQ==\"}", 200);
		assertThat((Boolean) JsonPath.read(bad, "$.photo")).isFalse();
		String none = punch(tablet, "{\"pin\":\"" + pin() + "\"}", 403);   // unknown PIN: no punch, no photo
		assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_photos p JOIN attendance_logs l ON l.id = p.log_id WHERE l.employee_id = ?", Integer.class, id2)).isZero();

		// the nightly clean-up removes photos older than the retention period and keeps newer ones
		jdbc.update("UPDATE attendance_photos SET taken_at = now() - interval '200 days' WHERE log_id = ?", inLog);
		assertThat(photoService.purge()).isGreaterThanOrEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_photos WHERE log_id = ?", Integer.class, inLog)).isZero();
		mvc.perform(get("/api/branch/attendance/photos/" + inLog).session(manager)).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_logs WHERE id = ?", Integer.class, inLog)).isEqualTo(1);   // the punch stays
		jdbc.update("DELETE FROM clock_attempts");
	}
}
