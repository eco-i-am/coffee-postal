package ph.coffeepostal.pos.engagement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/**
 * Training: HQ publishes a video (a link), a document (a file up to 8 MB) or a link; branch staff open it and mark it done,
 * and HQ sees who has finished each one.
 */
class TrainingApis {
    static final int MAX_FILE = 8 * 1024 * 1024;
    static final Set<String> FILE_TYPES = Set.of("application/pdf", "image/jpeg", "image/png",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    record Material(long id, String title, String description, String kind, String url, String fileName, boolean hasFile, Instant createdAt, int done, boolean mine) {}
    record Done(String name, String branch, Instant at) {}
    record CreateReq(@NotBlank @Size(max = 120) String title, @Size(max = 1000) String description, @NotBlank String kind, @Size(max = 500) String url) {}

    @Service
    static class Svc {
        private final JdbcTemplate jdbc;

        Svc(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        List<Material> list(long userId) {
            return jdbc.query("""
                SELECT m.id, m.title, m.description, m.kind, m.url, m.file_name, m.data IS NOT NULL AS has_file, m.created_at,
                       (SELECT count(*) FROM training_completions c WHERE c.material_id = m.id) AS done,
                       EXISTS (SELECT 1 FROM training_completions c WHERE c.material_id = m.id AND c.user_id = ?) AS mine
                FROM training_materials m WHERE m.removed_at IS NULL ORDER BY m.created_at DESC
                """, (rs, i) -> new Material(rs.getLong("id"), rs.getString("title"), rs.getString("description"), rs.getString("kind"), rs.getString("url"),
                    rs.getString("file_name"), rs.getBoolean("has_file"), rs.getTimestamp("created_at").toInstant(), rs.getInt("done"), rs.getBoolean("mine")), userId);
        }

        @Transactional
        long create(long userId, CreateReq r) {
            String kind = r.kind().trim().toUpperCase();
            if (!Set.of("VIDEO", "DOCUMENT", "LINK").contains(kind)) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick Video, Document or Link.", Map.of("kind", "Pick one."));
            String url = r.url() == null || r.url().isBlank() ? null : r.url().trim();
            if (!kind.equals("DOCUMENT")) {
                if (url == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Paste the link.", Map.of("url", "Paste the link."));
                try {
                    String scheme = URI.create(url).getScheme();
                    if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) throw new IllegalArgumentException();
                } catch (IllegalArgumentException e) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "That link does not look right. It should start with https://", Map.of("url", "Use a full web address."));
                }
            } else url = null;
            return jdbc.queryForObject("INSERT INTO training_materials(title, description, kind, url, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class,
                r.title().trim(), r.description() == null || r.description().isBlank() ? null : r.description().trim(), kind, url, userId);
        }

        @Transactional
        void putFile(long id, String name, String type, byte[] data) {
            if (data == null || data.length == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a file.");
            if (data.length > MAX_FILE) throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "That file is too big. Keep it under 8 MB, or share a link instead.");
            String ct = type == null ? "" : type.split(";")[0].trim().toLowerCase();
            if (!FILE_TYPES.contains(ct)) throw new ApiException(HttpStatus.BAD_REQUEST, "Use a PDF, Word, PowerPoint, Excel, JPG or PNG file.");
            int n = jdbc.update("UPDATE training_materials SET file_name = ?, content_type = ?, data = ? WHERE id = ? AND kind = 'DOCUMENT' AND removed_at IS NULL",
                name, ct, data, id);
            if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That training item was not found, or it is not a document.");
        }

        ResponseEntity<byte[]> file(long id) {
            try {
                Map<String, Object> r = jdbc.queryForMap("SELECT file_name, content_type, data FROM training_materials WHERE id = ? AND removed_at IS NULL AND data IS NOT NULL", id);
                String name = String.valueOf(r.get("file_name")).replaceAll("[^A-Za-z0-9._ -]", "_");
                return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, String.valueOf(r.get("content_type")))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                    .header("X-Content-Type-Options", "nosniff").body((byte[]) r.get("data"));
            } catch (EmptyResultDataAccessException e) {
                throw new ApiException(HttpStatus.NOT_FOUND, "That file was not found.");
            }
        }

