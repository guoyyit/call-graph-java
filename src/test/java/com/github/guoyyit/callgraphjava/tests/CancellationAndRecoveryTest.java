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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void cancelJustBeforeCommitLeavesNoRecoverableTrace(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");
        ProgressToken token = new ProgressToken();
        CallGraphService service = new CallGraphService(repo, state) {
            @Override
            protected void beforeCommit(ProgressToken commitToken) {
                // cancel arrives at the last possible moment before commit
                commitToken.cancel();
                super.beforeCommit(commitToken);
            }
        };

        assertThrows(CancellationException.class, () -> service.build(repo, token));
        assertEquals(0, service.snapshot().revision());
        assertJournalHasNoEntries(state);
        try (Stream<Path> files = Files.list(state)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp")),
                    "no temporary snapshot may remain");
        }

        CallGraphService restarted = new CallGraphService(repo, state);
        restarted.recover();
        assertEquals(0, restarted.snapshot().revision(),
                "a change cancelled before commit must not reappear after restart");
        assertTrue(restarted.snapshot().edges().isEmpty());
    }

    @Test
    void cancelAfterCommitKeepsCommittedState(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");
        CallGraphService service = new CallGraphService(repo, state);
        ProgressToken token = new ProgressToken();
        service.build(repo, token);
        long revision = service.snapshot().revision();
        int edgeCount = service.snapshot().edges().size();

        // cancellation that arrives after the commit completed must not roll
        // the committed state back or pollute it
        token.cancel();

        assertEquals(revision, service.snapshot().revision());
        assertEquals(edgeCount, service.snapshot().edges().size());

        CallGraphService restarted = new CallGraphService(repo, state);
        restarted.recover();
        assertEquals(revision, restarted.snapshot().revision());
        assertEquals(edgeCount, restarted.snapshot().edges().size());
    }

    @Test
    void cancelledUpdateLeavesNoJournalTrace(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");
        CallGraphService service = new CallGraphService(repo, state);
        service.build(repo, ProgressToken.neverCancelled());
        long revision = service.snapshot().revision();

        Path square = repo.resolve("pkgp/Square.java");
        Files.writeString(square, Files.readString(square).replace("return side * side;",
                "return side;"));
        ProgressToken token = new ProgressToken();
        token.cancel();
        assertThrows(CancellationException.class, () -> service.update(token));

        assertEquals(revision, service.snapshot().revision());
        assertJournalHasNoEntries(state);

        CallGraphService restarted = new CallGraphService(repo, state);
        restarted.recover();
        assertEquals(revision, restarted.snapshot().revision(),
                "cancelled update must not advance the revision after restart");
    }

    @Test
    void revisionIncreasesMonotonicallyAcrossRestart(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");
        CallGraphService service = new CallGraphService(repo, state);
        service.build(repo, ProgressToken.neverCancelled());
        long first = service.snapshot().revision();

        Path square = repo.resolve("pkgp/Square.java");
        Files.writeString(square, Files.readString(square).replace("return side * side;",
                "return side;"));
        long second = service.update(ProgressToken.neverCancelled()).revision();
        assertTrue(second > first);

        CallGraphService restarted = new CallGraphService(repo, state);
        restarted.recover();
        assertEquals(second, restarted.snapshot().revision(),
                "restart must restore the latest committed revision");

        Files.writeString(square, Files.readString(square).replace("return side;",
                "return side + side;"));
        long third = restarted.update(ProgressToken.neverCancelled()).revision();
        assertTrue(third > second,
                "revision must keep increasing after restart, never restart from 1");

        SnapshotPersistence persistence = new SnapshotPersistence();
        GraphSnapshot persisted = persistence.read(state.resolve("snapshot.json"));
        assertEquals(third, persisted.revision(),
                "persisted snapshot must never be overwritten by a lower revision");
    }

    private void assertJournalHasNoEntries(Path state) throws IOException {
        Path journalFile = state.resolve("journal.log");
        if (!Files.exists(journalFile)) {
            return;
        }
        List<String> lines = Files.readAllLines(journalFile).stream()
                .filter(line -> !line.isBlank())
                .toList();
        assertTrue(lines.isEmpty(), "cancelled change must leave no journal records: " + lines);
    }

    @Test
    void staleTemporarySnapshotIsRemovedOnRecovery(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");
        Files.createDirectories(state);
        Files.writeString(state.resolve("snapshot.json.tmp"), "{\"revision\": 99,");

        CallGraphService service = new CallGraphService(repo, state);
        service.recover();

        assertFalse(Files.exists(state.resolve("snapshot.json.tmp")),
                "recovery must remove leftover temporary snapshot files");
        assertEquals(0, service.snapshot().revision());
    }

    @Test
    void tornJournalTailIsIgnoredOnRecovery(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");
        CallGraphService service = new CallGraphService(repo, state);
        service.build(repo, ProgressToken.neverCancelled());
        long revision = service.snapshot().revision();
        int edgeCount = service.snapshot().edges().size();

        // simulate a crash that left a half-written record at the journal tail
        Files.writeString(state.resolve("journal.log"),
                "{\"op\":\"BEGIN\",\"revision\":99,\"files\":[\"pkgp/Squ",
                java.nio.file.StandardOpenOption.APPEND);

        CallGraphService restarted = new CallGraphService(repo, state);
        restarted.recover();
        assertEquals(revision, restarted.snapshot().revision(),
                "a torn tail record must not break or pollute recovery");
        assertEquals(edgeCount, restarted.snapshot().edges().size());
    }
}
