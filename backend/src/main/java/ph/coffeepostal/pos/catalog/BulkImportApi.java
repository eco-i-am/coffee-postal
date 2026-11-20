package ph.coffeepostal.pos.catalog;

import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.shared.AuditService;

/**
 * HQ: add many products or items from an Excel (.xlsx) or CSV file sent as the request body.
 * dryRun=true (the default) only checks the file and says what would happen.
 */
@RestController
@RequestMapping("/api/admin")
class BulkImportApi {
    private final BulkImportService imports;
    private final AuditService audit;

    BulkImportApi(BulkImportService imports, AuditService audit) { this.imports = imports; this.audit = audit; }

    @PostMapping(value = "/products/import", consumes = "*/*")
    @PreAuthorize("hasAuthority('PERM_products_add')")
    BulkImportService.Result products(@RequestBody byte[] file, @RequestParam(defaultValue = "true") boolean dryRun, Principal who) {
        BulkImportService.Result r = imports.importProducts(file, dryRun);
        if (!dryRun) audit.record(who.getName(), null, "PRODUCT_IMPORT", "product", null, null, "{\"added\":" + r.added() + ",\"skipped\":" + r.skipped() + "}", null);
        return r;
    }

    @PostMapping(value = "/items/import", consumes = "*/*")
    @PreAuthorize("hasAuthority('PERM_items_master_add')")
    BulkImportService.Result items(@RequestBody byte[] file, @RequestParam(defaultValue = "true") boolean dryRun, Principal who) {
        BulkImportService.Result r = imports.importItems(file, dryRun);
        if (!dryRun) audit.record(who.getName(), null, "ITEM_IMPORT", "item", null, null, "{\"added\":" + r.added() + ",\"skipped\":" + r.skipped() + "}", null);
        return r;
    }
}
