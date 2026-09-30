package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.Edge;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.service.CallGraphService;
import com.github.guoyyit.callgraphjava.store.GraphStore;
import com.github.guoyyit.callgraphjava.store.JournalStore;
import com.github.guoyyit.callgraphjava.store.SnapshotPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationAndRecoveryTest {
    private void copyFixture(String name, Path target) throws IOException {
        Path source = Path.of("src/test/resources/fixtures", name);
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(from -> {
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
    }

    @Test
    void cancelledBuildDoesNotCommitOrCorruptState(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");
        CallGraphService service = new CallGraphService(repo, state);
        ProgressToken token = new ProgressToken();
        token.cancel();

        assertThrows(CancellationException.class,
                () -> service.build(repo, token));
        assertEquals(0, service.snapshot().revision());
        try (Stream<Path> files = Files.list(state)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void cancelMidBuildKeepsPreviousSnapshot(@TempDir Path tempDir) throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        for (int index = 0; index < 400; index++) {
            Files.writeString(repo.resolve("Big" + index + ".java"),
                    "public class Big" + index + " { void go(Big" + ((index + 1) % 400)
                            + " other) { other.go(new Big" + ((index + 1) % 400) + "()); } }",
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        Path state = tempDir.resolve("state");
        CallGraphService service = new CallGraphService(repo, state);
        service.build(repo, ProgressToken.neverCancelled());
        long revision = service.snapshot().revision();
        int edgeCount = service.snapshot().edges().size();

        ProgressToken token = new ProgressToken();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> service.build(repo, token));
        token.cancel();
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(revision, service.snapshot().revision());
        assertEquals(edgeCount, service.snapshot().edges().size());
    }

    @Test
    void recoverReplaysCommittedJournalExactlyOnce(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");
        GraphStore store = new GraphStore();
        SnapshotPersistence persistence = new SnapshotPersistence();
        JournalStore journal = new JournalStore(state);
        CallGraphService service = new CallGraphService(repo, state, store, persistence, journal);

        service.build(repo, ProgressToken.neverCancelled());
        long afterBuild = service.snapshot().revision();
        int edgeCountAfterBuild = service.snapshot().edges().size();

        journal.append(afterBuild + 1, java.util.List.of("pkgp/Square.java"));

        CallGraphService restartedService = new CallGraphService(repo, state);
        restartedService.recover();
        GraphSnapshot afterRecovery = restartedService.snapshot();
        assertEquals(afterBuild + 1, afterRecovery.revision());

        CallGraphService secondRestart = new CallGraphService(repo, state);
        secondRestart.recover();
        assertEquals(afterBuild + 1, secondRestart.snapshot().revision(),
                "journal entries must not be applied twice");

        Optional<Path> tmp = Files.list(state).filter(path -> path.toString().endsWith(".tmp")).findAny();
        assertTrue(tmp.isEmpty(), "no partial snapshot file may remain");
        GraphSnapshot persisted = persistence.read(state.resolve("snapshot.json"));
        assertEquals(edgeCountAfterBuild, persisted.edges().size());
    }
}
