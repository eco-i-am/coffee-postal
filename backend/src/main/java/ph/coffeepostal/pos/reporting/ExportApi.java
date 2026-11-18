package ph.coffeepostal.pos.reporting;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.shared.ApiException;

/**
 * Download a report as Excel or PDF. Any signed-in user may use it: it only formats the rows the person's own screen already
 * showed them, so it reveals nothing the report endpoints would not.
 */
@RestController
@RequestMapping("/api/export")
class ExportApi {
    record ExportReq(@Size(max = 100) String title, @Size(max = 200) String subtitle, @NotNull List<List<Object>> rows) {}

    private final ExportService exports;

    ExportApi(ExportService exports) { this.exports = exports; }

    @PostMapping("/{format}")
    ResponseEntity<byte[]> export(@PathVariable String format, @RequestBody ExportReq r) {
        boolean xlsx = format.equals("xlsx");
        if (!xlsx && !format.equals("pdf")) throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "Unknown format.");
        byte[] body = xlsx ? exports.xlsx(r.title(), r.subtitle(), r.rows()) : exports.pdf(r.title(), r.subtitle(), r.rows());
        String name = (r.title() == null || r.title().isBlank() ? "report" : r.title()).replaceAll("[^A-Za-z0-9._-]+", "_") + (xlsx ? ".xlsx" : ".pdf");
        return ResponseEntity.ok()
            .contentType(xlsx ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") : MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(body);
    }
}
