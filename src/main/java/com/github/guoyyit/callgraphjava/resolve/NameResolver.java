package com.github.guoyyit.callgraphjava.resolve;

import com.github.guoyyit.callgraphjava.model.CallSite;
import com.github.guoyyit.callgraphjava.model.FieldInfo;
import com.github.guoyyit.callgraphjava.model.LocalVar;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.MethodNode;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import com.github.guoyyit.callgraphjava.model.TypeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.Set;

public class NameResolver {
    private static final Map<String, String> PRIMITIVES = Map.of(
            "void", "void", "boolean", "boolean", "byte", "byte", "char", "char",
            "short", "short", "int", "int", "long", "long", "float", "float", "double", "double");

    private final Map<String, ParsedFile> files;
    private final Map<String, TypeInfo> types;
    private final Map<String, List<String>> simpleTypeIndex = new HashMap<>();

    public NameResolver(Map<String, ParsedFile> parsedFiles, Map<String, TypeInfo> typeRegistry) {
        this.files = parsedFiles;
        this.types = typeRegistry;
        for (TypeInfo type : typeRegistry.values()) {
            simpleTypeIndex.computeIfAbsent(simpleName(type.qualifiedName()), key -> new ArrayList<>())
                    .add(type.qualifiedName());
        }
    }

    public Map<String, ParsedFile> normalizeAll() {
        Map<String, ParsedFile> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ParsedFile> entry : files.entrySet()) {
            normalized.put(entry.getKey(), normalizeFile(entry.getValue()));
        }
        return normalized;
    }

    private ParsedFile normalizeFile(ParsedFile parsed) {
        List<TypeInfo> typeInfos = parsed.types().stream()
                .map(type -> normalizeTypeInfo(type, parsed.packageName()))
                .toList();
        List<MethodNode> methodNodes = parsed.methods().stream()
                .map(method -> normalizeMethod(method, parsed.packageName())).toList();
        List<FieldInfo> fieldInfos = parsed.fields().stream()
                .map(field -> new FieldInfo(field.owner(), field.name(),
                        qualify(parsed.packageName(), parsed.importedSimple(), field.type()),
                        field.staticField()))
                .toList();
        java.util.Map<MethodId, MethodId> methodIdMap = new java.util.HashMap<>();
        for (int index = 0; index < parsed.methods().size(); index++) {
            methodIdMap.put(parsed.methods().get(index).id(), methodNodes.get(index).id());
        }
        List<LocalVar> localVars = parsed.locals().stream()
                .map(local -> new LocalVar(methodIdMap.getOrDefault(local.method(), local.method()), local.name(),
                        qualify(parsed.packageName(), parsed.importedSimple(), local.declaredType()),
                        local.initKind(),
                        local.initText() == null ? null
                                : qualify(parsed.packageName(), parsed.importedSimple(), local.initText())))
                .toList();
        List<CallSite> callSites = parsed.callSites().stream()
                .map(site -> normalizeCallSite(site, parsed.packageName(), parsed.importedSimple()))
                .toList();
        return new ParsedFile(parsed.path(), parsed.contentHash(), typeInfos, methodNodes,
                callSites, fieldInfos, localVars, parsed.importedSimple(), parsed.packageName());
    }

    private CallSite normalizeCallSite(CallSite site, String packageName, List<String> imports) {
        List<String> params = site.caller().paramTypes().stream()
                .map(type -> qualify(packageName, imports, type)).toList();
        MethodId caller = new MethodId(site.caller().owner(), site.caller().name(), params);
        String receiverText = site.receiverText();
        if ("TYPE".equals(site.receiverKind()) || "CTOR".equals(site.receiverKind())) {
            receiverText = qualify(packageName, imports, stripGenerics(receiverText));
        }
        return new CallSite(caller, site.file(), site.line(), site.name(), site.arity(),
                site.receiverKind(), receiverText, site.hint());
    }

    private TypeInfo normalizeTypeInfo(TypeInfo type, String packageName) {
        String superClass = type.superClass() == null ? null
                : qualify(packageName, List.of(), type.superClass());
        List<String> interfaces = type.interfaces().stream()
                .map(name -> qualify(packageName, List.of(), name)).toList();
        return new TypeInfo(type.qualifiedName(), type.kind(), superClass, interfaces, type.file(),
                type.abstractType(), type.outerClass());
    }

    private MethodNode normalizeMethod(MethodNode node, String packageName) {
        MethodId oldId = node.id();
        List<String> params = oldId.paramTypes().stream()
                .map(type -> qualify(packageName, List.of(), type)).toList();
        MethodId newId = new MethodId(oldId.owner(), oldId.name(), params);
        return new MethodNode(newId, node.file(), node.abstractMethod(), node.staticMethod(),
                node.privateMethod(),
                qualify(packageName, List.of(), node.returnType()), node.paramNames());
    }

    public String resolveSimple(String file, String simpleName) {
        ParsedFile parsed = files.get(file);
        if (parsed == null) {
            return simpleName;
        }
        return qualify(parsed.packageName(), parsed.importedSimple(), simpleName);
    }

    public String inferReceiverType(CallSite site) {
        ParsedFile parsed = files.get(site.file());
        if (parsed == null) {
            return null;
        }
        switch (site.receiverKind()) {
            case "THIS", "SUPER", "UNQUALIFIED":
                return site.caller().owner();
            case "TYPE":
                return qualify(parsed.packageName(), parsed.importedSimple(),
                        stripGenerics(site.receiverText()));
            case "CTOR":
                return qualify(parsed.packageName(), parsed.importedSimple(),
                        stripGenerics(site.receiverText()));
            case "EXPR":
                return inferExpressionType(site, parsed);
            default:
                return null;
        }
    }

    private String inferExpressionType(CallSite site, ParsedFile parsed) {
        String expression = stripGenerics(site.receiverText());
        if (expression.contains(".")) {
            String root = expression.split("\\.", 2)[0];
            String rootType = lookupLocalType(site.caller(), root, parsed);
            if (rootType == null) {
                return null;
            }
            return followPropertyChain(rootType, expression.substring(root.length() + 1));
        }
        return lookupLocalType(site.caller(), expression, parsed);
    }

    private String lookupLocalType(MethodId method, String variable, ParsedFile parsed) {
        for (LocalVar local : parsed.locals()) {
            if (local.method().equals(method) && local.name().equals(variable)) {
                if ("NEW".equals(local.initKind())) {
                    return local.initText();
                }
                return local.declaredType();
            }
        }
        for (FieldInfo field : parsed.fields()) {
            if (field.owner().equals(method.owner()) && field.name().equals(variable)) {
                return field.type();
            }
        }
        return null;
    }

    private String followPropertyChain(String startType, String chain) {
        String current = startType;
        for (String segment : chain.split("\\.")) {
            String accessor = segment.endsWith("()")
                    ? "get" + capitalize(segment.substring(0, segment.length() - 2))
                    : segment;
            String next = findMemberType(current, accessor, segment);
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    private String findMemberType(String owner, String accessor, String rawSegment) {
        String stripped = accessor.startsWith("get") ? accessor.substring(3) : accessor;
        String fieldName = stripped.isEmpty() ? stripped
                : Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
        for (ParsedFile parsed : files.values()) {
            for (FieldInfo field : parsed.fields()) {
                if (field.owner().equals(owner) && field.name().equals(fieldName)) {
                    return field.type();
                }
            }
            for (MethodNode method : parsed.methods()) {
                boolean getterMatch = method.id().owner().equals(owner)
                        && (method.id().name().equals(accessor)
                        || method.id().name().equals(rawSegment.replace("()", "")));
                if (getterMatch) {
                    return method.returnType();
                }
            }
        }
        return null;
    }

    private String qualify(String packageName, List<String> explicitImports, String type) {
        if (type == null || type.isEmpty()) {
            return type;
        }
        String withoutGenerics = stripGenerics(type);
        if (PRIMITIVES.containsKey(withoutGenerics)) {
            return withoutGenerics;
        }
        String[] parts = withoutGenerics.split("\\.");
        String head = parts[0];
        String tail = withoutGenerics.substring(head.length());
        if (explicitImports.contains(withoutGenerics)) {
            return withoutGenerics;
        }
        for (String imported : explicitImports) {
            if (imported.endsWith("." + head)) {
                return imported + tail;
            }
        }
        if (types.containsKey(withoutGenerics)) {
            return withoutGenerics;
        }
        List<String> bySimple = simpleTypeIndex.get(head);
        if (bySimple != null && bySimple.size() == 1) {
            return bySimple.get(0) + tail;
        }
        if (packageName != null && !packageName.isEmpty()) {
            String inPackage = packageName + "." + withoutGenerics;
            if (types.containsKey(inPackage)) {
                return inPackage;
            }
            String headInPackage = packageName + "." + head;
            if (types.containsKey(headInPackage)) {
                return headInPackage + tail;
            }
        }
        String javaLang = "java.lang." + head;
        if (isLikelyJdkType(head)) {
            return javaLang + tail;
        }
        return withoutGenerics;
    }

    private boolean isLikelyJdkType(String simple) {
        return Set.of("String", "Object", "Integer", "Long", "Boolean", "Double", "Float",
                "Short", "Byte", "Character", "Number", "Iterable", "Comparable", "Throwable",
                "Exception", "RuntimeException", "Override").contains(simple);
    }

    private String stripGenerics(String type) {
        return type == null ? null : type.replaceAll("<[^<>]*>", "");
    }

    private String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

}
