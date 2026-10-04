package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.service.CallGraphService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simulates a crash in the middle of an uncommitted build by running the
 * build in a separate JVM that halts itself (no shutdown hooks, like
 * {@code kill -9}) after the journal BEGIN record is durable. The restarted
 * service must recover only the last committed state and must not be
 * confused by the leftover records.
 */
class CrashRecoveryTest {
    private void generateRepo(Path repo, int files) throws IOException {
        Files.createDirectories(repo);
        for (int index = 0; index < files; index++) {
            int next = (index + 1) % files;
            Files.writeString(repo.resolve("Gen" + index + ".java"),
                    "public class Gen" + index + " { public void go(Gen" + next
                            + " other) { other.go(new Gen" + next + "()); } }");
        }
    }

    private int runCrashProcess(Path repo, Path state) throws IOException, InterruptedException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaBin, "-cp",
                System.getProperty("java.class.path"),
                CrashSimulatingBuild.class.getName(),
                repo.toAbsolutePath().toString(), state.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS),
                "crash-simulating process did not exit in time");
        return process.exitValue();
    }

    @Test
    void crashMidBuildIsNotAppliedAndDoesNotCorruptRevisions(@TempDir Path tempDir)
            throws Exception {
        Path repo = tempDir.resolve("repo");
        generateRepo(repo, 40);
        Path state = tempDir.resolve("state");

        CallGraphService service = new CallGraphService(repo, state);
        service.build(repo, ProgressToken.neverCancelled());
        long committedRevision = service.snapshot().revision();
        int committedEdges = service.snapshot().edges().size();
        assertEquals(1, committedRevision);

        int exit = runCrashProcess(repo, state);
        assertEquals(1, exit, "crash-simulating process must halt abruptly");

        List<String> journalAfterCrash = Files.readAllLines(state.resolve("journal.log"))
                .stream().filter(line -> !line.isBlank()).toList();
        assertTrue(journalAfterCrash.stream().anyMatch(line -> line.contains("BEGIN")),
                "the crashed build must have left a durable BEGIN record: " + journalAfterCrash);

        CallGraphService restarted = new CallGraphService(repo, state);
        restarted.recover();
        assertEquals(committedRevision, restarted.snapshot().revision(),
                "uncommitted change of the crashed process must not be applied");
        assertEquals(committedEdges, restarted.snapshot().edges().size(),
                "recovered graph must equal the last committed state");

        List<String> journalAfterRecovery = Files.readAllLines(state.resolve("journal.log"))
                .stream().filter(line -> !line.isBlank()).toList();
        assertTrue(journalAfterRecovery.isEmpty(),
                "recovery must clean up records of the uncommitted crashed change: "
                        + journalAfterRecovery);
        try (Stream<Path> files = Files.list(state)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp")),
                    "no temporary products may remain after recovery");
        }

        Files.writeString(repo.resolve("Gen0.java"),
                "public class Gen0 { public void go(Gen1 other) { other.go(new Gen1()); } "
                        + "public void extra(Gen1 other) { other.go(new Gen1()); } }");
        long nextRevision = restarted.update(ProgressToken.neverCancelled()).revision();
        assertTrue(nextRevision > committedRevision,
                "revisions must keep increasing after the crash, got " + nextRevision);

        CallGraphService restartedAgain = new CallGraphService(repo, state);
        restartedAgain.recover();
        assertEquals(nextRevision, restartedAgain.snapshot().revision(),
                "post-crash commits must survive another restart");
    }
}
