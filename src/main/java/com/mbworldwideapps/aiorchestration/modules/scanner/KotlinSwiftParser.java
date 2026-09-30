package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural reader for Kotlin and Swift (Android and iOS sources). There is no compiler here: comments and string
 * literals are blanked, declarations are found with line-anchored patterns and their bodies by brace matching. It
 * yields what the scanner stores for Java — types, functions, annotations, typed dependencies and call sites
 * (receiver + name) — and the resolve pass later links the call sites to project symbols by name.
 */
final class KotlinSwiftParser {

    enum Language {
        KOTLIN("kotlin"), SWIFT("swift");

        final String id;

        Language(String id) {
            this.id = id;
        }

        static Optional<Language> of(String fileName) {
            String name = fileName.toLowerCase(Locale.ROOT);
            if (name.endsWith(".kt")) {
                return Optional.of(KOTLIN);
            }
            if (name.endsWith(".swift")) {
                return Optional.of(SWIFT);
            }
            return Optional.empty();
        }
    }

    /** A call site; receiver is null for a bare call and "" when called on an expression ({@code a().b()}). */
    record Call(String receiver, String name) {
    }

    /** A typed property or parameter; injected when it comes from a constructor/init or a DI marker. */
    record Dependency(String name, String type, boolean injected) {
    }

    record Declaration(String kind, String name, String fqn, String ownerFqn, String signature, String returnType,
            String role, int startLine, int endLine, List<String> annotations, List<String> supertypes,
            List<Dependency> dependencies, Map<String, String> localTypes, List<Call> calls, String receiverType,
            String httpCall) {

        boolean isType() {
            return TYPE_KINDS.contains(kind);
        }
    }

    record ParsedFile(Language language, String packageName, List<Declaration> declarations) {
    }

    static final Set<String> TYPE_KINDS = Set.of("class", "interface", "object", "enum", "struct", "extension");

    private static final String K_ANN = "(?:@[\\w.:]+(?:\\([^)]*\\))?\\s+)*";
    private static final String S_ATTR = "(?:@\\w+(?:\\([^)]*\\))?\\s+)*";
    private static final Pattern KOTLIN_PACKAGE = Pattern.compile("^[ \\t]*package\\s+([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern KOTLIN_TYPE = Pattern.compile("^[ \\t]*(" + K_ANN + ")"
            + "(?:(?:public|private|protected|internal|open|abstract|final|sealed|data|enum|annotation|inner|value"
            + "|inline|expect|actual|external)\\s+)*"
            + "(fun\\s+interface|interface|class|companion\\s+object|object)\\b(?:[ \\t]+([A-Za-z_]\\w*))?",
            Pattern.MULTILINE);
    private static final Pattern KOTLIN_FUN = Pattern.compile("^[ \\t]*(" + K_ANN + ")"
            + "(?:(?:public|private|protected|internal|open|abstract|final|override|suspend|inline|operator|infix"
            + "|tailrec|external|expect|actual)\\s+)*"
            + "fun\\s+(?:<[^>\\n]*>\\s*)?(?:([A-Za-z_][\\w.<>?, *]*?)\\.)?([A-Za-z_]\\w*|`[^`\\n]+`)\\s*\\(",
            Pattern.MULTILINE);
    private static final Pattern KOTLIN_PROPERTY = Pattern.compile("^[ \\t]*(" + K_ANN + ")"
            + "(?:(?:public|private|protected|internal|override|open|final|lateinit|const|abstract)\\s+)*"
            + "(?:val|var)\\s+([A-Za-z_]\\w*)([^\\n]*)$", Pattern.MULTILINE);
    private static final Pattern SWIFT_TYPE = Pattern.compile("^[ \\t]*(" + S_ATTR + ")"
            + "(?:(?:public|private|fileprivate|internal|open|final|indirect|package|nonisolated)\\s+)*"
            + "(class|struct|enum|protocol|extension|actor)\\s+([A-Za-z_][\\w.]*)", Pattern.MULTILINE);
    private static final Pattern SWIFT_FUNC = Pattern.compile("^[ \\t]*(" + S_ATTR + ")"
            + "(?:(?:public|private|fileprivate|internal|open|final|override|static|class|mutating|nonmutating"
            + "|dynamic|required|convenience|nonisolated|optional|package)\\s+)*"
            + "(?:func\\s+([^\\s(<]+)|(init)[?!]?|(deinit)\\b|(subscript))", Pattern.MULTILINE);
    private static final Pattern SWIFT_COMPUTED = Pattern.compile("^[ \\t]*(" + S_ATTR + ")"
            + "(?:(?:public|private|fileprivate|internal|open|final|override|static|class|nonisolated|package)"
            + "(?:\\(set\\))?\\s+)*var\\s+([A-Za-z_]\\w*)\\s*:\\s*([^={\\n]+?)\\s*\\{", Pattern.MULTILINE);
    private static final Pattern SWIFT_PROPERTY = Pattern.compile("^[ \\t]*(" + S_ATTR + ")"
            + "(?:(?:public|private|fileprivate|internal|open|final|override|static|class|lazy|weak|unowned"
            + "|nonisolated|package)(?:\\([^)]*\\))?\\s+)*(?:let|var)\\s+([A-Za-z_]\\w*)([^\\n]*)$",
            Pattern.MULTILINE);
    private static final Pattern ANNOTATION = Pattern.compile("@(?:[\\w]+:)?([\\w.]+)");
    private static final Pattern CALL = Pattern.compile("([A-Za-z_]\\w*)\\s*(?:<[\\w\\s,.?*<>]*>)?\\s*([({])");
    private static final Pattern CONSTRUCTED_TYPE = Pattern.compile(
            "^\\s*([A-Z]\\w*)(?:<[^>]*>)?\\s*(?:\\(|\\{|\\.(?:shared|default|instance|current)\\b)");
    private static final Pattern DELEGATE_TYPE = Pattern.compile("^\\s*\\w+\\s*<\\s*([A-Z]\\w*)");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    // `class Foo` on one line and `private constructor(...)` on a later one.
    private static final Pattern KOTLIN_CONSTRUCTOR_LINE = Pattern.compile("(?:@?[\\w.]+(?:\\([^)]*\\))?\\s+)*constructor\\s*\\(");
    private static final Pattern SWIFT_EFFECTS = Pattern.compile("(?:(?:async|throws|rethrows)\\s*)*");

