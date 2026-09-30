package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.GraphBuilder;
import com.github.guoyyit.callgraphjava.build.IncrementalUpdater;
import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.Edge;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IncrementalConsistencyTest {
    private Path copyFixture(String name, Path target) throws IOException {
        Path source = Path.of("src/test/resources/fixtures", name);
        copyRecursively(source, target);
        return target;
    }

    private void copyRecursively(Path source, Path target) throws IOException {
        Files.walk(source).forEach(from -> {
            try {
                Path to = target.resolve(source.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Set<String> edgeKeys(GraphSnapshot snapshot) {
        return snapshot.edges().stream().map(Edge::key).collect(Collectors.toSet());
    }

    @Test
    void incrementalMatchesFullRebuildAndDropsStaleEdges(@TempDir Path tempDir) throws IOException {
        Path repo = copyFixture("shapes", tempDir.resolve("repo"));
        GraphBuilder builder = new GraphBuilder();
        GraphSnapshot full = builder.buildFull(repo, ProgressToken.neverCancelled());

        Path square = repo.resolve("pkgp/Square.java");
        Files.writeString(square, Files.readString(square, StandardCharsets.UTF_8)
                .replace("return side * side;", "return side;"),
                StandardCharsets.UTF_8);

        GraphSnapshot rebuilt = builder.buildFull(repo, ProgressToken.neverCancelled());
        GraphSnapshot incremental = new IncrementalUpdater().update(full, repo,
                java.util.List.of("pkgp/Square.java"), ProgressToken.neverCancelled());

        assertEquals(edgeKeys(rebuilt), edgeKeys(incremental),
                "incremental edge set must equal a full rebuild");
        assertEquals(rebuilt.types().size(), incremental.types().size());
        assertEquals(rebuilt.methods().keySet(), incremental.methods().keySet());
    }

    @Test
    void deletingTypeRemovesStaleVirtualEdges(@TempDir Path tempDir) throws IOException {
        Path repo = copyFixture("shapes", tempDir.resolve("repo2"));
        GraphBuilder builder = new GraphBuilder();
        GraphSnapshot full = builder.buildFull(repo, ProgressToken.neverCancelled());

        Files.delete(repo.resolve("pkgp/Square.java"));

        GraphSnapshot rebuilt = builder.buildFull(repo, ProgressToken.neverCancelled());
        GraphSnapshot incremental = new IncrementalUpdater().update(full, repo,
                java.util.List.of("pkgp/Square.java"), ProgressToken.neverCancelled());

        assertEquals(edgeKeys(rebuilt), edgeKeys(incremental));
        assertFalse(incremental.types().containsKey("pkgp.Square"));
        assertFalse(edgeKeys(incremental).stream().anyMatch(key -> key.contains("pkgp.Square")));
    }
}