        @Transactional
        boolean remove(long id) { return jdbc.update("UPDATE training_materials SET removed_at = now() WHERE id = ? AND removed_at IS NULL", id) > 0; }

        @Transactional
        void complete(long userId, long id) {
            if (jdbc.queryForObject("SELECT count(*) FROM training_materials WHERE id = ? AND removed_at IS NULL", Integer.class, id) == 0)
                throw new ApiException(HttpStatus.NOT_FOUND, "That training item was not found.");
            jdbc.update("INSERT INTO training_completions(material_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", id, userId);
        }

        @Transactional
        void undo(long userId, long id) { jdbc.update("DELETE FROM training_completions WHERE material_id = ? AND user_id = ?", id, userId); }

        List<Done> completions(long id) {
            return jdbc.query("""
                SELECT u.full_name, coalesce((SELECT string_agg(b.name, ', ') FROM user_branches ub JOIN branches b ON b.id = ub.branch_id WHERE ub.user_id = u.id), 'HQ') AS branch, c.completed_at
                FROM training_completions c JOIN users u ON u.id = c.user_id WHERE c.material_id = ? ORDER BY c.completed_at DESC
                """, (rs, i) -> new Done(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()), id);
        }

        long userId(String username) {
            return jdbc.query("SELECT id FROM users WHERE username = ?", (rs, i) -> rs.getLong(1), username).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Your account was not found."));
        }
    }

    @RestController
    @RequestMapping("/api/admin/training")
    static class AdminApi {
        private final Svc svc;
        private final AuditService audit;

        AdminApi(Svc svc, AuditService audit) { this.svc = svc; this.audit = audit; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_training_admin_view')")
        List<Material> list(Principal who) { return svc.list(svc.userId(who.getName())); }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_training_admin_add')")
        Map<String, Object> create(@Valid @RequestBody CreateReq r, Principal who) {
            long id = svc.create(svc.userId(who.getName()), r);
            audit.record(who.getName(), null, "TRAINING_CREATE", "training", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        /** The file of a Document, sent as the raw request body; the name rides in a header. */
        @PutMapping(value = "/{id}/file", consumes = "*/*")
        @PreAuthorize("hasAuthority('PERM_training_admin_add')")
        Map<String, Object> putFile(@PathVariable long id, @RequestBody byte[] body, @RequestHeader("Content-Type") String type,
                                    @RequestParam(defaultValue = "document") String name) {
            svc.putFile(id, name.length() > 200 ? name.substring(0, 200) : name, type, body);
            return Map.of("id", id);
        }

        @GetMapping("/{id}/file")
        @PreAuthorize("hasAuthority('PERM_training_admin_view')")
        ResponseEntity<byte[]> file(@PathVariable long id) { return svc.file(id); }

        @GetMapping("/{id}/completions")
        @PreAuthorize("hasAuthority('PERM_training_admin_view')")
        List<Done> completions(@PathVariable long id) { return svc.completions(id); }

        @DeleteMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_training_admin_delete')")
        Map<String, Object> remove(@PathVariable long id, Principal who) {
            if (!svc.remove(id)) throw new ApiException(HttpStatus.NOT_FOUND, "That training item was not found.");
            audit.record(who.getName(), null, "TRAINING_REMOVE", "training", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }
    }

    @RestController
    @RequestMapping("/api/branch/training")
    static class BranchApi {
        private final Svc svc;

        BranchApi(Svc svc) { this.svc = svc; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_branch_training_view')")
        List<Material> list(Principal who) { return svc.list(svc.userId(who.getName())); }

        @GetMapping("/{id}/file")
        @PreAuthorize("hasAuthority('PERM_branch_training_view')")
        ResponseEntity<byte[]> file(@PathVariable long id) { return svc.file(id); }

        @PostMapping("/{id}/complete")
        @PreAuthorize("hasAuthority('PERM_branch_training_view')")
        Map<String, Object> complete(@PathVariable long id, Principal who) { svc.complete(svc.userId(who.getName()), id); return Map.of("id", id); }

        @DeleteMapping("/{id}/complete")
        @PreAuthorize("hasAuthority('PERM_branch_training_view')")
        Map<String, Object> undo(@PathVariable long id, Principal who) { svc.undo(svc.userId(who.getName()), id); return Map.of("id", id); }
    }
}