    private static final Set<String> KOTLIN_KEYWORDS = Set.of("if", "else", "when", "for", "while", "do", "try",
            "catch", "finally", "return", "throw", "fun", "class", "object", "interface", "constructor", "init",
            "this", "super", "get", "set", "where", "in", "is", "as", "typealias", "val", "var", "package", "import");
    private static final Set<String> SWIFT_KEYWORDS = Set.of("if", "else", "guard", "switch", "case", "for", "while",
            "repeat", "do", "catch", "return", "throw", "throws", "rethrows", "func", "init", "deinit", "self",
            "Self", "super", "where", "in", "let", "var", "some", "any", "try", "await", "defer", "is", "as",
            "subscript", "default", "get", "set", "willSet", "didSet", "fallthrough");
    private static final Set<String> CAST_WORDS = Set.of("as", "is", "in");
    private static final Set<String> DECLARING_WORDS = Set.of("fun", "func", "class", "struct", "enum", "protocol",
            "interface", "object", "extension", "actor", "typealias", "case");
    private static final Set<String> KOTLIN_DI_DELEGATES = Set.of("inject", "viewModels", "activityViewModels",
            "viewModel", "navGraphViewModels", "hiltNavGraphViewModels", "koinInject");
    private static final Set<String> SWIFT_DI_WRAPPERS = Set.of("StateObject", "ObservedObject", "EnvironmentObject",
            "Inject", "Injected", "InjectedObject", "Dependency");
    // Retrofit: `@GET("users/{id}")`, `@HTTP(method = "DELETE", path = "x")`.
    private static final Pattern HTTP_ANNOTATION = Pattern.compile(
            "@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|HTTP)\\b\\s*(\\(([^)]*)\\))?");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");
    // Framework and value types that are never a project dependency worth an INJECTS edge.
    private static final Set<String> NON_DEPENDENCY_TYPES = Set.of("String", "Int", "Long", "Short", "Byte",
            "Double", "Float", "Boolean", "Bool", "Char", "Character", "Unit", "Void", "Any", "AnyObject", "Nothing",
            "List", "MutableList", "Map", "MutableMap", "Set", "MutableSet", "Array", "Dictionary", "Collection",
            "Sequence", "Date", "URL", "Data", "UUID", "Decimal", "CGFloat", "CGSize", "CGPoint", "CGRect",
            "TimeInterval", "Error", "Throwable", "Exception", "Context", "Bundle", "Intent", "Binding", "State",
            "Published", "Published.Publisher", "Optional");

    private KotlinSwiftParser() {
    }

    static ParsedFile parse(Language language, String source) {
        String code = blankCommentsAndStrings(source, language);
        Text text = new Text(source, code);
        String packageName = "";
        if (language == Language.KOTLIN) {
            Matcher matcher = KOTLIN_PACKAGE.matcher(code);
            if (matcher.find()) {
                packageName = matcher.group(1);
            }
        }
        List<Raw> raws = language == Language.KOTLIN ? kotlinDeclarations(text) : swiftDeclarations(text);
        raws.sort(Comparator.comparingInt((Raw raw) -> raw.start).thenComparingInt(raw -> -raw.end));
        List<Raw> kept = new ArrayList<>();
        for (Raw raw : raws) {
            Raw owner = innermost(kept, raw.start);
            if (owner == null ? text.depth(raw.start) != 0
                    : !owner.isType() || text.depth(raw.start) != text.depth(owner.bodyOpen) + 1) {
                continue; // local declaration, or one nested in an expression: its code belongs to the outer one
            }
            raw.owner = owner;
            if (owner != null) {
                owner.members.add(raw);
            }
            kept.add(raw);
        }
        Map<String, Raw> typesInFile = new LinkedHashMap<>();
        for (Raw raw : kept) {
            raw.fqn = fqn(raw, packageName);
            if (raw.isType() && !raw.kind.equals("extension")) {
                typesInFile.putIfAbsent(raw.fqn, raw);
            }
        }
        collectProperties(text, language, kept);
        // An extension of a type in the same file: its members and conformances go to that type.
        List<Raw> merged = new ArrayList<>();
        for (Raw raw : kept) {
            Raw extended = raw.kind.equals("extension") ? typesInFile.get(raw.fqn) : null;
            if (extended != null) {
                List<String> supertypes = new ArrayList<>(extended.supertypes);
                raw.supertypes.stream().filter(type -> !supertypes.contains(type)).forEach(supertypes::add);
                extended.supertypes = supertypes;
                merged.add(raw);
            }
        }
        List<Declaration> declarations = new ArrayList<>();
        for (Raw raw : kept) {
            if (!merged.contains(raw)) {
                declarations.add(toDeclaration(raw, text, language));
            }
        }
        return new ParsedFile(language, packageName, List.copyOf(declarations));
    }

