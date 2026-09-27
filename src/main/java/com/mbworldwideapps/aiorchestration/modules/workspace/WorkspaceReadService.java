package com.mbworldwideapps.aiorchestration.modules.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.JobMemoryProperties;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryLinkLookup;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLinkRecord;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLinkResolver;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded, canonical record browsing. Graph projections are never the record inventory. */
@Service
public class WorkspaceReadService {
    public record Item(
            String id,
            String kind,
            String title,
            String status,
            String project,
            String updatedAt,
            String text,
            List<String> tags,
            List<String> editableFields,
            String blockedReason,
            Map<String, Object> info) {}

    public record Page(List<Item> items, String nextCursor) {}

    public record Link(
            String id,
            String source,
            String target,
            String label,
            String evidence,
            boolean inferred,
            boolean stale) {}

    public record Neighborhood(
            List<Item> nodes, List<Link> links, String nextCursor, List<String> warnings) {}

    private record Source(String sql) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RuleMemoryLinkLookup ruleLinks;
    private final ObjectProvider<QdrantClient> qdrant;
    private final JobMemoryProperties jobs;
    private MemoryRepository memories;
    private MemoryCodeLinkResolver codeLinks;

    public WorkspaceReadService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            RuleMemoryLinkLookup ruleLinks,
            ObjectProvider<QdrantClient> qdrant,
            JobMemoryProperties jobs) {
        this.jdbc = jdbc;
        this.json = json;
        this.ruleLinks = ruleLinks;
        this.qdrant = qdrant;
        this.jobs = jobs;
    }

    /** Enables explicit code-locator links in neighborhoods (optional so read-only tests can omit it). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCodeLinks(MemoryRepository memories, MemoryCodeLinkResolver codeLinks) {
        this.memories = memories;
        this.codeLinks = codeLinks;
    }

    private Source source(String kind) {
        // SQL fragments are a closed allowlist. No request value becomes SQL syntax.
        String sql =
                switch (kind) {
                    case "memory" ->
                            "SELECT id, summary title, status, project_key project, updated_at,"
                                + " text, tags,"
                                + " jsonb_build_object('scope',scope,'memoryType',memory_type,'sourceRef',source_ref)"
                                + " info FROM memory_items";
                    case "rule" ->
                            "SELECT d.id, v.statement title,d.status,d.project_key"
                                + " project,d.updated_at,v.statement text,'[]'::jsonb"
                                + " tags,jsonb_build_object('version',v.version,'rationale',v.rationale,'enforcement',v.enforcement,'originMemoryId',v.origin_memory_id,'appliesAll',v.applies_all)"
                                + " info FROM rule_definitions d JOIN rule_versions v ON"
                                + " v.rule_id=d.id AND v.version=d.current_version";
                    case "scan" ->
                            "SELECT id,root_path title,status,project_key"
                                + " project,coalesce(completed_at,started_at) updated_at,root_path"
                                + " text,'[]'::jsonb"
                                + " tags,jsonb_build_object('filesScanned',files_scanned,'filesDiscovered',files_discovered)"
                                + " info FROM code_scan_runs";
                    case "file" ->
                            "SELECT id,file_path title,'recorded'::text status,project_key"
                                + " project,last_scanned_at updated_at,file_path text,'[]'::jsonb"
                                + " tags,jsonb_build_object('language',language) info FROM"
                                + " code_files";
                    case "symbol" ->
                            "SELECT s.id,s.fqn title,'recorded'::text status,s.project_key"
                                + " project,f.last_scanned_at updated_at,s.signature"
                                + " text,'[]'::jsonb"
                                + " tags,jsonb_build_object('file',f.file_path,'line',s.start_line)"
                                + " info FROM code_symbols s JOIN code_files f ON f.id=s.file_id";
                    case "capsule" ->
                            "SELECT id,summary title,'recorded'::text status,project_key"
                                    + " project,updated_at,text,'[]'::jsonb"
                                    + " tags,jsonb_build_object('target',target_key) info FROM"
                                    + " code_semantic_capsules";
                    default -> throw new IllegalArgumentException(PanelText.t("Unknown record kind",
                            "Bilinmeyen kayıt türü"));
                };
        return new Source(sql);
    }

    public List<String> projects() {
        return jdbc.queryForList(
                "SELECT project_key FROM scanner_project_roots UNION SELECT project_key FROM"
                    + " memory_items WHERE project_key IS NOT NULL UNION SELECT project_key FROM"
                    + " rule_definitions WHERE project_key IS NOT NULL ORDER BY 1",
                String.class);
    }

    /** Newest-first lifecycle events of one memory (bounded); metadata is already redacted at write time. */
    public List<Map<String, Object>> memoryEvents(String id, int limit) {
        requireLimit(limit);
        return jdbc.query(
                "SELECT event_type, metadata::text metadata, created_at FROM memory_events WHERE memory_id=?::uuid"
                        + " ORDER BY created_at DESC LIMIT ?",
                (r, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("type", r.getString("event_type"));
                    row.put("at", r.getTimestamp("created_at").toInstant().toString());
                    row.put("metadata", parse(r.getString("metadata"), new TypeReference<Map<String, Object>>() {}));
                    return row;
                }, id, limit);
    }

    /** Overview counters for the panel. Project '' means all projects; global rows count everywhere. */
    public Map<String, Object> stats(String project) {
        String p = clean(project);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", p);
        out.put("memoryByStatus", counts(
                "SELECT status k, count(*) c FROM memory_items WHERE (?='' OR project_key=? OR project_key IS NULL)"
                        + " GROUP BY status", p));
        out.put("memoryByType", counts(
                "SELECT memory_type k, count(*) c FROM memory_items WHERE status='active' AND"
                        + " (?='' OR project_key=? OR project_key IS NULL) GROUP BY memory_type", p));
        out.put("rulesByStatus", counts(
                "SELECT status k, count(*) c FROM rule_definitions WHERE (?='' OR project_key=? OR project_key IS NULL)"
                        + " GROUP BY status", p));
        out.put("symbols", jdbc.queryForObject(
                "SELECT count(*) FROM code_symbols WHERE (?='' OR project_key=?)", Long.class, p, p));
        out.put("files", jdbc.queryForObject(
                "SELECT count(*) FROM code_files WHERE (?='' OR project_key=?)", Long.class, p, p));
        out.put("recentMemories", jdbc.query(
                "SELECT id, summary, status, project_key, memory_type, updated_at FROM memory_items"
                        + " WHERE (?='' OR project_key=? OR project_key IS NULL) ORDER BY updated_at DESC LIMIT 8",
                (r, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.getString("id"));
                    row.put("title", r.getString("summary"));
                    row.put("status", r.getString("status"));
                    row.put("project", r.getString("project_key"));
                    row.put("type", r.getString("memory_type"));
                    row.put("updatedAt", r.getTimestamp("updated_at").toInstant().toString());
                    return row;
                }, p, p));
        return out;
    }

    private Map<String, Long> counts(String sql, String project) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(sql, r -> {
            out.put(Objects.toString(r.getString("k"), "unknown"), r.getLong("c"));
        }, project, project);
        return out;
    }

    public Page list(
            String kind, String project, String query, String status, String cursor, int limit) {
        return list(kind, project, query, status, "", "", "", cursor, limit);
    }

    public Page list(
            String kind, String project, String query, String status, String type, String cursor, int limit) {
        return list(kind, project, query, status, type, "", "", cursor, limit);
    }

    /**
     * {@code type} filters on info.memoryType, {@code scope} on info.scope (global|project) and {@code tag} on an
     * exact tag (memory only); '' disables each filter.
     */
    public Page list(
            String kind, String project, String query, String status, String type, String scope, String tag,
            String cursor, int limit) {
        requireLimit(limit);
        if ("saved".equals(kind)) return savedPage(query, cursor, limit);
        if ("checkpoint".equals(kind)) {
            List<Item> rows =
                    jdbc.query(
                            "SELECT content,updated_at FROM last_job WHERE singleton_id=1",
                            (r, n) ->
                                    new Item(
                                            "checkpoint:1",
                                            kind,
                                            PanelText.t("Last job", "Son çalışma"),
                                            "saved",
                                            null,
                                            r.getTimestamp(2).toInstant().toString(),
                                            r.getString(1),
                                            List.of(),
                                            List.of(),
                                            PanelText.t("Local work note", "Yerel çalışma notu"),
                                            Map.of()));
            return new Page(rows, null);
        }
        String after = decodeCursor(cursor);
        String sql =
                "SELECT id,title,status,project,updated_at,left(text,240) text,tags,info FROM ("
                        + source(kind).sql
                        + ") s "
                        + "WHERE (?='' OR project=?"
                        + (Set.of("memory", "rule").contains(kind) ? " OR project IS NULL" : "")
                        + ") AND (?='' OR title ILIKE ? OR text ILIKE ?) AND (?='' OR status=?) AND"
                        + " (?='' OR info->>'memoryType'=?) AND (?='' OR info->>'scope'=?) AND"
                        + " (?='' OR tags @> jsonb_build_array(?::text)) AND"
                        + " (?='' OR id::text>?) ORDER BY id::text LIMIT ?";
        String p = clean(project), q = clean(query), st = clean(status), ty = clean(type),
                sc = clean(scope), tg = clean(tag);
        List<Item> rows =
                jdbc.query(
                        sql,
                        (r, n) -> item(r, kind, false),
                        p,
                        p,
                        q,
                        "%" + escapeLike(q) + "%",
                        "%" + escapeLike(q) + "%",
                        st,
                        st,
                        ty,
                        ty,
                        sc,
                        sc,
                        tg,
                        tg,
                        after,
                        after,
                        limit + 1);
        return page(rows, limit);
    }

    /** Navigation groups describe containment, never inferred semantic relationships. */
    public Page hierarchy(
            String kind,
            String project,
            String group,
            String query,
            String status,
            String cursor,
            int limit) {
        requireLimit(limit);
        if (!Set.of("rule", "memory").contains(kind))
            throw new IllegalArgumentException("Katman desteklenmiyor");
        String scopeSql =
                "memory".equals(kind)
                        ? "SELECT id, 'Kaynak · ' || ref label FROM (SELECT id,ref, count(*) OVER"
                              + " (PARTITION BY project,ref) members FROM (SELECT id,project,CASE"
                              + " WHEN info->>'sourceRef' ~ '^auto-curated:queue:[0-9]+:[0-9]+$'"
                              + " THEN 'auto-curated:queue' WHEN info->>'sourceRef' LIKE '%/%' THEN"
                              + " regexp_replace(info->>'sourceRef','/[^/]*$','/') ELSE"
                              + " info->>'sourceRef' END ref FROM filtered WHERE"
                              + " coalesce(info->>'sourceRef','') <> '') normalized) sources"
                                + (clean(group).isEmpty() ? " WHERE members >= 2" : "")
                        : """
SELECT b.rule_id id, b.binding_kind || ' · ' || b.target_key label
FROM rule_target_bindings b JOIN filtered f ON f.id=b.rule_id
WHERE b.rule_version=(f.info->>'version')::int
UNION
SELECT g.rule_id id, 'Koşullar · ' || string_agg(
  p.polarity || ' ' || p.field || ' ' || p.operator || ' ' || p.values::text,
  ' AND ' ORDER BY p.ordinal) label
FROM rule_selector_groups g JOIN filtered f ON f.id=g.rule_id
JOIN rule_selector_predicates p ON p.group_id=g.id
WHERE g.rule_version=(f.info->>'version')::int GROUP BY g.id,g.rule_id
""";
        String levelSql;
        if (clean(project).isEmpty()) {
            levelSql =
                    """
SELECT id::text, title, status, project, updated_at, left(text,240) text,tags,info,
  ?::text kind FROM filtered WHERE project IS NULL
UNION ALL
SELECT '!project-' || md5(project),project,'group',project,max(updated_at),
  ''::text,'[]'::jsonb,jsonb_build_object('level','project','count',count(*)),
  'project-group' FROM filtered WHERE project IS NOT NULL GROUP BY project
""";
        } else if (clean(group).isEmpty()) {
            levelSql =
                    """
SELECT id::text,title,status,project,updated_at,left(text,240) text,tags,info,
  ?::text kind FROM filtered f WHERE project=? AND NOT EXISTS
  (SELECT 1 FROM scopes s WHERE s.id=f.id)
UNION ALL
SELECT '!scope-' || md5(s.label),s.label,'group',f.project,max(f.updated_at),
  ''::text,'[]'::jsonb,jsonb_build_object('level','scope','group',md5(s.label),'count',count(DISTINCT f.id)),
  'scope-group' FROM scopes s JOIN filtered f ON f.id=s.id
  WHERE f.project=? GROUP BY s.label,f.project
""";
        } else {
            levelSql =
                    """
                    SELECT id::text,title,status,project,updated_at,left(text,240) text,tags,info,
                      ?::text kind FROM filtered f WHERE project=? AND EXISTS
                      (SELECT 1 FROM scopes s WHERE s.id=f.id AND md5(s.label)=?)
                    """;
        }
        String sql =
                "WITH filtered AS (SELECT * FROM ("
                        + source(kind).sql
                        + ") records "
                        + "WHERE (?='' OR title ILIKE ? OR text ILIKE ?) AND (?='' OR status=?)), "
                        + "scopes AS ("
                        + scopeSql
                        + "), nodes AS ("
                        + levelSql
                        + ") "
                        + "SELECT * FROM nodes WHERE (?='' OR id>?) ORDER BY id LIMIT ?";
        String q = clean(query), st = clean(status), after = decodeCursor(cursor);
        List<Object> args =
                new ArrayList<>(
                        List.of(
                                q,
                                "%" + escapeLike(q) + "%",
                                "%" + escapeLike(q) + "%",
                                st,
                                st,
                                kind));
        if (!clean(project).isEmpty()) {
            args.add(project);
            args.add(clean(group).isEmpty() ? project : group);
        }
        args.add(after);
        args.add(after);
        args.add(limit + 1);
        return page(
                jdbc.query(sql, (r, n) -> item(r, r.getString("kind"), false), args.toArray()),
                limit);
    }

    public Map<String,Object> codeMemory(String project, String cursor) {
        if (project == null || project.isBlank() || project.length()>200) throw new IllegalArgumentException("Project is required");
        Page page=list("memory",project,"","",cursor,40);
        Map<String,Item> nodes=new LinkedHashMap<>(); Map<String,Link> edges=new LinkedHashMap<>();
        Set<String> warnings=new LinkedHashSet<>();
        for (Item memory:page.items()) {
            if (!project.equals(memory.project()) || !Set.of("active","stale").contains(memory.status())) continue;
            nodes.put(memory.id(),overlayItem(memory));
            Neighborhood nearby=neighbors("memory",memory.id().substring("memory:".length()),null,false,50);
            for (Item node:nearby.nodes())
                if (Set.of("memory","symbol","file","directory","reference").contains(node.kind())
                        && (project.equals(node.project()) || "reference".equals(node.kind()))
                        && (!"memory".equals(node.kind()) || Set.of("active","stale").contains(node.status())))
                    nodes.put(node.id(),overlayItem(node));
            for (Link edge:nearby.links())
                if (!edge.inferred() && nodes.containsKey(edge.source()) && nodes.containsKey(edge.target())) edges.put(edge.id(),edge);
            warnings.addAll(nearby.warnings());
            if (nearby.nextCursor()!=null) warnings.add(PanelText.t("Only some memory links are shown; open the detail to see every neighbour.",
                    "Bazı memory bağlantıları sınırlı gösteriliyor; ayrıntıdan tüm komşuları açabilirsiniz."));
        }
        Map<String,Object> result=new LinkedHashMap<>(); result.put("nodes",nodes.values()); result.put("links",edges.values());
        result.put("nextCursor",page.nextCursor()); result.put("warnings",warnings); return result;
    }

    private static Item overlayItem(Item item) {
        String title=Objects.toString(item.title(),""), text=Objects.toString(item.text(),"");
        if(title.length()>512) title=title.substring(0,512)+"…";
        if(text.length()>2048) text=text.substring(0,2048)+"…";
        return new Item(item.id(),item.kind(),title,item.status(),item.project(),item.updatedAt(),text,
                List.of(),List.of(),"",Map.of());
    }

    /**
     * Bir instruction kuralının KAPSAMINI gerçek kayıtlı bağlamaları kullanarak çözer.
     * Frontend'de prose/regex tahmini yapılmaması için vardır.
     *
     * Eşleştirme semantiği InstructionReadService.read ile birebir aynıdır:
     * MODULE kapsamı `rule_target_bindings.binding_kind='path_glob'` satırlarından gelir ve
     * `core/**` deseni `core` dizininin kendisi ve altındaki her yol için geçerlidir.
     * Burada YENİ bir glob dili icat edilmez.
     *
     * Kapsam eşleşmesi UYGUNLUK DEĞİLDİR: bu yanıt hiçbir zaman "kurala uyuyor" demez.
     */
    public Map<String, Object> ruleScope(String ruleId, String project, int limit) {
        requireLimit(limit);
        if (project == null || project.isBlank() || project.length() > 200)
            throw new IllegalArgumentException("Project is required");
        Map<String, Object> rule;
        try {
            rule = jdbc.queryForMap(
                    "SELECT d.project_key, v.version, v.applies_all, v.enforcement"
                        + " FROM rule_definitions d JOIN rule_versions v"
                        + " ON v.rule_id=d.id AND v.version=d.current_version WHERE d.id=?::uuid",
                    ruleId);
        } catch (EmptyResultDataAccessException missing) {
            throw new NoSuchElementException("rule not found");
        }
        String ruleProject = (String) rule.get("project_key");
        boolean appliesAll = Boolean.TRUE.equals(rule.get("applies_all"));
        String scopeType = ruleProject == null ? "GLOBAL_STRICT" : appliesAll ? "PROJECT" : "MODULE";

        // dir/** → the directory (prefix match); a file binding → that file (exact match).
        List<String> modulePaths = jdbc.queryForList(
                "SELECT CASE WHEN binding_kind='path_glob' THEN left(target_key, length(target_key)-3)"
                    + " ELSE target_key END FROM rule_target_bindings"
                    + " WHERE rule_id=?::uuid AND rule_version=? AND binding_kind IN ('path_glob','file')"
                    + " ORDER BY target_key",
                String.class, ruleId, rule.get("version"));
        List<String> symbols = jdbc.queryForList(
                "SELECT target_key FROM rule_target_bindings"
                    + " WHERE rule_id=?::uuid AND rule_version=? AND binding_kind='symbol' ORDER BY target_key",
                String.class, ruleId, rule.get("version"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", ruleId);
        out.put("ruleVersion", rule.get("version"));
        out.put("scopeType", scopeType);
        out.put("enforcement", rule.get("enforcement"));
        out.put("ruleProjectKey", ruleProject);
        out.put("modulePaths", modulePaths);
        if (!symbols.isEmpty()) out.put("symbols", symbols);
        out.put("matcherContract", "instruction-module-prefix/v1");
        // Kapsam eşleşmesi asla uygunluk kanıtı değildir.
        out.put("complianceEvaluated", false);
        out.put("complianceNote",
                PanelText.t("A scope match is not evidence that the code complies with the rule; no check has run for"
                                + " this rule.",
                        "Kapsam eşleşmesi kodun kurala uygunluğunun kanıtı değildir; bu kural için çalıştırılmış bir"
                                + " check sonucu yok."));

        boolean projectApplies = ruleProject == null || ruleProject.equals(project);
        out.put("appliesToProject", projectApplies);
        Integer projectFiles = jdbc.queryForObject(
                "SELECT count(*)::int FROM code_files WHERE project_key=?", Integer.class, project);
        out.put("projectFileCount", projectFiles == null ? 0 : projectFiles);

        if (!projectApplies) {
            out.put("coverage", "out-of-scope");
            out.put("matchedFiles", List.of());
            out.put("matchedTotal", 0);
            out.put("truncated", false);
            return out;
        }
        if (!"MODULE".equals(scopeType)) {
            // Proje geneli kapsam: sembol başına kenar üretmek yasak (T41).
            out.put("coverage", "project-wide");
            out.put("matchedFiles", List.of());
            out.put("matchedTotal", projectFiles == null ? 0 : projectFiles);
            out.put("truncated", false);
            return out;
        }
        if (modulePaths.isEmpty()) {
            out.put("coverage", "unresolved");
            out.put("matchedFiles", List.of());
            out.put("matchedTotal", 0);
            out.put("truncated", false);
            out.put("warning", PanelText.t("The rule has MODULE scope but no recorded path binding; the scope could not"
                            + " be resolved.",
                    "Kural MODULE kapsamında ama kayıtlı path bağlaması yok; kapsam çözümlenemedi."));
            return out;
        }
        String[] paths = modulePaths.toArray(String[]::new);
        Integer total = jdbc.queryForObject(
                "SELECT count(*)::int FROM code_files f WHERE f.project_key=? AND EXISTS ("
                    + " SELECT 1 FROM unnest(?::text[]) p(value)"
                    + " WHERE f.file_path = p.value OR starts_with(f.file_path, p.value || '/'))",
                Integer.class, project, paths);
        List<String> files = jdbc.queryForList(
                "SELECT f.file_path FROM code_files f WHERE f.project_key=? AND EXISTS ("
                    + " SELECT 1 FROM unnest(?::text[]) p(value)"
                    + " WHERE f.file_path = p.value OR starts_with(f.file_path, p.value || '/'))"
                    + " ORDER BY f.file_path LIMIT ?",
                String.class, project, paths, limit);
        out.put("coverage", "module-paths");
        out.put("matchedFiles", files);
        out.put("matchedTotal", total == null ? 0 : total);
        out.put("truncated", total != null && total > files.size());
        return out;
    }

    public Map<String, Object> codeGraph(String project, String part, String cursor, int limit) {
        if (project == null || project.isBlank() || limit < 1 || limit > 5000)
            throw new IllegalArgumentException("Project and limit 1..5000 required");
        String after = cursor == null ? "" : cursor;
        if (!after.isEmpty()) UUID.fromString(after);
        String sql =
                switch (part) {
                    case "nodes" ->
                            "SELECT"
                                + " s.id,s.project_key,s.symbol_kind,s.name,s.fqn,s.role,s.start_line,f.file_path"
                                + " FROM code_symbols s JOIN code_files f ON f.id=s.file_id WHERE"
                                + " s.project_key=? AND (?='' OR s.id::text>?) ORDER BY s.id::text"
                                + " LIMIT ?";
                    case "edges" ->
                            "SELECT"
                                + " id,source_symbol_id,target_symbol_id,target_ref,edge_type,resolution,confidence"
                                + " FROM code_edges WHERE project_key=? AND (?='' OR id::text>?)"
                                + " ORDER BY id::text LIMIT ?";
                    default -> throw new IllegalArgumentException("Unknown graph part");
                };
        var rows = jdbc.queryForList(sql, project, after, after, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("nextCursor", more ? items.getLast().get("id").toString() : null);
        return result;
    }

    public Item get(String kind, String id) {
        if ("checkpoint".equals(kind))
            return list(kind, "", "", "", null, 1).items().stream()
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("Son çalışma kaydı yok"));
        if ("saved".equals(kind)) return savedGet(id);
        if ("rule-version".equals(kind)) {
            String[] parts = id.split("~", 2);
            if (parts.length != 2) throw new IllegalArgumentException(PanelText.t("Invalid rule version",
                    "Geçersiz kural sürümü"));
            UUID ruleId = UUID.fromString(parts[0]);
            int version = Integer.parseInt(parts[1]);
            if (version < 1) throw new IllegalArgumentException(PanelText.t("Invalid rule version",
                    "Geçersiz kural sürümü"));
            var rows =
                    jdbc.query(
                            "SELECT * FROM ("
                                    + source("rule")
                                            .sql
                                            .replace(
                                                    "v.version=d.current_version",
                                                    "v.version=" + version)
                                            .replace("d.updated_at", "v.created_at AS updated_at")
                                    + ") s WHERE id=?",
                            (r, n) -> item(r, "rule", false),
                            ruleId);
            if (rows.isEmpty()) throw new NoSuchElementException("Kural sürümü bulunamadı");
            Item v = rows.getFirst();
            return new Item(
                    "rule-version:" + id,
                    kind,
                    v.title(),
                    "history",
                    v.project(),
                    v.updatedAt(),
                    v.text(),
                    v.tags(),
                    List.of(),
                    PanelText.t("A past version cannot be changed. Create a new version from the current rule.",
                            "Geçmiş sürüm değiştirilemez. Geçerli kuraldan yeni sürüm oluşturabilirsiniz."),
                    v.info());
        }
        UUID key = UUID.fromString(id);
        List<Item> rows =
                jdbc.query(
                        "SELECT * FROM (" + source(kind).sql + ") s WHERE id=?",
                        (r, n) -> item(r, kind, true),
                        key);
        if (rows.isEmpty()) throw new NoSuchElementException("Kayıt bulunamadı");
        return rows.getFirst();
    }

    private Item item(ResultSet r, String kind, boolean detail) throws SQLException {
        String id = r.getObject("id").toString();
        Map<String, Object> info = parse(r.getString("info"), new TypeReference<>() {});
        List<String> tags = parse(r.getString("tags"), new TypeReference<>() {});
        boolean linked =
                detail
                        && "memory".equals(kind)
                        && "rule".equals(info.get("memoryType"))
                        && ruleLinks.hasLinkedDefinition(UUID.fromString(id));
        List<String> editable =
                detail && "memory".equals(kind) && !linked
                        ? List.of("summary", "text", "tags")
                        : detail && "rule".equals(kind) && "active".equals(r.getString("status"))
                                ? List.of("statement", "rationale")
                                : List.of();
        return new Item(
                kind + ":" + id,
                kind,
                r.getString("title"),
                r.getString("status"),
                r.getString("project"),
                r.getTimestamp("updated_at").toInstant().toString(),
                r.getString("text"),
                tags,
                editable,
                linked
                        ? PanelText.t("This memory belongs to a rule's history. Create a new version from that rule.",
                                "Bu memory kural geçmişine bağlı. İlgili kuraldan yeni sürüm oluşturun.")
                        : "",
                info);
    }

    public Neighborhood neighbors(
            String kind, String id, String cursor, boolean similarity, int limit) {
        requireLimit(limit);
        Item center = get(kind, id);
        String after = decodeCursor(cursor);
        List<Link> edges = new ArrayList<>();
        Map<String, Item> nodes = new LinkedHashMap<>();
        nodes.put(center.id(), center);
        List<String> warnings = new ArrayList<>();
        if ("rule-version".equals(kind)) {
            Item current = get("rule", id.split("~", 2)[0]);
            nodes.put(current.id(), current);
            edges.add(
                    new Link(
                            "current",
                            center.id(),
                            current.id(),
                            PanelText.t("Current rule", "Geçerli kural"),
                            PanelText.t("Current version of the same rule", "Aynı kuralın güncel sürümü"),
                            false,
                            false));
            Object origin = center.info().get("originMemoryId");
            if (origin != null) {
                try {
                    Item m = get("memory", origin.toString());
                    nodes.put(m.id(), m);
                    edges.add(
                            new Link(
                                    "origin",
                                    center.id(),
                                    m.id(),
                                    PanelText.t("Source memory", "Kaynak memory"),
                                    PanelText.t("Version ", "Sürüm ") + center.info().get("version"),
                                    false,
                                    false));
                } catch (NoSuchElementException e) {
                    warnings.add(PanelText.t("The source memory of this version no longer exists.",
                            "Bu sürümün kaynak memorysi artık mevcut değil."));
                }
            }
            List<Link> page =
                    edges.stream()
                            .filter(e -> e.id().compareTo(after) > 0)
                            .sorted(Comparator.comparing(Link::id))
                            .toList();
            boolean more = page.size() > limit;
            page = page.stream().limit(limit).toList();
            Set<String> included = new HashSet<>();
            included.add(center.id());
            page.forEach(e -> included.add(e.target()));
            return new Neighborhood(
                    nodes.values().stream().filter(n -> included.contains(n.id())).toList(),
                    page,
                    more ? encodeCursor(page.getLast().id()) : null,
                    warnings);
        }
        String sql = relationSql(kind);
        if (sql != null) {
            List<Map<String, Object>> rows =
                    jdbc.queryForList(
                            "SELECT * FROM ("
                                    + sql
                                    + ") edges WHERE edge_id>? ORDER BY edge_id LIMIT ?",
                            id,
                            after,
                            limit + 1);
            for (var row : rows) {
                String targetKind = (String) row.get("target_kind"),
                        targetId = (String) row.get("target_id");
                String target = targetKind + ":" + targetId;
                try {
                    nodes.put(target, get(targetKind, targetId));
                } catch (NoSuchElementException e) {
                    nodes.put(
                            target,
                            new Item(
                                    target,
                                    targetKind,
                                    PanelText.t("Record that no longer exists", "Artık mevcut olmayan kayıt"),
                                    "missing",
                                    center.project(),
                                    "",
                                    "",
                                    List.of(),
                                    List.of(),
                                    "",
                                    Map.of()));
                }
                edges.add(
                        new Link(
                                (String) row.get("edge_id"),
                                center.id(),
                                target,
                                (String) row.get("label"),
                                (String) row.get("evidence"),
                                false,
                                false));
            }
        }
        if (Set.of("memory", "file", "symbol").contains(kind))
            resolvedLinks(center, after, similarity, limit + 1, nodes, edges, warnings);
        edges.sort(Comparator.comparing(Link::id));
        boolean more = edges.size() > limit;
        List<Link> result = edges.stream().limit(limit).toList();
        Set<String> keep = new HashSet<>();
        keep.add(center.id());
        result.forEach(e -> {
            keep.add(e.source());
            keep.add(e.target());
        });
        return new Neighborhood(
                nodes.values().stream().filter(n -> keep.contains(n.id())).toList(),
                result,
                more ? encodeCursor(result.getLast().id()) : null,
                warnings);
    }

    /**
     * Memory links that are not navigation anchors: explicit code locators (resolved against the latest scan)
     * and memory↔memory / memory→reference relations. Code lookups in the file/symbol direction scan the
     * project's memories, which is fine for the local, per-developer scale this panel serves.
     */
    private void resolvedLinks(
            Item center,
            String after,
            boolean similarity,
            int limit,
            Map<String, Item> nodes,
            List<Link> links,
            List<String> warnings) {
        Set<String> linked = new HashSet<>();
        links.forEach(l -> linked.add(l.source() + ">" + l.target()));
        String rawId = center.id().split(":", 2)[1];
        List<Link> found = new ArrayList<>();
        if ("memory".equals(center.kind())) {
            relationLinks(center, rawId, nodes, found);
        }
        if (memories == null || codeLinks == null) {
            warnings.add(PanelText.t("Code links: the resolver is not enabled.",
                    "Kod bağlantıları: çözümleyici etkin değil."));
        } else {
            try {
                if ("memory".equals(center.kind())) {
                    memories.findById(UUID.fromString(rawId)).ifPresent(item ->
                            codeLinkItems(center, item, similarity, nodes, found, false));
                } else {
                    UUID afterId = null;
                    while (found.size() < limit) {
                        List<MemoryItem> batch = memories.findEligibleForGraphProjection(center.project(), afterId, 200);
                        if (batch.isEmpty()) break;
                        for (MemoryItem item : batch) codeLinkItems(center, item, similarity, nodes, found, true);
                        afterId = batch.getLast().id();
                    }
                }
            } catch (RuntimeException e) {
                warnings.add(PanelText.t("Code links could not be resolved right now. Showing the recorded SQL relations.",
                        "Kod bağlantıları şu an çözümlenemedi. Kayıtlı SQL ilişkileri gösteriliyor."));
            }
        }
        for (Link link : found) {
            if (link.id().compareTo(after) <= 0 || !linked.add(link.source() + ">" + link.target())) continue;
            links.add(link);
        }
    }

    private void codeLinkItems(Item center, MemoryItem memory, boolean similarity, Map<String, Item> nodes,
            List<Link> found, boolean incoming) {
        for (MemoryCodeLinkRecord link : codeLinks.resolve(memory).links()) {
            boolean inferred = "similarity_backfill".equals(link.resolution());
            if (inferred && !similarity) continue;
            String kind;
            String raw;
            String title;
            switch (link.targetKind()) {
                case FILE -> { kind = "file"; raw = String.valueOf(link.fileId()); title = PanelText.t("File", "Dosya"); }
                case SYMBOL -> {
                    kind = "symbol"; raw = String.valueOf(link.symbolId()); title = PanelText.t("Symbol", "Sembol");
                }
                case DIRECTORY -> { kind = "directory"; raw = link.directoryPath(); title = link.directoryPath(); }
                default -> {
                    kind = "capsule"; raw = link.capsuleLogicalKey(); title = PanelText.t("Code summary", "Kod özeti");
                }
            }
            if (raw == null || raw.isBlank() || "null".equals(raw)) continue;
            String target = kind + ":" + raw;
            String memoryNode = "memory:" + memory.id();
            if (incoming && !target.equals(center.id())) continue;
            if (incoming) {
                nodes.putIfAbsent(memoryNode, lookup("memory", memory.id().toString(), memory.summary(), center));
            } else {
                nodes.putIfAbsent(target, lookup(kind, raw, title, center));
            }
            String evidence = link.resolution()
                    + (inferred ? PanelText.t(" · Similarity score: ", " · Benzerlik skoru: ") + link.confidence() : "");
            found.add(new Link("z:" + link.edgeId(), memoryNode, target, inferred ? PanelText.t("Similarity", "Benzerlik")
                    : link.relationshipType().name(), evidence, inferred, link.stale()));
        }
    }

    private void relationLinks(Item center, String memoryId, Map<String, Item> nodes, List<Link> found) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id, r.source_memory_id, r.target_memory_id, r.reference_id, r.relationship_type,
                       r.status, r.provenance, ref.relative_path
                FROM memory_relations r LEFT JOIN reference_items ref ON ref.id = r.reference_id
                WHERE r.source_memory_id = ?::uuid OR r.target_memory_id = ?::uuid
                """, memoryId, memoryId);
        for (var row : rows) {
            String type = ((String) row.get("relationship_type")).toUpperCase(java.util.Locale.ROOT);
            boolean stale = "stale".equals(row.get("status"));
            String source = "memory:" + row.get("source_memory_id");
            String target;
            if (row.get("reference_id") != null) {
                String refId = row.get("reference_id").toString();
                target = "reference:" + refId;
                Object path = row.get("relative_path");
                String title = path == null ? PanelText.t("Reference", "Referans") : path.toString();
                nodes.putIfAbsent(target, lookup("reference", refId, title, center));
            } else {
                target = "memory:" + row.get("target_memory_id");
                String other = source.equals(center.id()) ? target : source;
                nodes.putIfAbsent(other, lookup("memory", other.split(":", 2)[1], "Memory", center));
            }
            found.add(new Link("z:rel:" + row.get("id"), source, target, "MEMORY_" + type,
                    PanelText.t("Relation · ", "İlişki · ") + row.get("provenance"), false, stale));
        }
    }

    private Item lookup(String kind, String raw, String title, Item center) {
        try {
            return get(kind, raw);
        } catch (IllegalArgumentException | NoSuchElementException e) {
            return new Item(kind + ":" + raw, kind, title, "projection", center.project(), "", "",
                    List.of(), List.of(), "", Map.of());
        }
    }

    private String relationSql(String kind) {
        // The request's exact UUID is bound once in the CTE; retrieval != prompt injection.
        String prefix = "WITH selected AS (SELECT ?::uuid id) ";
        // Panel labels are fixed texts (never request data) quoted as SQL literals.
        String ruleOrigin = sqlText(PanelText.t("Rule origin", "Kuralın kaynağı"));
        String learned = sqlText(PanelText.t("Learned knowledge", "Öğrenilen bilgi"));
        String sourceMemory = sqlText(PanelText.t("Source memory", "Kaynak memory"));
        String version = sqlText(PanelText.t("Version ", "Sürüm "));
        String ruleVersion = sqlText(PanelText.t("Rule version ", "Kural sürümü "));
        String origins =
                "SELECT 'r:'||v.rule_id||':'||v.version edge_id,'rule-version'::text"
                    + " target_kind,v.rule_id::text||'~'||v.version target_id," + ruleOrigin
                    + "::text label," + ruleVersion + "||v.version evidence FROM rule_versions"
                    + " v,selected s WHERE v.origin_memory_id=s.id";
        // Learned knowledge is attached to code by the anchors the agent-learning capture resolved. Only
        // RESOLVED anchors are shown, matched exactly (symbol key = FQN plus parameter list, file = path),
        // so the view never invents a relation.
        String anchoredSymbol =
                " FROM memory_navigation_anchors a JOIN memory_items m ON m.id=a.memory_id"
                    + " JOIN code_symbols cs ON cs.project_key=m.project_key AND (cs.fqn=a.symbol_key OR"
                    + " cs.fqn||CASE WHEN strpos(cs.signature,'(')>0 THEN"
                    + " substr(cs.signature,strpos(cs.signature,'(')) ELSE '' END=a.symbol_key)"
                    + " WHERE a.locator_kind='symbol' AND a.resolution_state='RESOLVED'";
        String anchoredFile =
                " FROM memory_navigation_anchors a JOIN memory_items m ON m.id=a.memory_id"
                    + " JOIN code_files cf ON cf.project_key=m.project_key AND cf.file_path=a.canonical_ref"
                    + " WHERE a.locator_kind='file' AND a.resolution_state='RESOLVED'";
        String learnedCode =
                "SELECT 'a:'||a.id||':'||cs.id edge_id,'symbol'::text target_kind,cs.id::text target_id,"
                    + learned + "::text label,a.anchor_role||' · '||a.canonical_ref evidence"
                    + anchoredSymbol + " AND a.memory_id=(SELECT id FROM selected)"
                    + " UNION ALL SELECT 'a:'||a.id||':'||cf.id edge_id,'file'::text target_kind,cf.id::text"
                    + " target_id," + learned + "::text label,a.anchor_role||' · '||a.canonical_ref evidence"
                    + anchoredFile + " AND a.memory_id=(SELECT id FROM selected)";
        return switch (kind) {
            case "memory" -> prefix + origins + " UNION ALL " + learnedCode;
            case "symbol" ->
                    prefix
                            + "SELECT 'a:'||a.id||':'||cs.id edge_id,'memory'::text target_kind,"
                            + "a.memory_id::text target_id," + learned + "::text label,"
                            + "a.anchor_role||' · '||a.canonical_ref evidence"
                            + anchoredSymbol + " AND cs.id=(SELECT id FROM selected)";
            case "file" ->
                    prefix
                            + "SELECT 'a:'||a.id||':'||cf.id edge_id,'memory'::text target_kind,"
                            + "a.memory_id::text target_id," + learned + "::text label,"
                            + "a.anchor_role||' · '||a.canonical_ref evidence"
                            + anchoredFile + " AND cf.id=(SELECT id FROM selected)";
            case "rule" ->
                    prefix
                            + "SELECT 'v:'||v.version edge_id,'rule-version'::text"
                            + " target_kind,v.rule_id::text||'~'||v.version target_id," + version
                            + "||v.version label,v.created_at::text evidence FROM rule_versions"
                            + " v,selected s WHERE v.rule_id=s.id UNION ALL SELECT 'm:'||v.version"
                            + " edge_id,'memory'::text target_kind,v.origin_memory_id::text"
                            + " target_id," + sourceMemory + "::text label," + ruleVersion + "||v.version"
                            + " evidence FROM rule_versions v,selected s WHERE v.rule_id=s.id AND"
                            + " v.origin_memory_id IS NOT NULL";
            default -> null;
        };
    }

    private Page savedPage(String query, String cursor, int limit) {
        try {
            var request =
                    Points.ScrollPoints.newBuilder()
                            .setCollectionName(jobs.collectionName())
                            .setLimit(limit)
                            .setWithPayload(
                                    Points.WithPayloadSelector.newBuilder()
                                            .setInclude(
                                                    Points.PayloadIncludeSelector.newBuilder()
                                                            .addFields("title")
                                                            .addFields("summary")
                                                            .addFields("updatedAt")));
            if (!clean(query).isEmpty())
                request.setFilter(
                        Points.Filter.newBuilder()
                                .addShould(
                                        Points.Condition.newBuilder()
                                                .setField(
                                                        Points.FieldCondition.newBuilder()
                                                                .setKey("title")
                                                                .setMatch(
                                                                        Points.Match.newBuilder()
                                                                                .setText(query))))
                                .addShould(
                                        Points.Condition.newBuilder()
                                                .setField(
                                                        Points.FieldCondition.newBuilder()
                                                                .setKey("summary")
                                                                .setMatch(
                                                                        Points.Match.newBuilder()
                                                                                .setText(query)))));
            if (cursor != null && !cursor.isBlank())
                request.setOffset(
                        Points.PointId.newBuilder()
                                .setUuid(UUID.fromString(decodeCursor(cursor)).toString()));
            var response = qdrant.getObject().scrollAsync(request.build()).get(8, TimeUnit.SECONDS);
            List<Item> items =
                    response.getResultList().stream()
                            .map(p -> savedItem(p.getId().getUuid(), p.getPayloadMap()))
                            .toList();
            return new Page(
                    items,
                    response.hasNextPageOffset()
                            ? encodeCursor(response.getNextPageOffset().getUuid())
                            : null);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Yerel job kaynağına erişilemiyor", e);
        }
    }

    private Item savedGet(String id) {
        try {
            var points =
                    qdrant.getObject()
                            .retrieveAsync(
                                    jobs.collectionName(),
                                    List.of(
                                            Points.PointId.newBuilder()
                                                    .setUuid(UUID.fromString(id).toString())
                                                    .build()),
                                    true,
                                    false,
                                    null)
                            .get(8, TimeUnit.SECONDS);
            if (points.isEmpty()) throw new NoSuchElementException("Yerel job bulunamadı");
            return savedItem(id, points.getFirst().getPayloadMap());
        } catch (IllegalArgumentException | NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Yerel job kaynağına erişilemiyor", e);
        }
    }

    private Item savedItem(
            String id, Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload) {
        var metadata =
                payload.containsKey("metadata")
                        ? payload.get("metadata").getStructValue().getFieldsMap()
                        : payload;
        return new Item(
                "saved:" + id,
                "saved",
                field(metadata, "title"),
                "saved",
                null,
                field(metadata, "updatedAt"),
                (metadata.containsKey("content")
                        ? field(metadata, "content")
                        : field(metadata, "summary")),
                List.of(),
                List.of(),
                PanelText.t("Local work note", "Yerel çalışma notu"),
                Map.of("summary", field(metadata, "summary")));
    }

    private static String field(
            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> fields, String key) {
        return fields.containsKey(key) ? fields.get(key).getStringValue() : "";
    }

    private Page page(List<Item> rows, int limit) {
        boolean more = rows.size() > limit;
        List<Item> items = rows.stream().limit(limit).toList();
        return new Page(items, more ? encodeCursor(items.getLast().id().split(":", 2)[1]) : null);
    }

    private <T> T parse(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Kayıt verisi okunamadı", e);
        }
    }

    private static String sqlText(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static String encodeCursor(String s) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String decodeCursor(String s) {
        if (s == null || s.isBlank()) return "";
        if (s.length() > 1024) throw new IllegalArgumentException(PanelText.t("Invalid cursor", "Geçersiz cursor"));
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(PanelText.t("Invalid cursor", "Geçersiz cursor"));
        }
    }

    private static void requireLimit(int n) {
        if (n < 1 || n > 100) throw new IllegalArgumentException(PanelText.t("limit must be 1–100",
                "limit 1–100 olmalıdır"));
    }
}
