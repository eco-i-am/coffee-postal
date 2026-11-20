package ph.coffeepostal.pos.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.inventory.InventoryService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.PesoInput;
import ph.coffeepostal.pos.shared.TableReader;

/**
 * Adds menu products or inventory items from an Excel or CSV file. Every row is checked first; rows with a problem are skipped and
 * reported, the rest are added. With dryRun nothing is saved: it only says what would happen. Existing products and items are never
 * changed by an import (a row whose name already exists is skipped).
 */
@Service
public class BulkImportService {
    public record RowResult(int row, String name, String status, String message) {}
    public record Result(boolean dryRun, int total, int added, int skipped, List<RowResult> rows) {}

    private final JdbcTemplate jdbc;
    private final CatalogService catalog;
    private final InventoryService inventory;

    public BulkImportService(JdbcTemplate jdbc, CatalogService catalog, InventoryService inventory) {
        this.jdbc = jdbc; this.catalog = catalog; this.inventory = inventory;
    }

    /** Headings that mean the same thing. The first row of the file picks the columns, in any order. */
    private static final Map<String, String> PRODUCT_COLS = Map.ofEntries(
        Map.entry("name", "name"), Map.entry("product", "name"), Map.entry("productname", "name"),
        Map.entry("category", "category"), Map.entry("categoryname", "category"),
        Map.entry("price", "price"), Map.entry("sellingprice", "price"), Map.entry("retailprice", "price"), Map.entry("srp", "price"),
        Map.entry("cost", "cost"), Map.entry("costprice", "cost"), Map.entry("cpforsaleprice", "cost"),
        Map.entry("sku", "sku"), Map.entry("barcode", "barcode"),
        Map.entry("description", "description"), Map.entry("desc", "description"));
    private static final Map<String, String> ITEM_COLS = Map.ofEntries(
        Map.entry("name", "name"), Map.entry("item", "name"), Map.entry("itemname", "name"),
        Map.entry("unit", "unit"), Map.entry("uom", "unit"), Map.entry("unitofmeasure", "unit"),
        Map.entry("cost", "cost"), Map.entry("costprice", "cost"), Map.entry("unitcost", "cost"),
        Map.entry("category", "category"), Map.entry("categoryname", "category"),
        Map.entry("lowstock", "low"), Map.entry("lowstockalert", "low"), Map.entry("reorderlevel", "low"), Map.entry("reorderpoint", "low"),
        Map.entry("sku", "sku"), Map.entry("barcode", "barcode"),
        Map.entry("description", "description"), Map.entry("desc", "description"));

