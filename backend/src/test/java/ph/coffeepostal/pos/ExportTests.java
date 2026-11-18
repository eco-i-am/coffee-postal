package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ExportTests {

	@Autowired MockMvc mvc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private static final String BODY = "{\"title\":\"Sales: Jan/Feb [test]\",\"subtitle\":\"2026-01-01 to 2026-01-31\",\"rows\":["
		+ "[\"Item\",\"Qty\",\"Amount\",\"Code\"],"
		+ "[\"Latte\",12,\"1250.50\",\"007\"],"
		+ "[\"=1+1\",3,\"-20\",\"+cmd\"],"
		+ "[\"Peso ₱ café\",null,\"\",\"x\"]]}";

	@Test
	void excelFileHasNumbersAsNumbersAndNeverRunsAFormula() throws Exception {
		var s = login("manager");
		byte[] bytes = mvc.perform(post("/api/export/xlsx").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")))
			.andReturn().getResponse().getContentAsByteArray();
		try (var wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			var sheet = wb.getSheetAt(0);
			assertThat(sheet.getSheetName()).isEqualTo("Sales  Jan Feb  test");   // characters Excel forbids in a tab name are replaced
			assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Sales: Jan/Feb [test]");
			// rows: title, subtitle, blank, header, data...
			var head = sheet.getRow(3);
			assertThat(head.getCell(0).getStringCellValue()).isEqualTo("Item");
			var latte = sheet.getRow(4);
			assertThat(latte.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
			assertThat(latte.getCell(2).getNumericCellValue()).isEqualTo(1250.50);
			assertThat(latte.getCell(3).getCellType()).isEqualTo(CellType.STRING);       // "007" keeps its leading zeros
			assertThat(latte.getCell(3).getStringCellValue()).isEqualTo("007");
			var evil = sheet.getRow(5);
			assertThat(evil.getCell(0).getCellType()).isEqualTo(CellType.STRING);        // "=1+1" is text, not a formula
			assertThat(evil.getCell(0).getStringCellValue()).isEqualTo("=1+1");
			assertThat(evil.getCell(2).getNumericCellValue()).isEqualTo(-20.0);
			assertThat(evil.getCell(3).getStringCellValue()).isEqualTo("+cmd");
			assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Peso ₱ café");
		}
	}

	@Test
	void pdfIsARealPdfAndBadRequestsAreRefused() throws Exception {
		var s = login("manager");
		byte[] pdf = mvc.perform(post("/api/export/pdf").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
		assertThat(pdf.length).isGreaterThan(500);

		mvc.perform(post("/api/export/docx").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isNotFound());
		mvc.perform(post("/api/export/xlsx").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}")).andExpect(status().isBadRequest());
		// too many columns
		String wide = "[" + "\"c\",".repeat(30) + "\"c\"]";
		mvc.perform(post("/api/export/pdf").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[" + wide + "]}")).andExpect(status().isBadRequest());
		// not signed in
		mvc.perform(post("/api/export/xlsx").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
		// no CSRF token
		mvc.perform(post("/api/export/xlsx").session(s).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
	}
}
