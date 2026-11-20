package ph.coffeepostal.pos.shared;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;

/**
 * Reads an uploaded table into rows of text: an Excel file (.xlsx, first sheet) or a CSV file (comma, semicolon or tab, UTF-8).
 * The first row is the header. Blank rows are dropped. Nothing here trusts the file: it is capped in size and rows, and Excel formulas
 * are read as their result, never run.
 */
public final class TableReader {
    public static final int MAX_BYTES = 1_000_000;
    public static final int MAX_ROWS = 2000;

    private TableReader() {}

    public static List<List<String>> read(byte[] data) {
        if (data == null || data.length == 0) throw bad("Choose a file first.");
        if (data.length > MAX_BYTES) throw bad("That file is too big. Keep it under 1 MB.");
        List<List<String>> rows = isZip(data) ? xlsx(data) : csv(new String(data, StandardCharsets.UTF_8));
        rows.removeIf(r -> r.stream().allMatch(String::isBlank));
        if (rows.size() < 2) throw bad("The file needs a header row and at least one row of data.");
        if (rows.size() - 1 > MAX_ROWS) throw bad("Too many rows. Import up to " + MAX_ROWS + " at a time.");
        return rows;
    }

    private static boolean isZip(byte[] d) { return d.length > 4 && d[0] == 'P' && d[1] == 'K'; }

    private static ApiException bad(String m) { return new ApiException(HttpStatus.BAD_REQUEST, m); }

    private static List<List<String>> xlsx(byte[] data) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            List<List<String>> out = new ArrayList<>();
            int width = 0;
            for (Row row : sheet) {
                width = Math.max(width, row.getLastCellNum());
            }
            for (Row row : sheet) {
                if (out.size() > MAX_ROWS + 5) break;
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < width; c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : text(cell, fmt, ev));
                }
                out.add(cells);
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw bad("That Excel file could not be read. Save it as .xlsx or CSV and try again.");
        }
    }

    private static String text(Cell cell, DataFormatter fmt, FormulaEvaluator ev) {
        try {
            if (cell.getCellType() == CellType.FORMULA) return fmt.formatCellValue(cell, ev).trim();
            if (cell.getCellType() == CellType.NUMERIC) {
                double d = cell.getNumericCellValue();
                // 99.0 reads as 99, and a long barcode is not shown as 1.2E+12
                if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
                return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            }
            return fmt.formatCellValue(cell).trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static List<List<String>> csv(String text) {
        if (text.startsWith("﻿")) text = text.substring(1);
        char delim = delimiter(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(ch);
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == delim) {
                row.add(cell.toString().trim()); cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim()); cell.setLength(0);
                rows.add(row); row = new ArrayList<>();
                if (rows.size() > MAX_ROWS + 5) return rows;
            } else cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString().trim()); rows.add(row); }
        return rows;
    }

    /** The separator the first line uses most: comma, semicolon or tab. */
    private static char delimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        int comma = 0, semi = 0, tab = 0;
        for (char c : first.toCharArray()) { if (c == ',') comma++; else if (c == ';') semi++; else if (c == '\t') tab++; }
        return tab > comma && tab > semi ? '\t' : semi > comma ? ';' : ',';
    }

    /** "Cost (PHP)", "CP For Sale Price" -> "costphp", "cpforsaleprice": lets a heading be spelled loosely. */
    public static String key(String header) {
        return header == null ? "" : header.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