    private static Map<String, Integer> columns(List<String> header, Map<String, String> known, List<String> required) {
        Map<String, Integer> at = new java.util.HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String field = known.get(TableReader.key(header.get(i)));
            if (field != null) at.putIfAbsent(field, i);
        }
        for (String r : required) {
            if (!at.containsKey(r)) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "The first row needs a \"" + r + "\" column.");
        }
        return at;
    }

    private static String cell(List<String> row, Map<String, Integer> at, String field) {
        Integer i = at.get(field);
        return i == null || i >= row.size() ? "" : row.get(i).trim();
    }

    @Transactional
    public Result importProducts(byte[] file, boolean dryRun) {
        List<List<String>> rows = TableReader.read(file);
        Map<String, Integer> at = columns(rows.get(0), PRODUCT_COLS, List.of("name", "category", "price"));
        Set<String> names = new HashSet<>(), skus = new HashSet<>(), codes = new HashSet<>();
        List<RowResult> out = new ArrayList<>();
        int added = 0;
        for (int n = 1; n < rows.size(); n++) {
            List<String> r = rows.get(n);
            int line = n + 1;   // the row number as the person sees it in their spreadsheet
            String name = cell(r, at, "name"), category = cell(r, at, "category"), sku = cell(r, at, "sku"), barcode = cell(r, at, "barcode");
            String err = null;
            long price = 0, cost = 0;
            Long categoryId = null;
            try {
                if (name.isEmpty()) err = "Name is empty.";
                else if (name.length() > 120) err = "Name is longer than 120 characters.";
                else if (!names.add(name.toLowerCase())) err = "Appears twice in this file.";
                else if (catalog.productNameExists(name)) err = "A product with this name already exists.";
                if (err == null) {
                    List<Long> cat = jdbc.query("SELECT id FROM product_categories WHERE lower(name) = lower(?) AND active", (rs, i) -> rs.getLong(1), category);
                    if (cat.isEmpty()) err = "Category \"" + category + "\" does not exist. Add it under Menu setup first.";
                    else categoryId = cat.get(0);
                }
                if (err == null) price = PesoInput.parse(cell(r, at, "price"), true, "price", "Price must be a number like 99 or 99.50.");
                if (err == null && !cell(r, at, "cost").isEmpty()) cost = PesoInput.parse(cell(r, at, "cost"), true, "cost", "Cost must be a number like 40 or 40.50.");
                if (err == null && !sku.isEmpty() && (sku.length() > 40 || !skus.add(sku.toLowerCase()) || catalog.productSkuTaken(sku, -1))) err = "SKU is too long or already used.";
                if (err == null && !barcode.isEmpty() && (barcode.length() > 40 || !codes.add(barcode.toLowerCase()) || catalog.productBarcodeTaken(barcode, -1))) err = "Barcode is too long or already used.";
                if (err == null && cell(r, at, "description").length() > 500) err = "Description is longer than 500 characters.";
            } catch (ApiException e) {
                err = e.getMessage();
            }
            if (err != null) { out.add(new RowResult(line, name, "ERROR", err)); continue; }
            if (!dryRun) {
                long id = catalog.createProduct(categoryId, name, price, cost, false, "hot", "#FFA076");
                catalog.setProductExtras(id, sku, barcode, cell(r, at, "description"));
            }
            added++;
            out.add(new RowResult(line, name, "OK", dryRun ? "Will be added." : "Added."));
        }
        return new Result(dryRun, rows.size() - 1, added, rows.size() - 1 - added, out);
    }

    @Transactional
    public Result importItems(byte[] file, boolean dryRun) {
        List<List<String>> rows = TableReader.read(file);
        Map<String, Integer> at = columns(rows.get(0), ITEM_COLS, List.of("name", "unit", "cost"));
        Set<String> names = new HashSet<>(), skus = new HashSet<>(), codes = new HashSet<>();
        List<RowResult> out = new ArrayList<>();
        int added = 0;
        for (int n = 1; n < rows.size(); n++) {
            List<String> r = rows.get(n);
            int line = n + 1;
            String name = cell(r, at, "name"), unit = cell(r, at, "unit"), category = cell(r, at, "category"), sku = cell(r, at, "sku"), barcode = cell(r, at, "barcode");
            String err = null;
            long cost = 0, low = 0;
            Long categoryId = null;
            try {
                if (name.isEmpty()) err = "Name is empty.";
                else if (name.length() > 120) err = "Name is longer than 120 characters.";
                else if (!names.add(name.toLowerCase())) err = "Appears twice in this file.";
                else if (inventory.itemNameExists(name, null)) err = "An item with this name already exists.";
                else if (unit.isEmpty() || unit.length() > 20) err = "Unit is empty or longer than 20 characters.";
                if (err == null) cost = PesoInput.parse(cell(r, at, "cost"), true, "cost", "Cost must be a number like 850 or 850.50.");
                if (err == null && !cell(r, at, "low").isEmpty()) {
                    try { low = Long.parseLong(cell(r, at, "low")); } catch (NumberFormatException e) { err = "Low stock must be a whole number."; }
                    if (err == null && (low < 0 || low > 1_000_000)) err = "Low stock must be between 0 and 1,000,000.";
                }
                if (err == null && !category.isEmpty()) {
                    List<Long> cat = jdbc.query("SELECT id FROM item_categories WHERE lower(name) = lower(?) AND active", (rs, i) -> rs.getLong(1), category);
                    if (cat.isEmpty()) err = "Category \"" + category + "\" does not exist. Add it under Items > Categories first.";
                    else categoryId = cat.get(0);
                }
                if (err == null && !sku.isEmpty() && (sku.length() > 40 || !skus.add(sku.toLowerCase()) || inventory.itemSkuTaken(sku, -1))) err = "SKU is too long or already used.";
                if (err == null && !barcode.isEmpty() && (barcode.length() > 40 || !codes.add(barcode.toLowerCase()) || inventory.itemBarcodeTaken(barcode, -1))) err = "Barcode is too long or already used.";
                if (err == null && cell(r, at, "description").length() > 500) err = "Description is longer than 500 characters.";
            } catch (ApiException e) {
                err = e.getMessage();
            }
            if (err != null) { out.add(new RowResult(line, name, "ERROR", err)); continue; }
            if (!dryRun) {
                long id = inventory.createItem(name, unit, cost, low, categoryId);
                inventory.setItemExtras(id, sku, barcode, cell(r, at, "description"));
            }
            added++;
            out.add(new RowResult(line, name, "OK", dryRun ? "Will be added." : "Added."));
        }
        return new Result(dryRun, rows.size() - 1, added, rows.size() - 1 - added, out);
    }
}
