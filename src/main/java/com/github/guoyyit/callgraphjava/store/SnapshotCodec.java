package com.github.guoyyit.callgraphjava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.callgraphjava.analysis.BuildResult;
import com.github.guoyyit.callgraphjava.analysis.TypeHierarchy;
import com.github.guoyyit.callgraphjava.graph.CallGraph;
import com.github.guoyyit.callgraphjava.model.CallEdge;
import com.github.guoyyit.callgraphjava.model.CallKind;
import com.github.guoyyit.callgraphjava.model.CallSite;
import com.github.guoyyit.callgraphjava.model.FieldInfo;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.MethodInfo;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import com.github.guoyyit.callgraphjava.model.TypeInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照 JSON 编解码。记录类型（record）包含不可变字段与集合，统一显式逐字段序列化，
 * 避免依赖编译器参数名保留设置。
 */
final class SnapshotCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    String encode(StoredSnapshot snapshot) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root(snapshot));
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode snapshot", e);
        }
    }

    StoredSnapshot decode(String json) {
        try {
            Map<String, Object> root = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            long version = intLike(root.get("version"));
            long transactionId = intLike(root.get("transactionId"));
            long committedAt = intLike(root.get("committedAtMillis"));
            Map<String, String> hashes = toStringMap(root.get("fileHashes"));

            Map<String, ParsedFile> files = new LinkedHashMap<>();
            for (Object item : asList(root.get("files"))) {
                Map<String, Object> fm = asMap(item);
                ParsedFile parsed = parseFile(fm);
                files.put(parsed.relativePath(), parsed);
            }
            List<TypeInfo> allTypes = files.values().stream()
                    .flatMap(f -> f.types().stream()).toList();
            TypeHierarchy hierarchy = TypeHierarchy.build(allTypes, files.values());

            List<CallEdge> edges = new ArrayList<>();
            for (Object item : asList(root.get("edges"))) {
                edges.add(parseEdge(asMap(item)));
            }
            java.util.Set<MethodId> methods = new java.util.LinkedHashSet<>();
            for (ParsedFile file : files.values()) {
                for (MethodInfo method : file.methods()) {
                    methods.add(method.id());
                }
            }
            for (CallEdge edge : edges) {
                methods.add(edge.caller());
                methods.add(edge.callee());
            }
            CallGraph graph = CallGraph.of(edges, methods);
            BuildResult result = new BuildResult(graph, files, hierarchy, Map.copyOf(hashes));
            return new StoredSnapshot(version, transactionId, result,
                    Map.copyOf(hashes), committedAt);
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode snapshot", e);
        }
    }

    private Map<String, Object> root(StoredSnapshot snapshot) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", snapshot.version());
        root.put("transactionId", snapshot.transactionId());
        root.put("committedAtMillis", snapshot.committedAtMillis());
        root.put("fileHashes", snapshot.fileHashes());

        List<Map<String, Object>> files = new ArrayList<>();
        for (ParsedFile file : snapshot.result().parsedFiles().values()) {
            files.add(writeFile(file));
        }
        root.put("files", files);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (CallEdge edge : snapshot.result().graph().edges()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("caller", edge.caller().fqn());
            e.put("callee", edge.callee().fqn());
            e.put("kind", edge.kind().name());
            e.put("line", edge.line());
            e.put("sourceFile", edge.sourceFile());
            edges.add(e);
        }
        root.put("edges", edges);
        return root;
    }

    private Map<String, Object> writeFile(ParsedFile file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("relativePath", file.relativePath());
        map.put("packageName", file.packageName());
        map.put("imports", file.imports());
        map.put("staticImports", file.staticImports());
        map.put("sha256", file.sha256());

        List<Map<String, Object>> types = new ArrayList<>();
        for (TypeInfo t : file.types()) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("fqn", t.fqn());
            tm.put("kind", t.kind());
            tm.put("superClass", t.superClass());
            tm.put("interfaces", t.interfaces());
            tm.put("declaredMethods", t.declaredMethods().stream().map(MethodId::fqn).toList());
            List<Map<String, Object>> fields = new ArrayList<>();
            for (FieldInfo field : t.fields()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", field.name());
                fm.put("rawType", field.rawType());
                fields.add(fm);
            }
            tm.put("fields", fields);
            types.add(tm);
        }
        map.put("types", types);

        List<Map<String, Object>> methods = new ArrayList<>();
        for (MethodInfo m : file.methods()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.id().fqn());
            mm.put("isStatic", m.isStatic());
            mm.put("isAbstract", m.isAbstract());
            mm.put("sourceFile", m.sourceFile());
            mm.put("locals", m.locals());
            mm.put("returnType", m.returnType());
            methods.add(mm);
        }
        map.put("methods", methods);

        List<Map<String, Object>> calls = new ArrayList<>();
        for (CallSite cs : file.callSites()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("enclosing", cs.enclosingMethod().fqn());
            cm.put("line", cs.line());
            cm.put("kind", cs.kind().name());
            cm.put("targetName", cs.targetName());
            cm.put("arity", cs.arity());
            cm.put("scope", cs.scope());
            cm.put("staticOwner", cs.staticTargetOwner());
            cm.put("receiverType", cs.receiverType());
            cm.put("targets", cs.resolvedTargets().stream().map(MethodId::fqn).toList());
            calls.add(cm);
        }
        map.put("callSites", calls);
        return map;
    }

    private ParsedFile parseFile(Map<String, Object> map) {
        List<TypeInfo> types = new ArrayList<>();
        Map<String, MethodId> declaredByFqn = new LinkedHashMap<>();
        for (Object item : asList(map.get("types"))) {
            Map<String, Object> tm = asMap(item);
            List<MethodId> declared = new ArrayList<>();
            for (Object methodFqn : asList(tm.get("declaredMethods"))) {
                MethodId id = MethodId.parse((String) methodFqn);
                declared.add(id);
                declaredByFqn.put(id.fqn(), id);
            }
            List<FieldInfo> fields = new ArrayList<>();
            for (Object item2 : asList(tm.get("fields"))) {
                Map<String, Object> fm = asMap(item2);
                fields.add(new FieldInfo((String) fm.get("name"), (String) fm.get("rawType")));
            }
            types.add(new TypeInfo((String) tm.get("fqn"), (String) tm.get("kind"),
                    (String) tm.get("superClass"),
                    asList(tm.get("interfaces")).stream().map(String.class::cast).toList(),
                    List.copyOf(declared), List.copyOf(fields)));
        }

        List<MethodInfo> methods = new ArrayList<>();
        Map<String, MethodInfo> methodByFqn = new LinkedHashMap<>();
        for (Object item : asList(map.get("methods"))) {
            Map<String, Object> mm = asMap(item);
            MethodId id = MethodId.parse((String) mm.get("id"));
            Map<String, String> locals = toStringMap(mm.get("locals"));
            MethodInfo info = new MethodInfo(id, bool(mm.get("isStatic")),
                    bool(mm.get("isAbstract")), (String) mm.get("sourceFile"),
                    Map.copyOf(locals), (String) mm.get("returnType"));
            methods.add(info);
            methodByFqn.put(id.fqn(), info);
        }

        List<CallSite> calls = new ArrayList<>();
        for (Object item : asList(map.get("callSites"))) {
            Map<String, Object> cm = asMap(item);
            MethodId enclosing = MethodId.parse((String) cm.get("enclosing"));
            CallSite cs = new CallSite((String) map.get("relativePath"), enclosing,
                    intLike(cm.get("line")),
                    CallKind.valueOf((String) cm.get("kind")),
                    (String) cm.get("targetName"),
                    intLike(cm.get("arity")),
                    (String) cm.get("scope"),
                    (String) cm.get("staticOwner"));
            cs.setReceiverType((String) cm.get("receiverType"));
            List<MethodId> targets = asList(cm.get("targets")).stream()
                    .map(o -> MethodId.parse((String) o)).toList();
            cs.setResolvedTargets(targets);
            calls.add(cs);
        }

        return new ParsedFile((String) map.get("relativePath"),
                (String) map.get("packageName"),
                asList(map.get("imports")).stream().map(String.class::cast).toList(),
                asList(map.get("staticImports")).stream().map(String.class::cast).toList(),
                List.copyOf(types), List.copyOf(methods), List.copyOf(calls),
                (String) map.get("sha256"));
    }

    private CallEdge parseEdge(Map<String, Object> map) {
        return new CallEdge(MethodId.parse((String) map.get("caller")),
                MethodId.parse((String) map.get("callee")),
                CallKind.valueOf((String) map.get("kind")),
                intLike(map.get("line")),
                (String) map.get("sourceFile"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> toStringMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
            result.put(e.getKey(), e.getValue() == null ? null : e.getValue().toString());
        }
        return result;
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static int intLike(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}
