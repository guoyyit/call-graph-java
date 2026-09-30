package com.github.guoyyit.callgraphjava.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record GraphSnapshot(long revision, Map<String, ParsedFile> rawFiles,
                            Map<String, ParsedFile> files, Map<String, TypeInfo> types,
                            Map<String, MethodNode> methods, Map<String, String> resolvedReceiver,
                            List<Edge> edges) {
    public GraphSnapshot {
        rawFiles = rawFiles == null ? Map.of() : Map.copyOf(rawFiles);
        files = files == null ? Map.of() : Map.copyOf(files);
        types = types == null ? Map.of() : Map.copyOf(types);
        methods = methods == null ? Map.of() : Map.copyOf(methods);
        resolvedReceiver = resolvedReceiver == null ? Map.of() : Map.copyOf(resolvedReceiver);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static GraphSnapshot empty() {
        return new GraphSnapshot(0, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
    }

    public Map<String, ParsedFile> mutableRawFiles() {
        return new HashMap<>(rawFiles);
    }
}
