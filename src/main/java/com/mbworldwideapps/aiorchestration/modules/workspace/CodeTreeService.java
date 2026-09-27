package com.mbworldwideapps.aiorchestration.modules.workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The layered code map of the panel graph: project → package → class/file → member, opened one level at a time,
 * with the memories and rules attached at each node. A package is a source directory (named after its Java
 * package when one is known), so a package node and a {@code dir/**} rule mean the same thing.
 */
@Service
public class CodeTreeService {

    static final Set<String> CLASS_KINDS = Set.of("class", "interface", "enum", "record", "annotation", "object",
            "struct", "trait", "type");
    private static final int PAGE = 500;
    private static final int MAX_EDGES = 200;

    public record Node(String id, String kind, String name, String path, String fqn, String signature,
            int childCount, int memoryCount, int ruleCount) {}

    public record Tree(Node node, List<Node> children, String nextCursor) {}

    public record Attached(String id, String summary, String scope, String target) {}

    public record AttachedItems(List<Attached> memories, List<Attached> rules) {}

    public record Edge(String source, String target, String edgeType) {}

    public record Edges(List<Node> nodes, List<Edge> edges) {}

    /** A memory or rule tied to code: a directory, a file, or a symbol (fqn, optionally with #signature). */
    record Attachment(String id, String label, String scope, String dir, String file, String symbol) {}

    record Symbol(UUID id, String file, String kind, String name, String fqn, String signature, int line) {
        boolean isClass() {
            return CLASS_KINDS.contains(kind);
        }

        /** Owning class fqn of a member (`pkg.Owner#getPet` → `pkg.Owner`). */
        String owner() {
            int hash = fqn == null ? -1 : fqn.indexOf('#');
            return hash < 0 ? null : fqn.substring(0, hash);
        }

        String symbolKey() {
            String base = owner() == null ? fqn : owner() + "#";
            return signature == null || signature.isBlank() || owner() == null ? fqn : base + signature;
        }
    }

    private final JdbcTemplate jdbc;

    public CodeTreeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Tree children(String project, String parent, String cursor) {
        Snapshot s = snapshot(project);
        String id = parent == null || parent.isBlank() ? "project:" + project : parent;
        Node self = s.node(id);
        List<Node> all = s.childrenOf(id);
        int offset = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
        int end = Math.min(all.size(), offset + PAGE);
        return new Tree(self, all.subList(Math.min(offset, all.size()), end),
                end < all.size() ? Integer.toString(end) : null);
    }

    /** A search hit and the ids to open, outermost first, to make it visible (package, then class). */
    public record Hit(Node node, List<String> path) {}

    public record Hits(String query, List<Hit> hits) {}

    /**
     * Packages, classes, files and members whose name contains {@code query} (case-insensitive). Exact names rank first,
     * then prefixes; classes and packages before members.
     */
    public Hits search(String project, String query, int limit) {
        String q = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) return new Hits(query, List.of());
        Snapshot s = snapshot(project);
        record Scored(int score, String name, Hit hit) {}
        List<Scored> found = new ArrayList<>();
        java.util.function.BiFunction<String, Integer, Integer> score = (name, kindRank) -> {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            int at = n.indexOf(q);
            if (at < 0) return -1;
            return (n.equals(q) ? 0 : at == 0 ? 100 : 200) + kindRank;
        };
        for (Node pkg : s.childrenOf("project:" + project)) {
            int sc = score.apply(pkg.name(), 0);
            if (sc >= 0) found.add(new Scored(sc, pkg.name(), new Hit(pkg, List.of())));
        }
        for (Symbol sym : s.symbols.values()) {
            String owner = s.classOf(sym);
            if (!sym.isClass() && owner.startsWith("file:")) continue; // not reachable in the tree
            int sc = score.apply(sym.name(), sym.isClass() ? 1 : 3);
            if (sc < 0) continue;
            String pkg = "pkg:" + dirOf(sym.file());
            found.add(new Scored(sc, s.symbolNode(sym).name(), new Hit(s.symbolNode(sym), sym.isClass() ? List.of(pkg) : List.of(pkg, owner))));
        }
        for (var entry : s.filesByDir.entrySet()) {
            for (String file : entry.getValue()) {
                if (s.symbolsByFile.getOrDefault(file, List.of()).stream().anyMatch(Symbol::isClass)) continue;
                Node node = s.fileNode(file);
                int sc = score.apply(node.name(), 2);
                if (sc >= 0) found.add(new Scored(sc, node.name(), new Hit(node, List.of("pkg:" + entry.getKey()))));
            }
        }
        found.sort(Comparator.comparingInt(Scored::score).thenComparing(Scored::name));
        return new Hits(query, found.stream().limit(Math.max(1, Math.min(limit, 50))).map(Scored::hit).toList());
    }

    public AttachedItems attached(String project, String nodeId) {
        Snapshot s = snapshot(project);
        s.node(nodeId);
        List<Attached> memories = new ArrayList<>();
        List<Attached> rules = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Attachment a : s.attachmentsOf(nodeId)) {
            if (!seen.add(a.scope().charAt(0) + a.id())) continue;
            Attached row = new Attached(a.id(), a.label(), a.scope(), a.symbol() != null ? a.symbol()
                    : a.file() != null ? a.file() : a.dir() + "/**");
            if (a.scope().startsWith("rule")) rules.add(row); else memories.add(row);
        }
        return new AttachedItems(memories, rules);
    }

    /** Rules and memories attached to any of these symbols (a class also carries its file's attachments). */
    public AttachedItems attachedToSymbols(String project, List<UUID> symbolIds) {
        Snapshot s = snapshot(project);
        List<Attached> memories = new ArrayList<>();
        List<Attached> rules = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (UUID id : symbolIds) {
            Symbol sym = s.symbols.get(id);
            if (sym == null) continue;
            List<Attachment> on = new ArrayList<>(s.attachmentsOf(id.toString()));
            if (!sym.isClass()) on.addAll(s.attachmentsOf("file:" + sym.file())); // a member also gets file rules
            for (Attachment a : on) {
                if (!seen.add(a.scope().charAt(0) + a.id())) continue;
                Attached row = new Attached(a.id(), a.label(), a.scope(), a.symbol() != null ? a.symbol()
                        : a.file() != null ? a.file() : a.dir() + "/**");
                if (a.scope().startsWith("rule")) rules.add(row); else memories.add(row);
            }
        }
        return new AttachedItems(memories, rules);
    }

    public record Link(String source, String target, int weight) {}

    public record Links(String parent, List<Link> links) {}

    /**
     * The network between the children of one node: code edges (calls, injections, …) aggregated to the level that is
     * open. Under the project: package ↔ package; under a package: class/file ↔ class/file (a class stands for its
     * members); under a class: member ↔ member. Heaviest first, at most 400.
     */
    public Links links(String project, String parent) {
        Snapshot s = snapshot(project);
        String id = parent == null || parent.isBlank() ? "project:" + project : parent;
        s.node(id);
        java.util.function.Function<Symbol, String> bucket;
        if (id.startsWith("project:")) {
            bucket = sym -> "pkg:" + dirOf(sym.file());
        } else if (id.startsWith("pkg:")) {
            String dir = id.substring(4);
            bucket = sym -> !dir.equals(dirOf(sym.file())) ? null : s.classOf(sym);
        } else {
            Symbol cls = s.symbols.get(UUID.fromString(id));
            if (cls == null || !cls.isClass()) return new Links(id, List.of());
            bucket = sym -> sym.file().equals(cls.file()) && cls.fqn().equals(sym.owner()) ? sym.id().toString() : null;
        }
        Map<String, Integer> weights = new HashMap<>();
        jdbc.query("SELECT source_symbol_id, target_symbol_id FROM code_edges WHERE project_key = ? "
                + "AND target_symbol_id IS NOT NULL", rs -> {
            Symbol a = s.symbols.get(rs.getObject(1, UUID.class));
            Symbol b = s.symbols.get(rs.getObject(2, UUID.class));
            if (a == null || b == null) return;
            String x = bucket.apply(a);
            String y = bucket.apply(b);
            if (x == null || y == null || x.equals(y)) return;
            String key = x.compareTo(y) < 0 ? x + "\u0000" + y : y + "\u0000" + x; // undirected: one line per pair
            weights.merge(key, 1, Integer::sum);
        }, project);
        List<Link> links = weights.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(400)
                .map(e -> {
                    String[] ends = e.getKey().split("\u0000", 2);
                    return new Link(ends[0], ends[1], e.getValue());
                })
                .toList();
        return new Links(id, links);
    }

    /** Code edges of one symbol (a class includes its members), both directions, at most 200. */
    public Edges edges(String project, String symbolId) {
        Snapshot s = snapshot(project);
        Symbol center = s.symbols.get(UUID.fromString(symbolId));
        if (center == null) throw new NoSuchElementException("symbol not found");
        List<UUID> ids = new ArrayList<>(List.of(center.id()));
        if (center.isClass()) {
            // same file + owner fqn: a same-named class under another source root is a different class
            s.symbols.values().stream().filter(m -> center.fqn().equals(m.owner()) && center.file().equals(m.file()))
                    .forEach(m -> ids.add(m.id()));
        }
        List<Edge> edges = jdbc.query("""
                SELECT source_symbol_id::text, target_symbol_id::text, edge_type FROM code_edges
                WHERE project_key = ? AND target_symbol_id IS NOT NULL
                  AND (source_symbol_id = ANY(?::uuid[]) OR target_symbol_id = ANY(?::uuid[]))
                ORDER BY edge_type, source_symbol_id, target_symbol_id LIMIT ?""",
                (rs, n) -> new Edge(rs.getString(1), rs.getString(2), rs.getString(3)),
                project, ids.stream().map(UUID::toString).toArray(String[]::new),
                ids.stream().map(UUID::toString).toArray(String[]::new), MAX_EDGES);
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Edge e : edges) {
            for (String end : List.of(e.source(), e.target())) {
                if (!nodes.containsKey(end)) {
                    Symbol sym = s.symbols.get(UUID.fromString(end));
                    if (sym != null) nodes.put(end, s.symbolNode(sym));
                }
            }
        }
        return new Edges(List.copyOf(nodes.values()), edges);
    }

    // ------------------------------------------------------------------ snapshot of one project

    private Snapshot snapshot(String project) {
        if (project == null || project.isBlank() || project.length() > 200)
            throw new IllegalArgumentException("Project is required");
        Map<UUID, String> files = new LinkedHashMap<>();
        jdbc.query("SELECT id, file_path FROM code_files WHERE project_key = ? ORDER BY file_path",
                rs -> { files.put(rs.getObject(1, UUID.class), rs.getString(2)); }, project);
        Map<UUID, Symbol> symbols = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, file_id, lower(symbol_kind), name, fqn, signature, COALESCE(start_line, 0)
                FROM code_symbols WHERE project_key = ? ORDER BY file_id, start_line""", rs -> {
            String file = files.get(rs.getObject(2, UUID.class));
            if (file != null) {
                UUID id = rs.getObject(1, UUID.class);
                symbols.put(id, new Symbol(id, file, rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getInt(7)));
            }
        }, project);
        return new Snapshot(project, files.values(), symbols, attachments(project, symbols));
    }

    private List<Attachment> attachments(String project, Map<UUID, Symbol> symbols) {
        Map<String, String> fileOfSymbol = new HashMap<>();
        for (Symbol sym : symbols.values()) {
            fileOfSymbol.putIfAbsent(sym.fqn(), sym.file());
            fileOfSymbol.putIfAbsent(sym.symbolKey(), sym.file());
        }
        List<Attachment> out = new ArrayList<>();
        // memories: declared code locators + resolved navigation anchors
        jdbc.query("""
                SELECT m.id::text, m.summary, lower(x->>'kind'), x->>'ref', x->>'path'
                FROM memory_items m, jsonb_array_elements(COALESCE(m.metadata->'codeLocators'->'items', '[]'::jsonb)) x
                WHERE m.project_key = ? AND m.status IN ('active', 'stale')
                UNION ALL
                SELECT m.id::text, m.summary, lower(a.locator_kind), COALESCE(a.symbol_key, a.canonical_ref),
                       a.canonical_ref
                FROM memory_navigation_anchors a JOIN memory_items m ON m.id = a.memory_id
                WHERE m.project_key = ? AND m.status IN ('active', 'stale')""", rs -> {
            Attachment a = attachment(rs.getString(1), rs.getString(2), "memory", rs.getString(3), rs.getString(4),
                    rs.getString(5), fileOfSymbol);
            if (a != null) out.add(a);
        }, project, project);
        // active instruction rules of this project bound to a directory, file or symbol; a symbol binding is
        // located by its own rule's file binding (the fqn alone can repeat across source roots)
        Map<String, String> ruleFile = new HashMap<>();
        jdbc.query("""
                SELECT b.rule_id::text, b.target_key FROM rule_definitions d
                JOIN rule_target_bindings b ON b.rule_id = d.id AND b.rule_version = d.current_version
                WHERE d.status = 'active' AND d.project_key = ? AND b.binding_kind = 'file'""",
                rs -> { ruleFile.putIfAbsent(rs.getString(1), rs.getString(2)); }, project);
        jdbc.query("""
                SELECT d.id::text, v.statement, b.binding_kind, b.target_key
                FROM rule_definitions d
                JOIN rule_versions v ON v.rule_id = d.id AND v.version = d.current_version
                JOIN rule_target_bindings b ON b.rule_id = d.id AND b.rule_version = v.version
                WHERE d.status = 'active' AND v.enforcement = 'instruction' AND d.project_key = ?
                  AND b.binding_kind IN ('path_glob', 'file', 'symbol')""", rs -> {
            String kind = rs.getString(3);
            String key = rs.getString(4);
            String label = rs.getString(2);
            if (label != null && label.length() > 200) label = label.substring(0, 200) + "…";
            Attachment a = switch (kind) {
                case "path_glob" -> key.endsWith("/**")
                        ? new Attachment(rs.getString(1), label, "rule", key.substring(0, key.length() - 3), null, null)
                        : null;
                case "file" -> new Attachment(rs.getString(1), label, "rule", null, key, null);
                default -> attachment(rs.getString(1), label, "rule", "symbol", key, ruleFile.get(rs.getString(1)),
                        fileOfSymbol);
            };
            if (a != null) out.add(a);
        }, project);
        return out;
    }

    private static Attachment attachment(String id, String label, String scope, String kind, String ref, String path,
            Map<String, String> fileOfSymbol) {
        if (ref == null || ref.isBlank()) return null;
        return switch (kind == null ? "" : kind) {
            case "directory" -> new Attachment(id, label, scope, trimSlash(ref), null, null);
            case "file" -> new Attachment(id, label, scope, null, ref, null);
            case "symbol" -> {
                String file = path != null && !path.isBlank() && !path.equals(ref) ? path : fileOfSymbol.get(ref);
                if (file == null) {
                    int paren = ref.indexOf('(');
                    file = fileOfSymbol.get(paren < 0 ? ref : ref.substring(0, paren));
                }
                yield new Attachment(id, label, scope, null, file, ref);
            }
            default -> null;
        };
    }

    private static String trimSlash(String dir) {
        String d = dir.endsWith("/**") ? dir.substring(0, dir.length() - 3) : dir;
        return d.endsWith("/") ? d.substring(0, d.length() - 1) : d;
    }

    static String dirOf(String file) {
        int slash = file.lastIndexOf('/');
        return slash < 0 ? "" : file.substring(0, slash);
    }

    private static final class Snapshot {
        final String project;
        final Map<UUID, Symbol> symbols;
        final List<Attachment> attachments;
        final Map<String, List<String>> filesByDir = new java.util.TreeMap<>();
        final Map<String, List<Symbol>> symbolsByFile = new HashMap<>();
        final Map<String, List<Symbol>> membersByOwner = new HashMap<>();

        Snapshot(String project, java.util.Collection<String> files, Map<UUID, Symbol> symbols,
                List<Attachment> attachments) {
            this.project = project;
            this.symbols = symbols;
            this.attachments = attachments;
            for (String f : files) filesByDir.computeIfAbsent(dirOf(f), k -> new ArrayList<>()).add(f);
            for (Symbol sym : symbols.values()) {
                symbolsByFile.computeIfAbsent(sym.file(), k -> new ArrayList<>()).add(sym);
                if (!sym.isClass() && sym.owner() != null)
                    membersByOwner.computeIfAbsent(ownerKey(sym.file(), sym.owner()), k -> new ArrayList<>()).add(sym);
            }
        }

        Node node(String id) {
            if (id.equals("project:" + project)) {
                return new Node(id, "project", project, "", null, null, filesByDir.size(),
                        count(a -> a.scope().equals("memory"), a -> true),
                        count(a -> a.scope().equals("rule"), a -> true));
            }
            if (id.startsWith("pkg:")) {
                String dir = id.substring(4);
                if (!filesByDir.containsKey(dir)) throw new NoSuchElementException("package not found");
                return packageNode(dir);
            }
            if (id.startsWith("file:")) {
                String file = id.substring(5);
                if (!symbolsByFile.containsKey(file) && !filesByDir.getOrDefault(dirOf(file), List.of()).contains(file))
                    throw new NoSuchElementException("file not found");
                return fileNode(file);
            }
            Symbol sym = symbols.get(UUID.fromString(id));
            if (sym == null) throw new NoSuchElementException("symbol not found");
            return symbolNode(sym);
        }

        List<Node> childrenOf(String id) {
            if (id.startsWith("project:")) {
                List<Node> packages = filesByDir.keySet().stream().map(this::packageNode).toList();
                // the same Java package under src/main and src/test: tell them apart by their source root
                Map<String, Long> names = packages.stream().collect(java.util.stream.Collectors.groupingBy(
                        Node::name, java.util.stream.Collectors.counting()));
                return packages.stream().map(n -> names.get(n.name()) > 1 && !n.name().equals(n.path())
                                ? new Node(n.id(), n.kind(), n.name() + " · " + sourceRoot(n.path()), n.path(), n.fqn(),
                                        n.signature(), n.childCount(), n.memoryCount(), n.ruleCount())
                                : n)
                        .sorted(Comparator.comparing(Node::name)).toList();
            }
            if (id.startsWith("pkg:")) {
                List<Node> out = new ArrayList<>();
                for (String file : filesByDir.getOrDefault(id.substring(4), List.of())) {
                    List<Symbol> classes = symbolsByFile.getOrDefault(file, List.of()).stream()
                            .filter(Symbol::isClass).toList();
                    if (classes.isEmpty()) out.add(fileNode(file));
                    else classes.forEach(c -> out.add(symbolNode(c)));
                }
                out.sort(Comparator.comparing((Node n) -> n.kind().equals("file")).thenComparing(Node::name));
                return out;
            }
            if (id.startsWith("file:")) return List.of();
            Symbol sym = symbols.get(UUID.fromString(id));
            if (sym == null || !sym.isClass()) return List.of();
            return membersByOwner.getOrDefault(ownerKey(sym.file(), sym.fqn()), List.of()).stream()
                    .sorted(Comparator.comparingInt(Symbol::line)).map(this::symbolNode).toList();
        }

        List<Attachment> attachmentsOf(String id) {
            if (id.startsWith("project:")) return attachments;
            if (id.startsWith("pkg:")) {
                String dir = id.substring(4);
                return attachments.stream().filter(a -> inPackage(a, dir)).toList();
            }
            if (id.startsWith("file:")) {
                String file = id.substring(5);
                // file-level only: a memory on one overload is not about the whole file
                return attachments.stream().filter(a -> file.equals(a.file()) && a.symbol() == null).toList();
            }
            Symbol sym = symbols.get(UUID.fromString(id));
            if (sym == null) return List.of();
            return attachments.stream().filter(a -> onSymbol(a, sym)).toList();
        }

        /** Members belong to a class by file + owner fqn (the fqn alone repeats across source roots). */
        private Map<String, String> classIds;

        /** The graph node a symbol belongs to one level below its package: its class, or its file if it has none. */
        String classOf(Symbol sym) {
            if (classIds == null) {
                classIds = new HashMap<>();
                for (Symbol c : symbols.values()) {
                    if (c.isClass()) classIds.putIfAbsent(ownerKey(c.file(), c.fqn()), c.id().toString());
                }
            }
            if (sym.isClass()) return sym.id().toString();
            String owner = sym.owner() == null ? null : classIds.get(ownerKey(sym.file(), sym.owner()));
            return owner != null ? owner : "file:" + sym.file();
        }

        private static String ownerKey(String file, String ownerFqn) {
            return file + "\u0000" + ownerFqn;
        }

        private static boolean inPackage(Attachment a, String dir) {
            return dir.equals(a.dir()) || (a.file() != null && dir.equals(dirOf(a.file())));
        }

        /** A class carries file-level attachments of its file and those on itself or its members. */
        private boolean onSymbol(Attachment a, Symbol sym) {
            if (sym.isClass()) {
                if (a.symbol() == null) return sym.file().equals(a.file());
                String owner = a.symbol().contains("#") ? a.symbol().substring(0, a.symbol().indexOf('#')) : a.symbol();
                return sym.fqn().equals(owner) && (a.file() == null || a.file().equals(sym.file()));
            }
            if (a.symbol() == null) return false;
            if (a.file() != null && !a.file().equals(sym.file())) return false; // same fqn, other source root
            // `pkg.C#m` names every overload, `pkg.C#m(String)` exactly one
            return a.symbol().equals(sym.symbolKey()) || a.symbol().equals(sym.fqn());
        }

        private int count(java.util.function.Predicate<Attachment> kind, java.util.function.Predicate<Attachment> where) {
            return (int) attachments.stream().filter(kind).filter(where).map(Attachment::id).distinct().count();
        }

        private int memories(List<Attachment> list) {
            return (int) list.stream().filter(a -> a.scope().equals("memory")).map(Attachment::id).distinct().count();
        }

        private int rules(List<Attachment> list) {
            return (int) list.stream().filter(a -> a.scope().equals("rule")).map(Attachment::id).distinct().count();
        }

        Node packageNode(String dir) {
            List<Attachment> on = attachmentsOf("pkg:" + dir);
            String name = packageName(dir);
            return new Node("pkg:" + dir, "package", name, dir, null, null,
                    filesByDir.getOrDefault(dir, List.of()).size(), memories(on), rules(on));
        }

        Node fileNode(String file) {
            List<Attachment> on = attachmentsOf("file:" + file);
            return new Node("file:" + file, "file", file.substring(file.lastIndexOf('/') + 1), file, null, null, 0,
                    memories(on), rules(on));
        }

        Node symbolNode(Symbol sym) {
            List<Attachment> on = attachmentsOf(sym.id().toString());
            int children = sym.isClass() ? membersByOwner.getOrDefault(ownerKey(sym.file(), sym.fqn()), List.of()).size()
                    : 0;
            return new Node(sym.id().toString(), sym.isClass() ? "class" : "member",
                    sym.isClass() ? sym.name() : (sym.signature() == null || sym.signature().isBlank()
                            ? sym.name() : sym.signature()),
                    sym.file(), sym.fqn(), sym.signature(), children, memories(on), rules(on));
        }

        private static String sourceRoot(String dir) {
            String[] parts = dir.split("/");
            return parts.length >= 2 ? parts[0] + "/" + parts[1] : dir;
        }

        /** Java/Kotlin package of the directory's classes, else the directory itself. */
        private String packageName(String dir) {
            for (String file : filesByDir.getOrDefault(dir, List.of())) {
                for (Symbol sym : symbolsByFile.getOrDefault(file, List.of())) {
                    if (sym.isClass() && sym.fqn() != null && sym.fqn().endsWith("." + sym.name())) {
                        return sym.fqn().substring(0, sym.fqn().length() - sym.name().length() - 1);
                    }
                }
            }
            return dir.isEmpty() ? PanelText.t("(root)", "(kök)") : dir;
        }
    }
}
