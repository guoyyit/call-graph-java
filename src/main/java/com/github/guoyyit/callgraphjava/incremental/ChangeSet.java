package com.github.guoyyit.callgraphjava.incremental;

import java.nio.file.Path;
import java.util.Map;

/** 一批待应用变更：相对路径 -> 变更类型。 */
public record ChangeSet(Map<Path, FileChange> changes) {

    public static ChangeSet empty() {
        return new ChangeSet(Map.of());
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public java.util.List<Path> pathsOf(FileChange kind) {
        return changes.entrySet().stream()
                .filter(e -> e.getValue() == kind)
                .map(Map.Entry::getKey)
                .toList();
    }
}