    // ------------------------------------------------------------------ declarations

    private static List<Raw> kotlinDeclarations(Text text) {
        List<Raw> raws = new ArrayList<>();
        Matcher type = KOTLIN_TYPE.matcher(text.code);
        while (type.find()) {
            String keyword = type.group(2).replaceAll("\\s+", " ");
            String name = type.group(3);
            String kind;
            switch (keyword) {
                case "companion object" -> {
                    kind = "object";
                    name = name == null ? "Companion" : name;
                }
                case "fun interface", "interface" -> kind = "interface";
                case "object" -> kind = "object";
                default -> kind = type.group(0).matches("(?s).*\\benum\\s+class\\b.*") ? "enum" : "class";
            }
            if (name == null) {
                continue;
            }
            Raw raw = new Raw(kind, name, type.start(), annotations(type.group(1)));
            int nameEnd = type.end(3) < 0 ? type.end() : type.end(3);
            extent(text, raw, nameEnd, false);
            kotlinTypeHeader(text, raw, nameEnd);
            raws.add(raw);
        }
        Matcher fun = KOTLIN_FUN.matcher(text.code);
        while (fun.find()) {
            String name = fun.group(3).replace("`", "");
            Raw raw = new Raw("function", name, fun.start(), annotations(fun.group(1)));
            raw.receiverType = fun.group(2) == null ? "" : simpleType(fun.group(2));
            int paramsClose = matching(text.code, fun.end() - 1, '(', ')');
            raw.params = parameters(text.code.substring(fun.end(), Math.max(fun.end(), paramsClose)), Language.KOTLIN);
            raw.returnType = returnType(text.code, paramsClose + 1, Language.KOTLIN);
            extent(text, raw, paramsClose + 1, true);
            raws.add(raw);
        }
        return raws;
    }

    private static List<Raw> swiftDeclarations(Text text) {
        List<Raw> raws = new ArrayList<>();
        Matcher type = SWIFT_TYPE.matcher(text.code);
        while (type.find()) {
            String name = type.group(3);
            if (SWIFT_KEYWORDS.contains(name) || name.equals("func")) {
                continue; // `class func`, `class var`: a modifier, not a type
            }
            String kind = switch (type.group(2)) {
                case "protocol" -> "interface";
                case "actor" -> "class";
                default -> type.group(2);
            };
            Raw raw = new Raw(kind, name, type.start(), annotations(type.group(1)));
            extent(text, raw, type.end(), false);
            raw.supertypes = supertypes(text.code.substring(type.end(), raw.headerEnd()));
            raws.add(raw);
        }
        Matcher func = SWIFT_FUNC.matcher(text.code);
        while (func.find()) {
            String name = func.group(2) != null ? func.group(2)
                    : func.group(3) != null ? "init" : func.group(4) != null ? "deinit" : "subscript";
            Raw raw = new Raw("function", name, func.start(), annotations(func.group(1)));
            int cursor = skipSpaces(text.code, func.end());
            if (cursor < text.code.length() && text.code.charAt(cursor) == '<') {
                cursor = skipSpaces(text.code, matching(text.code, cursor, '<', '>') + 1);
            }
            int after = func.end();
            if (cursor < text.code.length() && text.code.charAt(cursor) == '(') {
                int close = matching(text.code, cursor, '(', ')');
                raw.params = parameters(text.code.substring(cursor + 1, Math.max(cursor + 1, close)), Language.SWIFT);
                raw.returnType = returnType(text.code, close + 1, Language.SWIFT);
                after = close + 1;
            } else if (!name.equals("deinit")) {
                continue;
            }
            extent(text, raw, after, false);
            raws.add(raw);
        }
        Matcher computed = SWIFT_COMPUTED.matcher(text.code);
        while (computed.find()) {
            Raw raw = new Raw("property", computed.group(2), computed.start(), annotations(computed.group(1)));
            raw.returnType = computed.group(3).trim();
            raw.bodyOpen = computed.end() - 1;
            raw.end = matching(text.code, raw.bodyOpen, '{', '}');
            raws.add(raw);
        }
        return raws;
    }

    /** Kotlin class header: primary constructor parameters, then the supertypes after ':'. */
    private static void kotlinTypeHeader(Text text, Raw raw, int nameEnd) {
        String header = text.code.substring(nameEnd, raw.headerEnd());
        int cursor = 0;
        if (cursor < header.length() && header.stripLeading().startsWith("<")) {
            int open = header.indexOf('<');
            cursor = matching(header, open, '<', '>') + 1;
        }
        int colon = topLevelIndexOf(header, ':', cursor);
        int paren = header.indexOf('(', cursor);
        if (paren >= 0 && (colon < 0 || paren < colon)) {
            int close = matching(header, paren, '(', ')');
            raw.params = parameters(header.substring(paren + 1, Math.max(paren + 1, close)), Language.KOTLIN);
            colon = topLevelIndexOf(header, ':', close + 1);
        }
        raw.supertypes = colon < 0 ? List.of() : supertypes(header.substring(colon));
    }

