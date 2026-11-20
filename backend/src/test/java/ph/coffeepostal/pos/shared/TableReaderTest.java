package ph.coffeepostal.pos.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class TableReaderTest {
    private static List<List<String>> csv(String s) { return TableReader.read(s.getBytes(StandardCharsets.UTF_8)); }

    @Test
    void readsCommaSeparatedWithQuotesAndBlankLines() {
        var rows = csv("Name,Category,Price\n\"Latte, Iced\",Coffee,99.50\n\n\"Say \"\"hi\"\"\",Coffee,80\n");
        assertEquals(List.of("Name", "Category", "Price"), rows.get(0));
        assertEquals(List.of("Latte, Iced", "Coffee", "99.50"), rows.get(1));
        assertEquals("Say \"hi\"", rows.get(2).get(0));
        assertEquals(3, rows.size());
    }

    @Test
    void detectsSemicolonsAndTabsAndStripsTheByteOrderMark() {
        assertEquals(List.of("Mocha", "120"), csv("﻿Name;Price\nMocha;120\r\n").get(1));
        assertEquals(List.of("Mocha", "120"), csv("Name\tPrice\nMocha\t120\n").get(1));
    }

    @Test
    void readsAnExcelFileAndShowsWholeNumbersWithoutDecimals() throws Exception {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sh = wb.createSheet();
            var h = sh.createRow(0);
            h.createCell(0).setCellValue("Name"); h.createCell(1).setCellValue("Price"); h.createCell(2).setCellValue("Barcode");
            var r = sh.createRow(1);
            r.createCell(0).setCellValue("Black Coffee"); r.createCell(1).setCellValue(99.0); r.createCell(2).setCellValue(4801234567890.0);
            var r2 = sh.createRow(2);
            r2.createCell(0).setCellValue("Mocha"); r2.createCell(1).setCellValue(120.5);
            wb.write(out);
            var rows = TableReader.read(out.toByteArray());
            assertEquals(List.of("Black Coffee", "99", "4801234567890"), rows.get(1));
            assertEquals(List.of("Mocha", "120.5", ""), rows.get(2));
        }
    }

    @Test
    void refusesEmptyTinyAndHugeFiles() {
        assertThrows(ApiException.class, () -> TableReader.read(new byte[0]));
        assertThrows(ApiException.class, () -> csv("Name,Price\n"));
        assertThrows(ApiException.class, () -> TableReader.read(new byte[TableReader.MAX_BYTES + 1]));
    }

    @Test
    void refusesAFileThatIsNotReallyExcel() {
        assertThrows(ApiException.class, () -> TableReader.read("PK not really a zip".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void looseHeadingsMatch() {
        assertEquals("cpforsaleprice", TableReader.key("CP For Sale Price"));
        assertEquals("costphp", TableReader.key(" Cost (PHP) "));
    }
}
