package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.service.CallGraphService;
import com.github.guoyyit.callgraphjava.store.GraphStore;
import com.github.guoyyit.callgraphjava.store.JournalStore;
import com.github.guoyyit.callgraphjava.store.SnapshotPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTimingTest {
    private Path repoWithFiles(Path repo, int count) throws IOException {
        Files.createDirectories(repo);
        for (int index = 0; index < count; index++) {
            int next = (index + 1) % count;
            Files.writeString(repo.resolve("Big" + index + ".java"),
                    "public class Big" + index + " { public void go(Big" + next
                            + " other) { other.go(new Big" + next + "()); } }",
                    StandardCharsets.UTF_8);
        }
        return repo;
    }

    private CallGraphService restart(Path repo, Path state) {
        CallGraphService restarted = new CallGraphService(repo, state);
        restarted.recover();
        return restarted;
    }

    private void assertNoLeftoverArtifacts(Path state) throws IOException {
        try (Stream<Path> files = Files.list(state)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp")),
                    "no temporary snapshot may remain");
        }
        Path journal = state.resolve("journal.log");
        if (Files.exists(journal)) {
            List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8)
                    .stream().filter(line -> !line.isBlank()).toList();
            assertEquals(List.of(), lines, "cancelled change must not leave journal records");
        }
    }

    @Test
    void cancelBeforeStartLeavesNoRecoverableRecord(@TempDir Path tempDir) throws Exception {
        Path repo = repoWithFiles(tempDir.resolve("repo"), 20);
        Path state = tempDir.resolve("state");
        CallGraphService service = new CallGraphService(repo, state);
        service.build(repo, ProgressToken.neverCancelled());
        GraphSnapshot committed = service.snapshot();

        ProgressToken token = new ProgressToken();
        token.cancel();
        assertThrows(CancellationException.class, () -> service.build(repo, token));

        assertEquals(committed.revision(), service.snapshot().revision());
        assertNoLeftoverArtifacts(state);

        CallGraphService restarted = restart(repo, state);
        assertEquals(committed.revision(), restarted.snapshot().revision());
        assertEquals(committed.edges().size(), restarted.snapshot().edges().size());
        assertEquals(committed.methods().size(), restarted.snapshot().methods().size());
    }

    @Test
    void cancelMidBuildLeavesNoRecoverableRecord(@TempDir Path tempDir) throws Exception {
        Path repo = repoWithFiles(tempDir.resolve("repo"), 400);
        Path state = tempDir.resolve("state");
        CallGraphService service = new CallGraphService(repo, state);
        service.build(repo, ProgressToken.neverCancelled());
        GraphSnapshot committed = service.snapshot();

        ProgressToken token = new ProgressToken();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> service.build(repo, token));
        token.cancel();
        assertThrows(Exception.class, () -> future.get(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(committed.revision(), service.snapshot().revision());
        assertNoLeftoverArtifacts(state);

        CallGraphService restarted = restart(repo, state);
        assertEquals(committed.revision(), restarted.snapshot().revision());
        assertEquals(committed.edges().size(), restarted.snapshot().edges().size());
    }

    @Test
    void cancelJustBeforeCommitLeavesNoRecoverableRecord(@TempDir Path tempDir) throws Exception {
        Path repo = repoWithFiles(tempDir.resolve("repo"), 20);
        Path state = tempDir.resolve("state");
        CallGraphService service = new CallGraphService(repo, state);
        service.build(repo, ProgressToken.neverCancelled());
        GraphSnapshot committed = service.snapshot();

        ProgressToken token = new ProgressToken();
        CallGraphService cancellingService = new CallGraphService(repo, state,
                new GraphStore(), new SnapshotPersistence(), new JournalStore(state)) {
            @Override
            protected void beforeCommit(GraphSnapshot pending, ProgressToken activeToken) {
                activeToken.cancel();
            }
        };
        assertThrows(CancellationException.class, () -> cancellingService.build(repo, token));

        assertNoLeftoverArtifacts(state);
        CallGraphService restarted = restart(repo, state);
        assertEquals(committed.revision(), restarted.snapshot().revision());
        assertEquals(committed.edges().size(), restarted.snapshot().edges().size());
        assertEquals(committed.types().size(), restarted.snapshot().types().size());
    }

    @Test
    void cancelAfterCommitKeepsCommittedState(@TempDir Path tempDir) throws Exception {
        Path repo = repoWithFiles(tempDir.resolve("repo"), 20);
        Path state = tempDir.resolve("state");
        CallGraphService service = new CallGraphService(repo, state);
        ProgressToken token = new ProgressToken();
        service.build(repo, token);
        GraphSnapshot committed = service.snapshot();
        assertTrue(committed.revision() > 0);

        token.cancel();

        assertEquals(committed.revision(), service.snapshot().revision());
        CallGraphService restarted = restart(repo, state);
        assertEquals(committed.revision(), restarted.snapshot().revision());
        assertEquals(committed.edges().size(), restarted.snapshot().edges().size());
    }
}