    /**
     * Finds where a declaration's body is. A block body is brace-matched; a Kotlin expression body ({@code = ...})
     * runs to the end of its statement; anything else ends with its header line.
     */
    private static void extent(Text text, Raw raw, int from, boolean kotlinFunction) {
        String code = text.code;
        int nesting = 0;
        for (int index = from; index < code.length(); index++) {
            char value = code.charAt(index);
            if (value == '(' || value == '[') {
                nesting++;
            } else if (value == ')' || value == ']') {
                nesting = Math.max(0, nesting - 1);
            } else if (nesting == 0) {
                if (value == '{') {
                    raw.bodyOpen = index;
                    raw.end = matching(code, index, '{', '}');
                    return;
                }
                if (kotlinFunction && value == '=' && !isOperatorEquals(code, index)) {
                    raw.expressionStart = index + 1;
                    raw.end = expressionEnd(code, index + 1);
                    return;
                }
                if (value == '}' || value == ';') {
                    raw.end = index - 1;
                    return;
                }
                if (value == '\n' && !headerContinues(code, index)) {
                    raw.end = index - 1;
                    return;
                }
            }
        }
        raw.end = code.length() - 1;
    }

    private static boolean isOperatorEquals(String code, int index) {
        char previous = index > 0 ? code.charAt(index - 1) : ' ';
        char next = index + 1 < code.length() ? code.charAt(index + 1) : ' ';
        return next == '=' || next == '>' || previous == '=' || previous == '!' || previous == '<' || previous == '>';
    }

    private static boolean headerContinues(String code, int newline) {
        String before = code.substring(code.lastIndexOf('\n', newline - 1) + 1, newline).stripTrailing();
        if (before.endsWith(",") || before.endsWith(":") || before.endsWith("->") || before.endsWith("&")) {
            return true;
        }
        String next = nextLine(code, newline);
        return next.startsWith(":") || next.startsWith(",") || next.startsWith("{") || next.startsWith("->")
                || KOTLIN_CONSTRUCTOR_LINE.matcher(next).lookingAt()
                || next.startsWith("where ") || next.startsWith("throws") || next.startsWith("async")
                || next.startsWith("=");
    }

    private static String nextLine(String code, int newline) {
        int start = newline + 1;
        while (start < code.length()) {
            int end = code.indexOf('\n', start);
            String line = code.substring(start, end < 0 ? code.length() : end).strip();
            if (!line.isEmpty()) {
                return line;
            }
            if (end < 0) {
                break;
            }
            start = end + 1;
        }
        return "";
    }

    private static int expressionEnd(String code, int from) {
        int nesting = 0;
        for (int index = from; index < code.length(); index++) {
            char value = code.charAt(index);
            if (value == '(' || value == '[' || value == '{') {
                nesting++;
            } else if (value == ')' || value == ']' || value == '}') {
                if (nesting == 0) {
                    return index - 1;
                }
                nesting--;
            } else if (value == '\n' && nesting == 0) {
                String before = code.substring(code.lastIndexOf('\n', index - 1) + 1, index).strip();
                String next = nextLine(code, index);
                boolean continues = before.isEmpty() || before.endsWith("=") || before.endsWith("->")
                        || before.endsWith(".") || before.endsWith("&&") || before.endsWith("||")
                        || before.endsWith("+") || before.endsWith(",") || next.startsWith(".")
                        || next.startsWith("?.") || next.startsWith("?:") || next.startsWith("&&")
                        || next.startsWith("||");
                if (!continues) {
                    return index - 1;
                }
            }
        }
        return code.length() - 1;
    }

    private static Raw innermost(List<Raw> candidates, int offset) {
        Raw found = null;
        for (Raw candidate : candidates) {
            if (candidate.bodyStart() >= 0 && candidate.bodyStart() < offset && offset <= candidate.end
                    && (found == null || candidate.bodyStart() > found.bodyStart())) {
                found = candidate;
            }
        }
        return found;
    }

    private static String fqn(Raw raw, String packageName) {
        Raw owner = raw.owner;
        if (raw.isType()) {
            // `extension Foo` (Swift, no packages): its members belong to Foo wherever Foo is declared.
            String path = owner == null || raw.kind.equals("extension") ? raw.name : typePath(owner) + "." + raw.name;
            return packageName.isBlank() ? path : packageName + "." + path;
        }
        String ownerFqn = owner == null ? packageName : owner.fqn;
        return ownerFqn.isBlank() ? raw.name : ownerFqn + "#" + raw.name;
    }

    private static String typePath(Raw type) {
        return type.owner == null ? type.name : typePath(type.owner) + "." + type.name;
    }

    // ------------------------------------------------------------------ properties and dependencies

    private static void collectProperties(Text text, Language language, List<Raw> kept) {
        Matcher matcher = (language == Language.KOTLIN ? KOTLIN_PROPERTY : SWIFT_PROPERTY).matcher(text.code);
        while (matcher.find()) {
            Raw owner = innermost(kept, matcher.start());
            if (owner == null) {
                continue;
            }
            String name = matcher.group(2);
            String rest = matcher.group(3);
            List<String> annotations = annotations(matcher.group(1));
            // The declared type or, failing that, the type the initializer constructs.
            String declared = "";
            String initializer = "";
            String delegate = "";
            String trimmed = rest.strip();
            if (trimmed.startsWith(":")) {
                String afterColon = trimmed.substring(1);
                int by = afterColon.indexOf(" by ");
                int equals = topLevelIndexOf(afterColon, '=', 0);
                int brace = afterColon.indexOf('{');
                int stop = minPositive(afterColon.length(), by, equals, brace);
                declared = simpleType(afterColon.substring(0, stop));
                if (by >= 0 && by == stop) {
                    delegate = afterColon.substring(by + 4);
                } else if (equals >= 0 && equals == stop) {
                    initializer = afterColon.substring(equals + 1);
                }
            } else if (trimmed.startsWith("by ")) {
                delegate = trimmed.substring(3);
            } else if (trimmed.startsWith("=")) {
                initializer = trimmed.substring(1);
            }
            String type = declared;
            if (type.isBlank()) {
                Matcher constructed = CONSTRUCTED_TYPE.matcher(initializer);
                if (constructed.find()) {
                    type = constructed.group(1);
                } else if (!delegate.isBlank()) {
                    Matcher generic = DELEGATE_TYPE.matcher(delegate);
                    type = generic.find() ? generic.group(1) : "";
                }
            }
            if (type.isBlank()) {
                continue;
            }
            if (owner.isType()) {
                if (text.depth(matcher.start()) != text.depth(owner.bodyOpen) + 1) {
                    continue; // inside an initializer block or a lambda, not a member
                }
                boolean injected = language == Language.KOTLIN
                        ? annotations.contains("Inject") || annotations.contains("Autowired")
                                || KOTLIN_DI_DELEGATES.contains(firstWord(delegate))
                        : annotations.stream().anyMatch(SWIFT_DI_WRAPPERS::contains);
                owner.properties.add(new Dependency(name, type, injected));
            } else {
                owner.locals.putIfAbsent(name, type);
            }
        }
    }

