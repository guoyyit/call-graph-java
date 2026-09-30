package com.github.guoyyit.callgraphjava.build;

import com.github.guoyyit.callgraphjava.analysis.TypeHierarchy;
import com.github.guoyyit.callgraphjava.model.CallSite;
import com.github.guoyyit.callgraphjava.model.Edge;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.model.MethodNode;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import com.github.guoyyit.callgraphjava.model.TypeInfo;
import com.github.guoyyit.callgraphjava.parse.SourceParser;
import com.github.guoyyit.callgraphjava.parse.SourceScanner;
import com.github.guoyyit.callgraphjava.resolve.CallResolver;
import com.github.guoyyit.callgraphjava.resolve.NameResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

public class GraphBuilder {
    private final SourceScanner scanner = new SourceScanner();
    private final SourceParser parser = new SourceParser();

    public GraphSnapshot buildFull(Path root) {
        return buildFull(root, ProgressToken.neverCancelled());
    }

    public GraphSnapshot buildFull(Path root, ProgressToken token) {
        List<Path> sources = scanner.scan(root);
        Map<String, ParsedFile> raw = new LinkedHashMap<>();
        for (Path source : sources) {
            checkCancelled(token);
            String display = relativize(root, source);
            raw.put(display, parser.parse(source, display));
        }
        return assemble(raw, token, 1);
    }

    GraphSnapshot assemble(Map<String, ParsedFile> raw, ProgressToken token, long revision) {
        Map<String, TypeInfo> typeRegistry = collectTypes(raw);
        NameResolver normalizer = new NameResolver(raw, typeRegistry);
        Map<String, ParsedFile> normalized = normalizer.normalizeAll();
        typeRegistry = collectTypes(normalized);
        Map<String, MethodNode> methods = collectMethods(normalized);
        TypeHierarchy hierarchy = new TypeHierarchy(typeRegistry.values());
        NameResolver resolverNames = new NameResolver(normalized, typeRegistry);
        CallResolver resolver = new CallResolver(hierarchy, resolverNames, methods);
        Map<String, String> receivers = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        for (ParsedFile file : normalized.values()) {
            checkCancelled(token);
            for (CallSite site : file.callSites()) {
                String receiverType = resolverNames.inferReceiverType(site);
                if (receiverType != null) {
                    receivers.put(site.siteId(), receiverType);
                }
                edges.addAll(resolver.resolve(site));
            }
        }
        return new GraphSnapshot(revision, raw, normalized, typeRegistry, methods, receivers,
                dedup(edges));
    }

    private Map<String, TypeInfo> collectTypes(Map<String, ParsedFile> files) {
        Map<String, TypeInfo> registry = new LinkedHashMap<>();
        for (ParsedFile file : files.values()) {
            for (TypeInfo type : file.types()) {
                registry.put(type.qualifiedName(), type);
            }
        }
        return registry;
    }

    private Map<String, MethodNode> collectMethods(Map<String, ParsedFile> files) {
        Map<String, MethodNode> registry = new LinkedHashMap<>();
        for (ParsedFile file : files.values()) {
            for (MethodNode method : file.methods()) {
                registry.put(method.id().key(), method);
            }
        }
        return registry;
    }

    private List<Edge> dedup(List<Edge> edges) {
        Map<String, Edge> unique = new LinkedHashMap<>();
        for (Edge edge : edges) {
            unique.putIfAbsent(edge.key(), edge);
        }
        return List.copyOf(unique.values());
    }

    private String relativize(Path root, Path source) {
        try {
            return root.toAbsolutePath().relativize(source.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return source.toString();
        }
    }

    private void checkCancelled(ProgressToken token) {
        if (token != null && token.isCancelled()) {
            throw new CancellationException("call graph build cancelled");
        }
    }
}
