package ph.coffeepostal.pos.reporting;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ph.coffeepostal.pos.shared.ApiException;

/**
 * Turns a report's table (header row, then data rows) into an Excel or PDF file. The screen sends the same rows it shows and
 * downloads as CSV, so there is one source of truth per report. Every cell is written as plain text or a number, never a formula,
 * so a value like "=1+1" from the data cannot run in a spreadsheet.
 */
@Service
public class ExportService {
    static final int MAX_ROWS = 20_000, MAX_COLS = 30, MAX_CELL = 500;
    private static final Pattern NUMBER = Pattern.compile("^-?(0|[1-9]\\d{0,14})(\\.\\d{1,6})?$");

    /** Checks size limits and returns the rows with every cell as text, cut to a sane length. */
    static List<List<String>> clean(List<List<Object>> rows) {
        if (rows == null || rows.size() < 1) throw new ApiException(HttpStatus.BAD_REQUEST, "There is nothing to export.");
        if (rows.size() > MAX_ROWS) throw new ApiException(HttpStatus.BAD_REQUEST, "That is too many rows to export (limit " + MAX_ROWS + ").");
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0 || cols > MAX_COLS) throw new ApiException(HttpStatus.BAD_REQUEST, "That report has too many columns to export.");
        return rows.stream().map(r -> r.stream().map(c -> {
            String s = c == null ? "" : String.valueOf(c);
            return s.length() > MAX_CELL ? s.substring(0, MAX_CELL) : s;
        }).toList()).toList();
    }

    static String safeSheetName(String title) {
        String n = title == null ? "Report" : title.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        if (n.isEmpty()) n = "Report";
        return n.length() > 31 ? n.substring(0, 31) : n;
    }

    public byte[] xlsx(String title, String subtitle, List<List<Object>> raw) {
        List<List<String>> rows = clean(raw);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet(safeSheetName(title));
            XSSFFont bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            CellStyle right = wb.createCellStyle();
            right.setAlignment(HorizontalAlignment.RIGHT);
            int r = 0;
            XSSFRow t = sheet.createRow(r++);
            XSSFCell tc = t.createCell(0);
            tc.setCellValue(title == null ? "Report" : title);
            tc.setCellStyle(head);
            if (subtitle != null && !subtitle.isBlank()) sheet.createRow(r++).createCell(0).setCellValue(subtitle);
            r++;
            int headerRow = r;
            int cols = rows.stream().mapToInt(List::size).max().orElse(1);
            int[] width = new int[cols];
            for (int i = 0; i < rows.size(); i++) {
                XSSFRow row = sheet.createRow(r++);
                for (int c = 0; c < rows.get(i).size(); c++) {
                    String v = rows.get(i).get(c);
                    XSSFCell cell = row.createCell(c);
                    if (i > 0 && NUMBER.matcher(v).matches()) {
                        cell.setCellValue(Double.parseDouble(v));
                        cell.setCellStyle(right);
                    } else {
                        cell.setCellValue(v);          // always text: never a formula
                        if (i == 0) cell.setCellStyle(head);
                    }
                    width[c] = Math.max(width[c], Math.min(v.length(), 60));
                }
            }
            for (int c = 0; c < cols; c++) sheet.setColumnWidth(c, Math.max(8, width[c] + 2) * 256);
            sheet.createFreezePane(0, headerRow + 1);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build the Excel file.");
        }
    }

    public byte[] pdf(String title, String subtitle, List<List<Object>> raw) {
        List<List<String>> rows = clean(raw);
        int cols = rows.stream().mapToInt(List::size).max().orElse(1);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(cols > 6 ? PageSize.A4.rotate() : PageSize.A4, 28, 28, 28, 28);
            PdfWriter.getInstance(doc, out);
            doc.open();
            Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.GRAY);
            Font hf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
            Font bf = FontFactory.getFont(FontFactory.HELVETICA, 8);
            doc.add(new Paragraph(title == null ? "Report" : title, h1));
            if (subtitle != null && !subtitle.isBlank()) doc.add(new Paragraph(subtitle, small));
            doc.add(new Paragraph(" "));
            PdfPTable table = new PdfPTable(cols);
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            for (int i = 0; i < rows.size(); i++) {
                for (int c = 0; c < cols; c++) {
                    String v = c < rows.get(i).size() ? rows.get(i).get(c) : "";
                    PdfPCell cell = new PdfPCell(new Phrase(v, i == 0 ? hf : bf));
                    cell.setPadding(3);
                    if (i == 0) cell.setBackgroundColor(new Color(0xF2, 0xE8, 0xDA));
                    if (i > 0 && NUMBER.matcher(v).matches()) cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    table.addCell(cell);
                }
            }
            doc.add(table);
            doc.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build the PDF.");
        }
    }
}