    private static Declaration toDeclaration(Raw raw, Text text, Language language) {
        int startLine = text.line(raw.start);
        int endLine = Math.max(startLine, text.line(Math.max(raw.start, raw.end)));
        List<Dependency> dependencies = new ArrayList<>();
        Map<String, String> localTypes = new LinkedHashMap<>();
        List<Call> calls = List.of();
        String signature = "";
        String kind = raw.kind;
        String ownerFqn = raw.owner == null ? "" : raw.owner.fqn;
        if (raw.isType()) {
            // Constructor injection: Kotlin primary-constructor parameters, Swift init parameters.
            for (Dependency parameter : raw.params) {
                dependencies.add(new Dependency(parameter.name(), parameter.type(), true));
            }
            for (Raw member : raw.members) {
                if (language == Language.SWIFT && member.name.equals("init")) {
                    member.params.forEach(parameter -> dependencies.add(
                            new Dependency(parameter.name(), parameter.type(), true)));
                }
            }
            dependencies.addAll(raw.properties);
        } else {
            for (Dependency parameter : raw.params) {
                localTypes.putIfAbsent(parameter.name(), parameter.type());
            }
            localTypes.putAll(raw.locals);
            calls = calls(text.code, raw, language);
            if (!kind.equals("property")) {
                kind = raw.owner == null ? "function" : "method";
                signature = raw.name + "(" + String.join(",", raw.params.stream().map(Dependency::type).toList())
                        + ")";
            } else {
                signature = raw.name;
            }
        }
        String httpCall = httpCall(raw, text);
        String role = role(raw, kind, httpCall);
        return new Declaration(kind, raw.name, raw.fqn, ownerFqn, signature, raw.returnType, role, startLine,
                endLine, List.copyOf(raw.annotations), List.copyOf(raw.supertypes), distinct(dependencies),
                Map.copyOf(localTypes), calls, raw.receiverType, httpCall);
    }

    private static List<Dependency> distinct(List<Dependency> dependencies) {
        Map<String, Dependency> byName = new LinkedHashMap<>();
        for (Dependency dependency : dependencies) {
            byName.merge(dependency.name(), dependency, (first, second) -> first.injected() ? first : second);
        }
        return List.copyOf(byName.values());
    }

    /** Whether a dependency type can be a project type: a named, non-framework type. */
    static boolean projectTypeCandidate(String type) {
        return type != null && !type.isBlank() && Character.isUpperCase(type.charAt(0))
                && !NON_DEPENDENCY_TYPES.contains(type);
    }

    // ------------------------------------------------------------------ calls

    private static List<Call> calls(String code, Raw raw, Language language) {
        int from = raw.bodyOpen >= 0 ? raw.bodyOpen + 1 : raw.expressionStart;
        if (from < 0 || from > raw.end) {
            return List.of();
        }
        Set<String> keywords = language == Language.KOTLIN ? KOTLIN_KEYWORDS : SWIFT_KEYWORDS;
        LinkedHashSet<Call> calls = new LinkedHashSet<>();
        Matcher matcher = CALL.matcher(code);
        matcher.region(from, Math.min(code.length(), raw.end + 1));
        while (matcher.find()) {
            String name = matcher.group(1);
            if (keywords.contains(name)) {
                continue;
            }
            int before = previousNonSpace(code, matcher.start() - 1);
            char previous = before < 0 ? ' ' : code.charAt(before);
            if (previous == '#' || previous == '@' || previous == '`') {
                continue;
            }
            if (Character.isLetterOrDigit(previous) || previous == '_') {
                if (DECLARING_WORDS.contains(wordEndingAt(code, before))) {
                    continue;
                }
            }
            String receiver = null;
            if (previous == '.') {
                int receiverEnd = previousNonSpace(code, before - 1);
                while (receiverEnd >= 0 && (code.charAt(receiverEnd) == '?' || code.charAt(receiverEnd) == '!')) {
                    receiverEnd--;
                }
                char last = receiverEnd < 0 ? ' ' : code.charAt(receiverEnd);
                if (Character.isLetterOrDigit(last) || last == '_') {
                    receiver = wordEndingAt(code, receiverEnd);
                    if (receiver.equals("case")) {
                        continue; // Swift `case .loaded(let x)`: a pattern, not a call
                    }
                    if (Character.isDigit(receiver.charAt(0))) {
                        receiver = "";
                    }
                } else {
                    receiver = "";
                }
            }
            boolean trailingClosure = matcher.group(2).equals("{");
            if (trailingClosure && language == Language.SWIFT && receiver == null
                    && !Character.isUpperCase(name.charAt(0))) {
                continue; // `if ready {`, `switch state {`: a condition, not a call
            }
            if (trailingClosure && receiver == null && (previous == '?' || previous == '!' || previous == ':'
                    || previous == '>' || CAST_WORDS.contains(wordEndingAt(code, before)))) {
                continue; // `x as? Foo {`, `-> Foo {`, `: Foo {`: a type, not a call
            }
            calls.add(new Call(receiver, name));
            if (calls.size() >= 80) {
                break;
            }
        }
        return List.copyOf(calls);
    }

