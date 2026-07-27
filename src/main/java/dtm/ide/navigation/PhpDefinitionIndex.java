package dtm.ide.navigation;

import dtm.di.annotations.Async;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class PhpDefinitionIndex {
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)\\b(class|interface|trait|enum|function)\\s+&?\\s*([A-Za-z_][\\w]*)");
    private static final Pattern CONSTANT = Pattern.compile(
            "(?m)\\bconst\\s+(?:(?:[?\\\\|&A-Za-z_][\\\\|&\\w?]*)\\s+)?([A-Za-z_][\\w]*)\\s*=");
    private static final Pattern NAMESPACE = Pattern.compile(
            "(?m)^\\s*namespace\\s+([^;{]+)\\s*[;{]");
    private static final Pattern USE = Pattern.compile(
            "(?m)^\\s*use\\s+([^;]+);");
    private static final Pattern TYPE_HEADER = Pattern.compile(
            "(?m)\\b(class|interface|trait|enum)\\s+([A-Za-z_][\\w]*)\\s*([^\\{;]*)\\{");
    private static final Pattern METHOD = Pattern.compile(
            "\\bfunction\\s+&?\\s*([A-Za-z_][\\w]*)\\s*\\(");
    private static final Pattern IMPLEMENTS = Pattern.compile(
            "(?i)\\bimplements\\s+(.+)$");
    private static final Pattern EXTENDS = Pattern.compile(
            "(?i)\\bextends\\s+(.+?)(?=\\bimplements\\b|$)");
    private static final Pattern IMPORT_OR_NAMESPACE = Pattern.compile(
            "(?m)^[\\t ]*(?:namespace|use)[\\t ]+[^;]+;");
    private static final Set<String> IGNORED = Set.of(
            ".git", ".idea", ".orion", "node_modules", "runtime", "log", "cache", "tmp"
    );
    private static final Set<String> PHP_BUILTIN_TYPES = Set.of(
            "array", "bool", "callable", "false", "float", "int", "iterable",
            "mixed", "never", "null", "object", "string", "true", "void"
    );
    private volatile Snapshot snapshot = Snapshot.empty(null);

    @Async
    public CompletableFuture<Void> rebuild(Path projectRoot) {
        snapshot = build(projectRoot);
        return CompletableFuture.completedFuture(null);
    }

    public List<Location> definitions(String text, int offset) {
        return rankedDefinitions(text, offset).stream()
                .map(Definition::location)
                .distinct()
                .toList();
    }

    public List<Location> preferredDefinitions(String text, int offset) {
        String symbol = wordAt(text, offset);
        if (symbol.isBlank()) return List.of();
        List<Definition> ranked = rankedDefinitions(text, offset);
        if (ranked.size() == 1) return List.of(ranked.getFirst().location());

        Hint hint = hint(text, offset, symbol);
        String qualifiedHint = qualifiedHint(text, symbol, hint);
        if (hint == Hint.TYPE && qualifiedHint != null) {
            List<Location> exact = ranked.stream()
                    .filter(value -> qualifiedHint.equalsIgnoreCase(value.qualifiedName()))
                    .map(Definition::location)
                    .distinct()
                    .toList();
            if (exact.size() == 1) return exact;
        }
        return List.of();
    }

    public List<Location> implementations(String text, int offset, Path sourceFile) {
        String symbol = wordAt(text, offset);
        if (symbol.isBlank()) return List.of();
        Snapshot current = snapshot;
        String targetInterface = interfaceAt(text, offset);

        if (targetInterface == null) {
            String qualified = qualifiedHint(text, symbol, Hint.TYPE);
            List<TypeDefinition> interfaces = current.types().stream()
                    .filter(type -> type.kind() == TypeKind.INTERFACE)
                    .filter(type -> type.name().equalsIgnoreCase(symbol))
                    .filter(type -> qualified == null
                            || qualified.equalsIgnoreCase(type.qualifiedName()))
                    .toList();
            if (interfaces.size() == 1) targetInterface = interfaces.getFirst().qualifiedName();
        }
        if (targetInterface == null) {
            List<TypeDefinition> methodContracts = current.types().stream()
                    .filter(type -> type.kind() == TypeKind.INTERFACE)
                    .filter(type -> type.methods().containsKey(symbol.toLowerCase(Locale.ROOT)))
                    .toList();
            if (methodContracts.size() == 1) {
                targetInterface = methodContracts.getFirst().qualifiedName();
            }
        }
        if (targetInterface == null) return List.of();

        String method = isInterfaceName(text, offset, targetInterface)
                ? null : symbol.toLowerCase(Locale.ROOT);
        Map<String, TypeDefinition> byName = new HashMap<>();
        current.types().forEach(type ->
                byName.put(type.qualifiedName().toLowerCase(Locale.ROOT), type));

        List<Location> result = new ArrayList<>();
        for (TypeDefinition type : current.types()) {
            if (type.kind() != TypeKind.CLASS
                    || !implementsInterface(type, targetInterface, byName, new LinkedHashSet<>())) {
                continue;
            }
            Location target = method == null ? type.location() : type.methods().get(method);
            if (target != null) result.add(target);
        }
        return result.stream().distinct().toList();
    }

    public List<Location> usages(String text, int offset) {
        String symbol = wordAt(text, offset);
        if (symbol.isBlank()) return List.of();
        Snapshot current = snapshot;
        List<Location> declarations = current.symbols()
                .getOrDefault(symbol.toLowerCase(Locale.ROOT), List.of())
                .stream().map(Definition::location).toList();
        return current.occurrences()
                .getOrDefault(symbol.toLowerCase(Locale.ROOT), List.of())
                .stream()
                .filter(location -> declarations.stream()
                        .noneMatch(declaration -> samePosition(location, declaration)))
                .distinct()
                .toList();
    }

    public List<ImplementationAnchor> implementationAnchors(String text, Path sourceFile) {
        if (text == null || text.isBlank()) return List.of();
        List<ImplementationAnchor> anchors = new ArrayList<>();
        Matcher types = TYPE_HEADER.matcher(text);
        while (types.find()) {
            if (!"interface".equalsIgnoreCase(types.group(1))) continue;
            int openBrace = types.end() - 1;
            int closeBrace = matchingBrace(text, openBrace);
            if (closeBrace < 0) closeBrace = text.length();
            int typeOffset = types.start(2);
            anchors.add(new ImplementationAnchor(lineAt(text, typeOffset), typeOffset,
                    types.group(2), false));

            Matcher methods = METHOD.matcher(text);
            methods.region(openBrace + 1, closeBrace);
            while (methods.find()) {
                int methodOffset = methods.start(1);
                anchors.add(new ImplementationAnchor(lineAt(text, methodOffset), methodOffset,
                        methods.group(1), true));
            }
        }
        return List.copyOf(anchors);
    }

    public List<ImplementationAnchor> usageAnchors(String text, Path sourceFile) {
        if (text == null || text.isBlank()) return List.of();
        List<ImplementationAnchor> anchors = new ArrayList<>();
        Matcher types = TYPE_HEADER.matcher(text);
        while (types.find()) {
            int openBrace = types.end() - 1;
            int closeBrace = matchingBrace(text, openBrace);
            if (closeBrace < 0) closeBrace = text.length();
            int typeOffset = types.start(2);
            anchors.add(new ImplementationAnchor(lineAt(text, typeOffset), typeOffset,
                    types.group(2), false));

            Matcher methods = METHOD.matcher(text);
            methods.region(openBrace + 1, closeBrace);
            while (methods.find()) {
                int methodOffset = methods.start(1);
                anchors.add(new ImplementationAnchor(lineAt(text, methodOffset), methodOffset,
                        methods.group(1), true));
            }
        }
        return List.copyOf(anchors);
    }

    public List<MissingInterfaceImplementation> missingInterfaceImplementations(
            String text, Path sourceFile) {
        if (text == null || text.isBlank() || sourceFile == null) return List.of();
        Path normalizedFile = sourceFile.toAbsolutePath().normalize();
        List<TypeDefinition> currentTypes = new ArrayList<>();
        indexTypes(normalizedFile, text, namespaceOf(text), currentTypes);

        Map<String, TypeDefinition> byName = new HashMap<>();
        snapshot.types().stream()
                .filter(type -> !type.file().equals(normalizedFile))
                .forEach(type -> byName.put(
                        type.qualifiedName().toLowerCase(Locale.ROOT), type));
        currentTypes.forEach(type -> byName.put(
                type.qualifiedName().toLowerCase(Locale.ROOT), type));

        List<MissingInterfaceImplementation> output = new ArrayList<>();
        for (TypeDefinition type : currentTypes) {
            if (type.kind() != TypeKind.CLASS || type.abstractType()) continue;

            Map<String, MethodDeclaration> required = new java.util.LinkedHashMap<>();
            collectInterfaceMethods(type, byName, required, new LinkedHashSet<>());
            if (required.isEmpty()) continue;

            Set<String> implemented = new LinkedHashSet<>();
            collectClassMethods(type, byName, implemented, new LinkedHashSet<>());
            required.keySet().removeAll(implemented);
            if (required.isEmpty()) continue;

            List<MethodDeclaration> missing = List.copyOf(required.values());
            Insertion insertion = implementationInsertion(text, type, missing);
            Range classRange = type.location().range();
            output.add(new MissingInterfaceImplementation(
                    type.name(),
                    classRange,
                    insertion.position(),
                    insertion.text(),
                    missing.stream().map(MethodDeclaration::name).toList()));
        }
        return List.copyOf(output);
    }

    private List<Definition> rankedDefinitions(String text, int offset) {
        String symbol = wordAt(text, offset);
        if (symbol.isBlank()) return List.of();
        Snapshot current = snapshot;
        List<Definition> candidates = current.symbols().getOrDefault(
                symbol.toLowerCase(Locale.ROOT), List.of());
        if (candidates.isEmpty()) return List.of();

        Hint hint = hint(text, offset, symbol);
        String qualifiedHint = qualifiedHint(text, symbol, hint);
        return candidates.stream()
                .sorted(Comparator
                        .comparingInt((Definition value) ->
                                score(value, hint, symbol, qualifiedHint, current.root()))
                        .thenComparing(value -> value.file().toString()))
                .toList();
    }

    Snapshot build(Path projectRoot) {
        Path root = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        if (root == null || !Files.isDirectory(root)) return Snapshot.empty(root);
        Map<String, List<Definition>> symbols = new HashMap<>();
        Map<String, List<Location>> occurrences = new HashMap<>();
        List<TypeDefinition> types = new ArrayList<>();
        for (Path scanRoot : roots(root)) {
            try (Stream<Path> files = Files.walk(scanRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(PhpDefinitionIndex::isPhp)
                        .filter(path -> !ignored(scanRoot, path))
                        .filter(path -> size(path) <= 2_000_000)
                        .forEach(path -> indexFile(path, symbols, types, occurrences));
            } catch (IOException ignored) {
            }
        }
        Map<String, List<Definition>> immutable = new HashMap<>();
        symbols.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        Map<String, List<Location>> immutableOccurrences = new HashMap<>();
        occurrences.forEach((key, value) ->
                immutableOccurrences.put(key, List.copyOf(value)));
        return new Snapshot(root, Map.copyOf(immutable), List.copyOf(types),
                Map.copyOf(immutableOccurrences));
    }

    private static List<Path> roots(Path projectRoot) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(projectRoot);
        Path framework = Path.of("C:\\Users\\danie\\Documents\\development\\php");
        if (Files.isDirectory(framework) && !framework.startsWith(projectRoot)) {
            roots.add(framework);
        }
        return List.copyOf(roots);
    }

    private static void indexFile(Path file, Map<String, List<Definition>> symbols,
                                  List<TypeDefinition> types,
                                  Map<String, List<Location>> occurrences) {
        try {
            String source = Files.readString(file);
            String namespace = namespaceOf(source);
            indexTypes(file, source, namespace, types);
            if (!isVendor(file)) indexIdentifiers(file, source, occurrences);
            Matcher declarations = DECLARATION.matcher(source);
            while (declarations.find()) {
                Kind kind = "function".equals(declarations.group(1)) ? Kind.FUNCTION : Kind.TYPE;
                add(symbols, declarations.group(2), definition(
                        file, source, declarations.start(2), declarations.end(2), kind,
                        qualified(namespace, declarations.group(2))));
            }
            Matcher constants = CONSTANT.matcher(source);
            while (constants.find()) {
                add(symbols, constants.group(1), definition(
                        file, source, constants.start(1), constants.end(1), Kind.CONSTANT,
                        qualified(namespace, constants.group(1))));
            }
        } catch (Exception ignored) {
        }
    }

    private static void indexIdentifiers(Path file, String source,
                                         Map<String, List<Location>> occurrences) {
        int[] lines = lineStarts(source);
        List<OffsetRange> ignoredRanges = ignoredUsageRanges(source);
        int ignoredIndex = 0;
        boolean php = false;
        int i = 0;
        while (i < source.length()) {
            if (!php) {
                int open = source.indexOf("<?", i);
                if (open < 0) return;
                i = source.startsWith("<?php", open) ? open + 5
                        : source.startsWith("<?=", open) ? open + 3 : open + 2;
                php = true;
                continue;
            }
            while (ignoredIndex < ignoredRanges.size()
                    && ignoredRanges.get(ignoredIndex).end() <= i) ignoredIndex++;
            if (ignoredIndex < ignoredRanges.size()) {
                OffsetRange ignored = ignoredRanges.get(ignoredIndex);
                if (i >= ignored.start() && i < ignored.end()) {
                    i = ignored.end();
                    continue;
                }
            }
            if (source.startsWith("?>", i)) {
                php = false;
                i += 2;
                continue;
            }
            char c = source.charAt(i);
            if (c == '\'' || c == '"') {
                i = stringEnd(source, i, c);
            } else if (source.startsWith("//", i)
                    || (c == '#' && (i + 1 >= source.length()
                    || source.charAt(i + 1) != '['))) {
                int newline = source.indexOf('\n', i + 1);
                i = newline < 0 ? source.length() : newline + 1;
            } else if (source.startsWith("/*", i)) {
                int close = source.indexOf("*/", i + 2);
                i = close < 0 ? source.length() : close + 2;
            } else if (identifierStart(c)) {
                int start = i++;
                while (i < source.length() && identifierPart(source.charAt(i))) i++;
                String symbol = source.substring(start, i).toLowerCase(Locale.ROOT);
                PositionData position = positionAt(lines, start);
                Range range = Range.of(position.line(), position.col(),
                        position.line(), position.col() + i - start);
                Path normalized = file.toAbsolutePath().normalize();
                occurrences.computeIfAbsent(symbol, ignored -> new ArrayList<>())
                        .add(Location.of(normalized.toUri().toString(), range));
            } else {
                i++;
            }
        }
    }

    private static List<OffsetRange> ignoredUsageRanges(String source) {
        List<OffsetRange> ranges = new ArrayList<>();
        Matcher imports = IMPORT_OR_NAMESPACE.matcher(source);
        while (imports.find()) ranges.add(new OffsetRange(imports.start(), imports.end()));

        Matcher types = TYPE_HEADER.matcher(source);
        while (types.find()) {
            if (types.start(3) >= 0 && types.end(3) > types.start(3)
                    && !types.group(3).isBlank()) {
                ranges.add(new OffsetRange(types.start(3), types.end(3)));
            }
        }
        return ranges.stream()
                .sorted(Comparator.comparingInt(OffsetRange::start))
                .toList();
    }

    private static void indexTypes(Path file, String source, String namespace,
                                   List<TypeDefinition> output) {
        Map<String, String> imports = importsOf(source);
        Matcher matcher = TYPE_HEADER.matcher(source);
        while (matcher.find()) {
            TypeKind kind;
            try {
                kind = TypeKind.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
                continue;
            }
            String name = matcher.group(2);
            int openBrace = matcher.end() - 1;
            int closeBrace = matchingBrace(source, openBrace);
            if (closeBrace < 0) closeBrace = source.length();

            Set<String> parents = new LinkedHashSet<>();
            Set<String> traits = new LinkedHashSet<>();
            String relations = matcher.group(3) == null ? "" : matcher.group(3);
            Matcher implementsMatcher = IMPLEMENTS.matcher(relations);
            if (implementsMatcher.find()) {
                addRelations(parents, implementsMatcher.group(1), namespace, imports);
            }
            if (kind == TypeKind.INTERFACE || kind == TypeKind.CLASS) {
                Matcher extendsMatcher = EXTENDS.matcher(relations);
                if (extendsMatcher.find()) {
                    addRelations(parents, extendsMatcher.group(1), namespace, imports);
                }
            }
            if (kind == TypeKind.CLASS || kind == TypeKind.TRAIT) {
                Matcher traitUse = USE.matcher(source);
                traitUse.region(openBrace + 1, closeBrace);
                while (traitUse.find()) {
                    if (!isDirectTypeMember(source, openBrace, traitUse.start())) continue;
                    String traitList = traitUse.group(1);
                    if (traitList.contains("{")) {
                        traitList = traitList.substring(0, traitList.indexOf('{'));
                    }
                    for (String raw : traitList.split(",")) {
                        String value = raw.strip();
                        if (value.isBlank()) continue;
                        String resolved = resolveTypeName(value, namespace, imports);
                        if (!resolved.contains("\\") && namespace != null && !namespace.isBlank()) {
                            resolved = qualified(namespace, resolved);
                        }
                        traits.add(resolved);
                    }
                }
            }

            Map<String, Location> methods = new HashMap<>();
            Map<String, MethodDeclaration> methodDeclarations = new java.util.LinkedHashMap<>();
            Matcher methodMatcher = METHOD.matcher(source);
            methodMatcher.region(openBrace + 1, closeBrace);
            while (methodMatcher.find()) {
                if (!isDirectTypeMember(source, openBrace, methodMatcher.start())) continue;
                String method = methodMatcher.group(1);
                String key = method.toLowerCase(Locale.ROOT);
                Location methodLocation = definition(
                        file, source, methodMatcher.start(1), methodMatcher.end(1),
                        Kind.FUNCTION, qualified(namespace, method)).location();
                methods.putIfAbsent(key, methodLocation);
                String signature = methodSignature(source, methodMatcher);
                if (signature != null) {
                    if (kind == TypeKind.INTERFACE) {
                        signature = qualifyMethodSignature(
                                signature, namespace, imports, qualified(namespace, name));
                    }
                    methodDeclarations.putIfAbsent(
                            key, new MethodDeclaration(method, signature, methodLocation));
                }
            }

            Location location = definition(file, source, matcher.start(2), matcher.end(2),
                    Kind.TYPE, qualified(namespace, name)).location();
            output.add(new TypeDefinition(
                    file.toAbsolutePath().normalize(), name, qualified(namespace, name), kind,
                    matcher.start(), matcher.start(2), openBrace, closeBrace,
                    location, Set.copyOf(parents), Set.copyOf(traits), Map.copyOf(methods),
                    Collections.unmodifiableMap(new java.util.LinkedHashMap<>(methodDeclarations)),
                    isAbstractType(source, matcher.start())));
        }
    }

    private static void collectInterfaceMethods(
            TypeDefinition type,
            Map<String, TypeDefinition> byName,
            Map<String, MethodDeclaration> output,
            Set<String> visited) {
        String key = type.qualifiedName().toLowerCase(Locale.ROOT);
        if (!visited.add(key)) return;
        for (String parentName : type.parents()) {
            TypeDefinition parent = byName.get(parentName.toLowerCase(Locale.ROOT));
            if (parent == null) continue;
            if (parent.kind() == TypeKind.INTERFACE) {
                collectInterfaceContract(parent, byName, output, visited);
            } else if (parent.kind() == TypeKind.CLASS) {
                collectInterfaceMethods(parent, byName, output, visited);
            }
        }
    }

    private static void collectInterfaceContract(
            TypeDefinition type,
            Map<String, TypeDefinition> byName,
            Map<String, MethodDeclaration> output,
            Set<String> visited) {
        String key = type.qualifiedName().toLowerCase(Locale.ROOT);
        if (!visited.add(key)) return;
        for (String parentName : type.parents()) {
            TypeDefinition parent = byName.get(parentName.toLowerCase(Locale.ROOT));
            if (parent != null && parent.kind() == TypeKind.INTERFACE) {
                collectInterfaceContract(parent, byName, output, visited);
            }
        }
        type.methodDeclarations().forEach(output::putIfAbsent);
    }

    private static void collectClassMethods(
            TypeDefinition type,
            Map<String, TypeDefinition> byName,
            Set<String> output,
            Set<String> visited) {
        String key = type.qualifiedName().toLowerCase(Locale.ROOT);
        if (!visited.add(key)) return;
        output.addAll(type.methods().keySet());
        for (String traitName : type.traits()) {
            TypeDefinition trait = byName.get(traitName.toLowerCase(Locale.ROOT));
            if (trait != null && trait.kind() == TypeKind.TRAIT) {
                collectClassMethods(trait, byName, output, visited);
            }
        }
        for (String parentName : type.parents()) {
            TypeDefinition parent = byName.get(parentName.toLowerCase(Locale.ROOT));
            if (parent != null && parent.kind() == TypeKind.CLASS) {
                collectClassMethods(parent, byName, output, visited);
            }
        }
    }

    private static Insertion implementationInsertion(
            String source, TypeDefinition type, List<MethodDeclaration> missing) {
        int closeBrace = Math.clamp(type.bodyEnd(), 0, source.length());
        int closeLineStart = lineStart(source, closeBrace);
        boolean closeOnOwnLine = source.substring(closeLineStart, closeBrace).isBlank();
        int insertionOffset = closeOnOwnLine ? closeLineStart : closeBrace;
        String baseIndent = closeOnOwnLine
                ? source.substring(closeLineStart, closeBrace) : lineIndent(source, type.declarationStart());
        String indentUnit = memberIndentUnit(source, type, baseIndent);
        String memberIndent = baseIndent + indentUnit;
        String newline = source.contains("\r\n") ? "\r\n" : "\n";
        boolean hasBodyContent = !source.substring(
                Math.min(type.bodyStart() + 1, closeBrace), closeBrace).isBlank();

        StringBuilder generated = new StringBuilder();
        if (!closeOnOwnLine) generated.append(newline);
        else if (hasBodyContent) generated.append(newline);
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) generated.append(newline);
            String signature = missing.get(i).signature()
                    .replaceFirst("(?i)^abstract\\s+", "")
                    .replaceFirst("(?i)^(?=function\\b)", "public ");
            generated.append(memberIndent).append(signature).append(newline)
                    .append(memberIndent).append('{').append(newline)
                    .append(memberIndent).append(indentUnit)
                    .append("throw new \\LogicException('Not implemented');").append(newline)
                    .append(memberIndent).append('}').append(newline);
        }
        if (!closeOnOwnLine) generated.append(baseIndent);
        PositionData position = positionAt(lineStarts(source), insertionOffset);
        return new Insertion(Position.of(position.line(), position.col()), generated.toString());
    }

    private static String methodSignature(String source, Matcher methodMatcher) {
        int functionStart = methodMatcher.start();
        int start = functionStart;
        int lineStart = lineStart(source, functionStart);
        String prefix = source.substring(lineStart, functionStart).strip();
        if (prefix.matches("(?i)(?:(?:public|protected|private|static|abstract|final)\\s*)*")) {
            start = lineStart;
            while (start < functionStart && Character.isWhitespace(source.charAt(start))) start++;
        }

        int openParen = source.indexOf('(', methodMatcher.end(1));
        if (openParen < 0) return null;
        int closeParen = matchingDelimiter(source, openParen, '(', ')');
        if (closeParen < 0) return null;
        int end = declarationEnd(source, closeParen + 1);
        if (end < 0) return null;
        return source.substring(start, end).strip()
                .replaceFirst("[\\s;{]+$", "");
    }

    private static String qualifyMethodSignature(
            String signature,
            String namespace,
            Map<String, String> imports,
            String ownerType) {
        int function = signature.toLowerCase(Locale.ROOT).indexOf("function");
        int openParen = function < 0 ? -1 : signature.indexOf('(', function);
        int closeParen = openParen < 0 ? -1
                : matchingDelimiter(signature, openParen, '(', ')');
        if (closeParen < 0) return signature;

        String parameters = qualifyParameterTypes(
                signature.substring(openParen + 1, closeParen),
                namespace, imports, ownerType);
        StringBuilder result = new StringBuilder(signature.length() + 32)
                .append(signature, 0, openParen + 1)
                .append(parameters)
                .append(')');
        int colon = signature.indexOf(':', closeParen + 1);
        if (colon < 0) {
            result.append(signature.substring(closeParen + 1));
        } else {
            result.append(signature, closeParen + 1, colon + 1)
                    .append(qualifyTypeExpression(
                            signature.substring(colon + 1),
                            namespace, imports, ownerType));
        }
        return result.toString();
    }

    private static String qualifyParameterTypes(
            String parameters,
            String namespace,
            Map<String, String> imports,
            String ownerType) {
        StringBuilder result = new StringBuilder(parameters.length() + 32);
        int start = 0;
        int round = 0;
        int square = 0;
        int curly = 0;
        char quote = 0;
        for (int i = 0; i <= parameters.length(); i++) {
            char c = i < parameters.length() ? parameters.charAt(i) : ',';
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                round++;
            } else if (c == ')') {
                round--;
            } else if (c == '[') {
                square++;
            } else if (c == ']') {
                square--;
            } else if (c == '{') {
                curly++;
            } else if (c == '}') {
                curly--;
            } else if (c == ',' && round == 0 && square == 0 && curly == 0) {
                if (start > 0) result.append(',');
                result.append(qualifyParameter(
                        parameters.substring(start, i), namespace, imports, ownerType));
                start = i + 1;
            }
        }
        return result.toString();
    }

    private static String qualifyParameter(
            String parameter,
            String namespace,
            Map<String, String> imports,
            String ownerType) {
        int variable = parameter.indexOf('$');
        if (variable < 0) return parameter;
        int typeEnd = variable;
        while (typeEnd > 0 && Character.isWhitespace(parameter.charAt(typeEnd - 1))) typeEnd--;
        if (typeEnd >= 3 && parameter.substring(typeEnd - 3, typeEnd).equals("...")) {
            typeEnd -= 3;
            while (typeEnd > 0 && Character.isWhitespace(parameter.charAt(typeEnd - 1))) typeEnd--;
        }
        if (typeEnd > 0 && parameter.charAt(typeEnd - 1) == '&') {
            typeEnd--;
            while (typeEnd > 0 && Character.isWhitespace(parameter.charAt(typeEnd - 1))) typeEnd--;
        }
        int typeStart = typeEnd;
        while (typeStart > 0 && typeExpressionChar(parameter.charAt(typeStart - 1))) typeStart--;
        if (typeStart == typeEnd) return parameter;
        return parameter.substring(0, typeStart)
                + qualifyTypeExpression(
                parameter.substring(typeStart, typeEnd), namespace, imports, ownerType)
                + parameter.substring(typeEnd);
    }

    private static String qualifyTypeExpression(
            String expression,
            String namespace,
            Map<String, String> imports,
            String ownerType) {
        Matcher matcher = Pattern.compile("\\??\\\\?[A-Za-z_][A-Za-z0-9_\\\\]*")
                .matcher(expression);
        StringBuilder result = new StringBuilder(expression.length() + 16);
        while (matcher.find()) {
            String raw = matcher.group();
            String nullable = raw.startsWith("?") ? "?" : "";
            String type = nullable.isEmpty() ? raw : raw.substring(1);
            String lower = type.replaceFirst("^\\\\+", "").toLowerCase(Locale.ROOT);
            String replacement;
            if (PHP_BUILTIN_TYPES.contains(lower)
                    || lower.equals("static") || lower.equals("parent")) {
                replacement = type;
            } else if (lower.equals("self")) {
                replacement = "\\" + ownerType;
            } else {
                replacement = "\\" + resolveTypeName(type, namespace, imports);
            }
            matcher.appendReplacement(
                    result, Matcher.quoteReplacement(nullable + replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean typeExpressionChar(char value) {
        return Character.isLetterOrDigit(value)
                || value == '_' || value == '\\' || value == '?'
                || value == '|' || value == '&' || Character.isWhitespace(value);
    }

    private static int declarationEnd(String source, int start) {
        int round = 0;
        int square = 0;
        char quote = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') quote = c;
            else if (c == '(') round++;
            else if (c == ')') round--;
            else if (c == '[') square++;
            else if (c == ']') square--;
            else if (round == 0 && square == 0 && (c == ';' || c == '{')) return i;
        }
        return -1;
    }

    private static int matchingDelimiter(
            String source, int open, char opening, char closing) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') quote = c;
            else if (c == opening) depth++;
            else if (c == closing && --depth == 0) return i;
        }
        return -1;
    }

    private static boolean isDirectTypeMember(String source, int bodyStart, int offset) {
        int depth = 0;
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = bodyStart + 1; i < offset; i++) {
            char c = source.charAt(i);
            char next = i + 1 < offset ? source.charAt(i + 1) : 0;
            if (lineComment) {
                if (c == '\n') lineComment = false;
            } else if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
            } else if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '/' && next == '/') {
                lineComment = true;
                i++;
            } else if (c == '#') {
                lineComment = true;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth == 0;
    }

    private static boolean isAbstractType(String source, int classStart) {
        int start = lineStart(source, classStart);
        return source.substring(start, classStart).matches("(?is).*\\babstract\\s+");
    }

    private static String memberIndentUnit(
            String source, TypeDefinition type, String baseIndent) {
        for (MethodDeclaration method : type.methodDeclarations().values()) {
            int offset = offsetAt(source, method.location().range().start());
            String indent = lineIndent(source, offset);
            if (indent.startsWith(baseIndent) && indent.length() > baseIndent.length()) {
                return indent.substring(baseIndent.length());
            }
        }
        return source.contains("\n\t") ? "\t" : "    ";
    }

    private static int offsetAt(String source, Position position) {
        int[] starts = lineStarts(source);
        if (position == null || position.line() < 0 || position.line() >= starts.length) return 0;
        return Math.min(source.length(), starts[position.line()] + Math.max(0, position.col()));
    }

    private static int lineStart(String source, int offset) {
        int newline = source.lastIndexOf('\n', Math.max(0, offset - 1));
        return newline < 0 ? 0 : newline + 1;
    }

    private static String lineIndent(String source, int offset) {
        int start = lineStart(source, offset);
        int end = start;
        while (end < source.length()) {
            char c = source.charAt(end);
            if (c != ' ' && c != '\t') break;
            end++;
        }
        return source.substring(start, end);
    }

    private static void addRelations(Set<String> output, String list, String namespace,
                                     Map<String, String> imports) {
        if (list == null) return;
        for (String raw : list.split(",")) {
            String value = raw.strip().replaceFirst("\\s.*$", "");
            if (!value.isBlank()) output.add(resolveTypeName(value, namespace, imports));
        }
    }

    private static Definition definition(Path file, String source, int start, int end,
                                         Kind kind, String qualifiedName) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < start; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int startCol = start - lineStart;
        Range range = Range.of(line, startCol, line, startCol + Math.max(1, end - start));
        Path normalized = file.toAbsolutePath().normalize();
        return new Definition(normalized, kind, qualifiedName,
                Location.of(normalized.toUri().toString(), range));
    }

    private static void add(Map<String, List<Definition>> values, String symbol, Definition definition) {
        values.computeIfAbsent(symbol.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                .add(definition);
    }

    public static String wordAt(String text, int offset) {
        if (text == null || text.isEmpty()) return "";
        int at = Math.clamp(offset, 0, text.length());
        if (at == text.length() || (at < text.length() && !identifierPart(text.charAt(at)))) at--;
        if (at < 0 || !identifierPart(text.charAt(at))) return "";
        int start = at;
        int end = at + 1;
        while (start > 0 && identifierPart(text.charAt(start - 1))) start--;
        while (end < text.length() && identifierPart(text.charAt(end))) end++;
        return text.substring(start, end);
    }

    private static Hint hint(String text, int offset, String symbol) {
        if (text == null) return Hint.ANY;
        int start = Math.max(0, Math.min(offset, text.length()) - symbol.length() - 32);
        int end = Math.min(text.length(), Math.max(offset, 0) + symbol.length() + 8);
        String around = text.substring(start, end);
        int symbolAt = around.indexOf(symbol);
        if (symbolAt < 0) return Hint.ANY;
        String before = around.substring(0, symbolAt).stripTrailing();
        String after = around.substring(symbolAt + symbol.length()).stripLeading();
        if (before.endsWith("->") || after.startsWith("(")) return Hint.FUNCTION;
        if (before.endsWith("::") && !after.startsWith("(")) return Hint.CONSTANT;
        if (before.matches("(?s).*(?:new|extends|implements|use|instanceof)\\s*$")
                || before.endsWith("#[")) return Hint.TYPE;
        return Hint.ANY;
    }

    private static int score(Definition value, Hint hint, String symbol,
                             String qualifiedHint, Path root) {
        int score = switch (hint) {
            case TYPE -> value.kind() == Kind.TYPE ? 0 : 30;
            case FUNCTION -> value.kind() == Kind.FUNCTION ? 0 : 30;
            case CONSTANT -> value.kind() == Kind.CONSTANT ? 0 : 30;
            case ANY -> 0;
        };
        if (qualifiedHint != null && !qualifiedHint.isBlank()
                && !qualifiedHint.equalsIgnoreCase(value.qualifiedName())) {
            score += 100;
        }
        String filename = value.file().getFileName() == null ? ""
                : value.file().getFileName().toString();
        if (!filename.equalsIgnoreCase(symbol + ".php")) score += 4;
        if (root != null && !value.file().startsWith(root)) score += 8;
        if (value.file().toString().toLowerCase(Locale.ROOT)
                .contains(java.io.File.separator + "vendor" + java.io.File.separator)) score += 2;
        return score;
    }

    private static String qualifiedHint(String source, String symbol, Hint hint) {
        if (source == null || symbol == null || symbol.isBlank()) return null;
        Matcher uses = USE.matcher(source);
        while (uses.find()) {
            String imported = uses.group(1).strip()
                    .replaceFirst("(?i)^(?:function|const)\\s+", "");
            if (imported.contains("{") || imported.contains(",")) continue;
            String[] alias = imported.split("(?i)\\s+as\\s+", 2);
            String qualified = alias[0].strip().replaceFirst("^\\\\+", "");
            String local = alias.length == 2 ? alias[1].strip() : lastSegment(qualified);
            if (local.equalsIgnoreCase(symbol)) return qualified;
        }
        if (hint == Hint.TYPE) {
            String namespace = namespaceOf(source);
            if (!namespace.isBlank()) return qualified(namespace, symbol);
        }
        return null;
    }

    private static String namespaceOf(String source) {
        if (source == null) return "";
        Matcher matcher = NAMESPACE.matcher(source);
        return matcher.find() ? matcher.group(1).strip().replaceFirst("^\\\\+", "") : "";
    }

    private static String qualified(String namespace, String symbol) {
        return namespace == null || namespace.isBlank() ? symbol : namespace + "\\" + symbol;
    }

    private static String lastSegment(String qualified) {
        int slash = qualified.lastIndexOf('\\');
        return slash < 0 ? qualified : qualified.substring(slash + 1);
    }

    private static Map<String, String> importsOf(String source) {
        Map<String, String> imports = new HashMap<>();
        Matcher matcher = USE.matcher(source == null ? "" : source);
        while (matcher.find()) {
            String imported = matcher.group(1).strip()
                    .replaceFirst("(?i)^(?:function|const)\\s+", "");
            if (imported.contains("{") || imported.contains(",")) continue;
            String[] alias = imported.split("(?i)\\s+as\\s+", 2);
            String qualified = alias[0].strip().replaceFirst("^\\\\+", "");
            String local = alias.length == 2 ? alias[1].strip() : lastSegment(qualified);
            imports.put(local.toLowerCase(Locale.ROOT), qualified);
        }
        return imports;
    }

    private static String resolveTypeName(String raw, String namespace,
                                          Map<String, String> imports) {
        boolean absolute = raw.strip().startsWith("\\");
        String value = raw.strip().replaceFirst("^\\\\+", "");
        if (absolute) return value;
        String imported = imports.get(value.toLowerCase(Locale.ROOT));
        if (imported != null) return imported;
        int slash = value.indexOf('\\');
        if (slash > 0) {
            String head = value.substring(0, slash);
            String prefix = imports.get(head.toLowerCase(Locale.ROOT));
            if (prefix != null) return prefix + value.substring(slash);
        }
        return qualified(namespace, value);
    }

    private static boolean implementsInterface(TypeDefinition type, String target,
                                               Map<String, TypeDefinition> byName,
                                               Set<String> visited) {
        String key = type.qualifiedName().toLowerCase(Locale.ROOT);
        if (!visited.add(key)) return false;
        for (String parent : type.parents()) {
            if (parent.equalsIgnoreCase(target)) return true;
            TypeDefinition parentType = byName.get(parent.toLowerCase(Locale.ROOT));
            if (parentType != null
                    && implementsInterface(parentType, target, byName, visited)) return true;
        }
        return false;
    }

    private static String interfaceAt(String text, int offset) {
        if (text == null) return null;
        String namespace = namespaceOf(text);
        Matcher matcher = TYPE_HEADER.matcher(text);
        int at = Math.clamp(offset, 0, text.length());
        while (matcher.find()) {
            if (!"interface".equalsIgnoreCase(matcher.group(1))) continue;
            int close = matchingBrace(text, matcher.end() - 1);
            if (close < 0) close = text.length();
            if (at >= matcher.start() && at <= close) {
                return qualified(namespace, matcher.group(2));
            }
        }
        return null;
    }

    private static boolean isInterfaceName(String text, int offset, String qualifiedInterface) {
        String name = lastSegment(qualifiedInterface);
        return name.equalsIgnoreCase(wordAt(text, offset));
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (lineComment) {
                if (c == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
            } else if (c == '#') {
                lineComment = true;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int lineAt(String source, int offset) {
        int line = 0;
        for (int i = 0; i < Math.min(offset, source.length()); i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static boolean samePosition(Location first, Location second) {
        return first != null && second != null
                && first.uri().equalsIgnoreCase(second.uri())
                && first.range() != null && second.range() != null
                && first.range().start() != null && second.range().start() != null
                && first.range().start().line() == second.range().start().line()
                && first.range().start().col() == second.range().start().col();
    }

    private static int stringEnd(String source, int at, char quote) {
        int i = at + 1;
        while (i < source.length()) {
            if (source.charAt(i) == '\\' && i + 1 < source.length()) i += 2;
            else if (source.charAt(i) == quote) return i + 1;
            else i++;
        }
        return i;
    }

    private static int[] lineStarts(String source) {
        int count = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') count++;
        }
        int[] starts = new int[count];
        int line = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') starts[line++] = i + 1;
        }
        return starts;
    }

    private static PositionData positionAt(int[] lineStarts, int offset) {
        int low = 0;
        int high = lineStarts.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lineStarts[middle] <= offset) low = middle + 1;
            else high = middle - 1;
        }
        int line = Math.max(0, high);
        return new PositionData(line, offset - lineStarts[line]);
    }

    private static boolean ignored(Path root, Path file) {
        Path relative;
        try {
            relative = root.relativize(file);
        } catch (Exception e) {
            return false;
        }
        for (Path part : relative) {
            if (IGNORED.contains(part.toString().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean isPhp(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".php");
    }

    private static boolean isVendor(Path path) {
        for (Path part : path) {
            if ("vendor".equalsIgnoreCase(part.toString())) return true;
        }
        return false;
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean identifierPart(char value) {
        return value == '_' || Character.isLetterOrDigit(value) || value >= 128;
    }

    private static boolean identifierStart(char value) {
        return value == '_' || Character.isLetter(value) || value >= 128;
    }

    enum Kind { TYPE, FUNCTION, CONSTANT }
    enum Hint { TYPE, FUNCTION, CONSTANT, ANY }
    enum TypeKind { CLASS, INTERFACE, TRAIT, ENUM }
    record Definition(Path file, Kind kind, String qualifiedName, Location location) {}
    record MethodDeclaration(String name, String signature, Location location) {}
    record TypeDefinition(Path file, String name, String qualifiedName, TypeKind kind,
                          int declarationStart, int nameStart, int bodyStart, int bodyEnd,
                          Location location, Set<String> parents, Set<String> traits,
                          Map<String, Location> methods,
                          Map<String, MethodDeclaration> methodDeclarations,
                          boolean abstractType) {}
    record PositionData(int line, int col) {}
    record OffsetRange(int start, int end) {}
    record Insertion(Position position, String text) {}
    public record ImplementationAnchor(int line, int offset, String symbol, boolean method) {}
    public record MissingInterfaceImplementation(
            String className,
            Range range,
            Position insertion,
            String implementation,
            List<String> methodNames) {}
    record Snapshot(Path root, Map<String, List<Definition>> symbols,
                    List<TypeDefinition> types, Map<String, List<Location>> occurrences) {
        static Snapshot empty(Path root) {
            return new Snapshot(root, Map.of(), List.of(), Map.of());
        }
    }
}
