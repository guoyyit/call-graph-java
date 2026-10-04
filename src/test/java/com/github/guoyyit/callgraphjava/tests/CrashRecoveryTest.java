package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.Application;
import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.service.CallGraphService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashRecoveryTest {
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

    private ConfigurableApplicationContext startService(Path repo, Path state) {
        SpringApplication application = new SpringApplication(Application.class);
        return application.run(
                "--server.port=0",
                "--callgraph.repo-path=" + repo,
                "--callgraph.state-path=" + state,
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN");
    }

    @Test
    void crashMidBuildIsNotAppliedAfterRestart(@TempDir Path tempDir) throws Exception {
        Path repo = repoWithFiles(tempDir.resolve("repo"), 60);
        Path state = tempDir.resolve("state");

        CallGraphService initial = new CallGraphService(repo, state);
        initial.build(repo, ProgressToken.neverCancelled());
        GraphSnapshot committed = initial.snapshot();
        assertTrue(committed.revision() > 0);

        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                CrashHarness.class.getName(),
                repo.toString(), state.toString(), "3")
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(120, TimeUnit.SECONDS);
        assertTrue(exited, "crash harness must terminate");
        assertEquals(42, process.exitValue(), "harness must be force-terminated mid-build");

        List<String> journalLines = Files.readAllLines(state.resolve("journal.log"),
                StandardCharsets.UTF_8).stream().filter(line -> !line.isBlank()).toList();
        assertTrue(journalLines.stream().anyMatch(line -> line.contains("BEGIN")),
                "crashed build must leave its BEGIN record behind");
        assertTrue(journalLines.stream().noneMatch(line -> line.contains("COMMIT")),
                "crashed build must not have a COMMIT record");

        ConfigurableApplicationContext context = startService(repo, state);
        try {
            String base = "http://localhost:"
                    + context.getEnvironment().getProperty("local.server.port") + "/api/callgraph";
            RestTemplate http = new RestTemplate();

            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = http.getForObject(base + "/snapshot", Map.class);
            assertEquals(committed.revision(), ((Number) snapshot.get("revision")).longValue(),
                    "restart must recover the last committed state, not the crashed change");
            assertEquals(committed.edges().size(), ((Number) snapshot.get("edgeCount")).intValue());
            assertEquals(committed.methods().size(),
                    ((Number) snapshot.get("methodCount")).intValue());
            assertEquals(committed.types().size(), ((Number) snapshot.get("typeCount")).intValue());

            Path extra = repo.resolve("Extra.java");
            Files.writeString(extra, "public class Extra { public void run() { } }",
                    StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> update = http.postForObject(base + "/update", null, Map.class);
            assertEquals(committed.revision() + 1, ((Number) update.get("revision")).longValue(),
                    "revision must continue from the committed state without disorder");
        } finally {
            context.close();
        }

        List<String> journalAfter = Files.readAllLines(state.resolve("journal.log"),
                StandardCharsets.UTF_8).stream().filter(line -> !line.isBlank()).toList();
        assertEquals(List.of(), journalAfter,
                "incomplete crash records must be cleaned after recovery");
    }
}