    private static String wordEndingAt(String code, int end) {
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(code.charAt(start - 1)) || code.charAt(start - 1) == '_')) {
            start--;
        }
        return code.substring(start, end + 1);
    }

    private static int previousNonSpace(String code, int index) {
        int cursor = index;
        while (cursor >= 0 && Character.isWhitespace(code.charAt(cursor))) {
            cursor--;
        }
        return cursor;
    }

    // ------------------------------------------------------------------ roles and HTTP

    private static String role(Raw raw, String kind, String httpCall) {
        if (!httpCall.isBlank()) {
            return "client";
        }
        String name = raw.name;
        List<String> supertypes = raw.supertypes;
        if (raw.kind.equals("extension")) {
            return "extension"; // the extended type carries the role; an extension is no flow entry
        }
        if (raw.isType()) {
            if (name.endsWith("ViewModel") || supertypes.contains("ViewModel")
                    || supertypes.contains("AndroidViewModel") || supertypes.contains("ObservableObject")
                    || raw.annotations.contains("HiltViewModel")) {
                return "viewmodel";
            }
            if (name.endsWith("Controller") || supertypes.stream().anyMatch(type -> type.endsWith("ViewController"))) {
                return "controller";
            }
            if (name.endsWith("Activity") || name.endsWith("Fragment") || name.endsWith("Screen")
                    || supertypes.stream().anyMatch(type -> type.endsWith("Activity") || type.endsWith("Fragment"))) {
                return "screen";
            }
            if (supertypes.contains("View")) {
                return "view"; // SwiftUI: screens and small components alike, so not a flow entry by itself
            }
            if (name.endsWith("Service")) {
                return "service";
            }
            if (name.endsWith("Repository")) {
                return "repository";
            }
            return raw.kind.equals("interface") ? "interface" : "component";
        }
        if (raw.annotations.contains("Composable") && name.endsWith("Screen")) {
            return "screen";
        }
        return kind;
    }

    private static String httpCall(Raw raw, Text text) {
        if (raw.isType()) {
            return "";
        }
        Matcher matcher = HTTP_ANNOTATION.matcher(text.source.substring(raw.start,
                Math.min(text.source.length(), raw.start + Math.max(0, headerLength(raw, text)))));
        if (!matcher.find()) {
            return "";
        }
        String method = matcher.group(1);
        String arguments = matcher.group(3) == null ? "" : matcher.group(3);
        Matcher literal = STRING_LITERAL.matcher(arguments);
        List<String> strings = new ArrayList<>();
        while (literal.find()) {
            strings.add(literal.group(1));
        }
        String path = "";
        if (method.equals("HTTP") && strings.size() >= 2) {
            method = strings.get(0);
            path = strings.get(1);
        } else if (!strings.isEmpty()) {
            path = strings.get(0);
        }
        return method + " " + (path.startsWith("/") || path.startsWith("http") ? path : "/" + path);
    }

    private static int headerLength(Raw raw, Text text) {
        int stop = raw.bodyOpen >= 0 ? raw.bodyOpen : raw.expressionStart >= 0 ? raw.expressionStart : raw.end + 1;
        return Math.min(text.source.length(), stop) - raw.start;
    }

    // ------------------------------------------------------------------ small parsing helpers

    private static List<Dependency> parameters(String list, Language language) {
        List<Dependency> parameters = new ArrayList<>();
        for (String parameter : splitTopLevel(list, ',')) {
            String value = parameter.replaceAll("@\\w+(?:\\([^)]*\\))?", " ").strip();
            int colon = topLevelIndexOf(value, ':', 0);
            if (colon < 0) {
                continue;
            }
            String[] names = value.substring(0, colon).strip().split("\\s+");
            String name = names[names.length - 1];
            String type = value.substring(colon + 1);
            int defaultValue = topLevelIndexOf(type, '=', 0);
            if (defaultValue >= 0) {
                type = type.substring(0, defaultValue);
            }
            type = type.strip();
            if (language == Language.SWIFT) {
                type = type.replaceAll("^(?:inout|__owned|borrowing|consuming)\\s+", "");
            }
            parameters.add(new Dependency(name, normalizeType(type), false));
        }
        return parameters;
    }

    private static String returnType(String code, int from, Language language) {
        int cursor = skipSpaces(code, from);
        String marker = language == Language.KOTLIN ? ":" : "->";
        if (language == Language.SWIFT) {
            Matcher effects = SWIFT_EFFECTS.matcher(code).region(cursor, code.length());
            if (effects.lookingAt()) {
                cursor = effects.end(); // `async throws -> T`
            }
        }
        if (!code.startsWith(marker, cursor)) {
            return "";
        }
        int start = cursor + marker.length();
        int index = start;
        int nesting = 0;
        while (index < code.length()) {
            char value = code.charAt(index);
            if (value == '(' || value == '<' || value == '[') {
                nesting++;
            } else if (value == ')' || value == '>' || value == ']') {
                if (value == '>' && index > 0 && code.charAt(index - 1) == '-') {
                    index++;
                    continue;
                }
                nesting--;
            } else if (nesting <= 0 && (value == '{' || value == '=' || value == '\n' || value == ';')) {
                break;
            }
            index++;
        }
        String type = code.substring(start, index).replaceAll("\\bwhere\\b.*", "").strip();
        return normalizeType(type);
    }

    private static List<String> supertypes(String header) {
        String value = header.strip();
        if (value.startsWith("<")) {
            value = value.substring(matching(value, 0, '<', '>') + 1).strip(); // `struct Box<T>: View`
        }
        if (!value.startsWith(":")) {
            return List.of();
        }
        value = value.substring(1).replaceAll("\\bwhere\\b[\\s\\S]*", "");
        List<String> types = new ArrayList<>();
        for (String part : splitTopLevel(value, ',')) {
            String type = simpleType(part.replaceAll("\\(.*", "").replaceAll("\\bby\\b.*", ""));
            if (!type.isBlank()) {
                types.add(type);
            }
        }
        return List.copyOf(types);
    }

    /** {@code com.x.Repo<User>?} → {@code Repo}; function, tuple and collection literal types → "". */
    static String simpleType(String raw) {
        String value = raw == null ? "" : raw.strip();
        value = value.replaceAll("^(?:@\\w+\\s+)*", "").replaceAll("^(?:some|any|inout)\\s+", "").strip();
        if (value.isEmpty() || value.contains("->") || value.startsWith("(") || value.startsWith("[")) {
            return "";
        }
        int generic = value.indexOf('<');
        if (generic >= 0) {
            value = value.substring(0, generic);
        }
        value = value.replaceAll("[?!\\s]+$", "").strip();
        int dot = value.lastIndexOf('.');
        if (dot >= 0) {
            value = value.substring(dot + 1);
        }
        return IDENTIFIER.matcher(value).matches() ? value : "";
    }

    private static String normalizeType(String type) {
        return type.replaceAll("\\s+", " ").replaceAll("\\s*([<>,?:])\\s*", "$1").strip();
    }

    private static List<String> splitTopLevel(String value, char separator) {
        List<String> parts = new ArrayList<>();
        int nesting = 0;
        int start = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '(' || current == '<' || current == '[' || current == '{') {
                nesting++;
            } else if (current == ')' || current == ']' || current == '}'
                    || current == '>' && (index == 0 || value.charAt(index - 1) != '-')) {
                nesting--;
            } else if (current == separator && nesting == 0) {
                parts.add(value.substring(start, index));
                start = index + 1;
            }
        }
        if (start < value.length()) {
            parts.add(value.substring(start));
        }
        return parts.stream().filter(part -> !part.isBlank()).toList();
    }

    private static int topLevelIndexOf(String value, char target, int from) {
        int nesting = 0;
        for (int index = Math.max(0, from); index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '(' || current == '<' || current == '[' || current == '{') {
                nesting++;
            } else if (current == ')' || current == ']' || current == '}'
                    || current == '>' && (index == 0 || value.charAt(index - 1) != '-')) {
                nesting--;
            } else if (current == target && nesting == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int minPositive(int fallback, int... values) {
        int min = fallback;
        for (int value : values) {
            if (value >= 0 && value < min) {
                min = value;
            }
        }
        return min;
    }

    private static int matching(String code, int open, char openChar, char closeChar) {
        int nesting = 0;
        for (int index = open; index < code.length(); index++) {
            char value = code.charAt(index);
            if (value == openChar) {
                nesting++;
            } else if (value == closeChar && !(closeChar == '>' && index > 0 && code.charAt(index - 1) == '-')) {
                nesting--;
                if (nesting == 0) {
                    return index;
                }
            }
        }
        return code.length() - 1;
    }

    private static int skipSpaces(String code, int from) {
        int index = from;
        while (index < code.length() && Character.isWhitespace(code.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String firstWord(String value) {
        Matcher matcher = IDENTIFIER.matcher(value == null ? "" : value.strip());
        return matcher.lookingAt() ? matcher.group() : "";
    }

    private static List<String> annotations(String block) {
        if (block == null || block.isBlank()) {
            return new ArrayList<>();
        }
        List<String> names = new ArrayList<>();
        Matcher matcher = ANNOTATION.matcher(block);
        while (matcher.find()) {
            String name = matcher.group(1);
            int dot = name.lastIndexOf('.');
            names.add(dot < 0 ? name : name.substring(dot + 1));
        }
        return names.stream().distinct().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    // ------------------------------------------------------------------ lexical blanking

    /**
     * Returns the source with comments and string/char literals replaced by spaces (newlines kept), so offsets and
     * line numbers still match the original while braces and keywords inside literals no longer count.
     */
    static String blankCommentsAndStrings(String source, Language language) {
        char[] out = source.toCharArray();
        int length = source.length();
        int index = 0;
        while (index < length) {
            char value = source.charAt(index);
            char next = index + 1 < length ? source.charAt(index + 1) : '\0';
            int end;
            boolean literal = true;
            if (value == '/' && next == '/') {
                end = source.indexOf('\n', index);
                end = end < 0 ? length : end;
                literal = false;
            } else if (value == '/' && next == '*') {
                end = blockCommentEnd(source, index);
                literal = false;
            } else if (value == '"') {
                end = stringEnd(source, index, language, 0);
            } else if (value == '#' && language == Language.SWIFT && rawStringHashes(source, index) > 0) {
                int hashes = rawStringHashes(source, index);
                end = stringEnd(source, index + hashes, language, hashes);
            } else if (value == '\'' && language == Language.KOTLIN) {
                end = charLiteralEnd(source, index);
            } else {
                index++;
                continue;
            }
            // A literal keeps its first and last delimiter so `x + "a"` still ends in an operand, not `+`.
            int from = literal ? index + 1 : index;
            int to = literal && end - index >= 2 ? end - 1 : end;
            for (int blank = from; blank < to && blank < length; blank++) {
                if (out[blank] != '\n') {
                    out[blank] = ' ';
                }
            }
            index = Math.max(end, index + 1);
        }
        return new String(out);
    }

    private static int blockCommentEnd(String source, int start) {
        int nesting = 0;
        int index = start;
        while (index < source.length() - 1) {
            if (source.startsWith("/*", index)) {
                nesting++;
                index += 2;
            } else if (source.startsWith("*/", index)) {
                nesting--;
                index += 2;
                if (nesting == 0) {
                    return index;
                }
            } else {
                index++;
            }
        }
        return source.length();
    }

    private static int rawStringHashes(String source, int index) {
        int hashes = 0;
        while (index + hashes < source.length() && source.charAt(index + hashes) == '#') {
            hashes++;
        }
        return index + hashes < source.length() && source.charAt(index + hashes) == '"' ? hashes : 0;
    }

    /** Index just past the closing delimiter of the string opening at {@code start} (a quote). */
    private static int stringEnd(String source, int start, Language language, int hashes) {
        boolean triple = source.startsWith("\"\"\"", start);
        String closing = (triple ? "\"\"\"" : "\"") + "#".repeat(hashes);
        boolean escapes = !(triple && language == Language.KOTLIN);
        String escape = "\\" + "#".repeat(hashes);
        int index = start + (triple ? 3 : 1);
        while (index < source.length()) {
            char value = source.charAt(index);
            if (!triple && value == '\n') {
                return index;
            }
            if (escapes && source.startsWith(escape, index)) {
                int after = index + escape.length();
                if (language == Language.SWIFT && after < source.length() && source.charAt(after) == '(') {
                    index = interpolationEnd(source, after + 1, '(', ')', language);
                } else {
                    index = after + 1;
                }
                continue;
            }
            if (language == Language.KOTLIN && value == '$' && index + 1 < source.length()
                    && source.charAt(index + 1) == '{') {
                index = interpolationEnd(source, index + 2, '{', '}', language);
                continue;
            }
            if (source.startsWith(closing, index)) {
                int end = index + closing.length();
                while (triple && language == Language.KOTLIN && end < source.length() && source.charAt(end) == '"') {
                    end++; // Kotlin allows quotes right before the closing """
                }
                return end;
            }
            index++;
        }
        return source.length();
    }

    private static int interpolationEnd(String source, int from, char open, char close, Language language) {
        int nesting = 1;
        int index = from;
        while (index < source.length() && nesting > 0) {
            char value = source.charAt(index);
            if (value == '"') {
                index = stringEnd(source, index, language, 0);
                continue;
            }
            if (value == open) {
                nesting++;
            } else if (value == close) {
                nesting--;
            }
            index++;
        }
        return index;
    }

    private static int charLiteralEnd(String source, int start) {
        int index = start + 1;
        while (index < source.length() && index <= start + 12) {
            char value = source.charAt(index);
            if (value == '\\') {
                index += 2;
                continue;
            }
            if (value == '\'') {
                return index + 1;
            }
            if (value == '\n') {
                break;
            }
            index++;
        }
        return start + 1; // not a char literal; leave it alone
    }

    // ------------------------------------------------------------------ state

    /** Source text, its blanked twin, brace depth per offset and line starts. */
    private static final class Text {
        final String source;
        final String code;
        private final int[] depths;
        private final int[] lineStarts;

        Text(String source, String code) {
            this.source = source;
            this.code = code;
            this.depths = new int[code.length() + 1];
            int depth = 0;
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int index = 0; index < code.length(); index++) {
                depths[index] = depth;
                char value = code.charAt(index);
                if (value == '{') {
                    depth++;
                } else if (value == '}') {
                    depth = Math.max(0, depth - 1);
                } else if (value == '\n') {
                    starts.add(index + 1);
                }
            }
            depths[code.length()] = depth;
            this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        int depth(int offset) {
            return depths[Math.max(0, Math.min(offset, depths.length - 1))];
        }

        int line(int offset) {
            int low = 0;
            int high = lineStarts.length - 1;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (lineStarts[mid] <= offset) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            return low + 1;
        }
    }

    private static final class Raw {
        final String kind;
        final String name;
        final int start;
        final List<String> annotations;
        int bodyOpen = -1;
        int expressionStart = -1;
        int end;
        List<Dependency> params = List.of();
        List<String> supertypes = List.of();
        String returnType = "";
        String receiverType = "";
        String fqn = "";
        Raw owner;
        final List<Dependency> properties = new ArrayList<>();
        final Map<String, String> locals = new LinkedHashMap<>();
        final List<Raw> members = new ArrayList<>();

        Raw(String kind, String name, int start, List<String> annotations) {
            this.kind = kind;
            this.name = name;
            this.start = start;
            this.annotations = annotations;
        }

        boolean isType() {
            return TYPE_KINDS.contains(kind);
        }

        int bodyStart() {
            return bodyOpen >= 0 ? bodyOpen : expressionStart;
        }

        int headerEnd() {
            return bodyOpen >= 0 ? bodyOpen : Math.max(start, end + 1);
        }
    }
}
