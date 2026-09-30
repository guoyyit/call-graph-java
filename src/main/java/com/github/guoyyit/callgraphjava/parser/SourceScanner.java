package com.github.guoyyit.callgraphjava.parser;

import java.nio.file.Path;
import java.util.List;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;

/** 扫描仓库根目录，产出全部 {@code .java} 源文件的相对路径。 */
public final class SourceScanner {

    private static final java.util.Set<String> SKIPPED_DIRS =
            java.util.Set.of("target", "build", ".git", ".idea", "node_modules", "out", "bin");

    public SourceScanner() {
    }

    public List<Path> scan(Path repoRoot) {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(repoRoot)) {
            return List.of();
        }
        try {
            Files.walkFileTree(repoRoot, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(repoRoot) && SKIPPED_DIRS.contains(dir.getFileName().toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (file.getFileName().toString().endsWith(".java")) {
                                result.add(repoRoot.relativize(file));
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan " + repoRoot, e);
        }
        return result;
    }
}
