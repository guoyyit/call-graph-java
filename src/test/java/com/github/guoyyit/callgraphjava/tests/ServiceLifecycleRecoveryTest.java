package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.Application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceLifecycleRecoveryTest {
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

    private ConfigurableApplicationContext startService(Path repo, Path state) {
        SpringApplication application = new SpringApplication(Application.class);
        return application.run(
                "--server.port=0",
                "--callgraph.repo-path=" + repo,
                "--callgraph.state-path=" + state,
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN");
    }

    private String baseUrl(ConfigurableApplicationContext context) {
        return "http://localhost:"
                + context.getEnvironment().getProperty("local.server.port") + "/api/callgraph";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String url) {
        return new org.springframework.web.client.RestTemplate().getForObject(url, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String url) {
        return new org.springframework.web.client.RestTemplate().postForObject(url, null, Map.class);
    }

    private Map<String, Object> awaitBuild(String base, String buildId) throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            Map<String, Object> status = get(base + "/builds/" + buildId);
            if (!"running".equals(status.get("status"))) {
                return status;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("build did not finish in time");
    }

    private Map<String, Object> buildAndAwait(String base) throws InterruptedException {
        Map<String, Object> started = post(base + "/build");
        Map<String, Object> finished = awaitBuild(base, (String) started.get("buildId"));
        assertEquals("completed", finished.get("status"), () -> "build failed: " + finished);
        return finished;
    }

    @Test
    void restartRestoresCommittedStateWithoutRebuild(@TempDir Path tempDir) throws Exception {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");

        Map<String, Object> beforeRestart;
        List<?> calleesBefore;
        ConfigurableApplicationContext first = startService(repo, state);
        try {
            String base = baseUrl(first);
            buildAndAwait(base);
            beforeRestart = get(base + "/snapshot");
            calleesBefore = new org.springframework.web.client.RestTemplate().getForObject(
                    base + "/methods/pkgq.Canvas/makeShape", List.class);
            assertTrue(((Number) beforeRestart.get("revision")).longValue() > 0);
            assertTrue(((Number) beforeRestart.get("edgeCount")).longValue() > 0);
            assertTrue(calleesBefore != null && !calleesBefore.isEmpty());
        } finally {
            first.close();
        }

        FileTime snapshotModified = Files.getLastModifiedTime(state.resolve("snapshot.json"));

        ConfigurableApplicationContext second = startService(repo, state);
        try {
            String base = baseUrl(second);
            Map<String, Object> afterRestart = get(base + "/snapshot");
            assertEquals(beforeRestart.get("revision"), afterRestart.get("revision"),
                    "revision must survive restart");
            assertEquals(beforeRestart.get("typeCount"), afterRestart.get("typeCount"));
            assertEquals(beforeRestart.get("methodCount"), afterRestart.get("methodCount"));
            assertEquals(beforeRestart.get("edgeCount"), afterRestart.get("edgeCount"));
            assertEquals(beforeRestart.get("fileCount"), afterRestart.get("fileCount"));

            List<?> calleesAfter = new org.springframework.web.client.RestTemplate().getForObject(
                    base + "/methods/pkgq.Canvas/makeShape", List.class);
            assertEquals(calleesBefore, calleesAfter,
                    "query results must be identical after restart without any rebuild");

            assertEquals(snapshotModified, Files.getLastModifiedTime(state.resolve("snapshot.json")),
                    "startup must not rewrite the snapshot when nothing is pending");
        } finally {
            second.close();
        }
    }

    @Test
    void revisionStaysMonotonicAcrossRestarts(@TempDir Path tempDir) throws Exception {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");

        long revisionAfterBuild;
        ConfigurableApplicationContext first = startService(repo, state);
        try {
            String base = baseUrl(first);
            buildAndAwait(base);
            revisionAfterBuild = ((Number) get(base + "/snapshot").get("revision")).longValue();

            Path square = repo.resolve("pkgp/Square.java");
            Files.writeString(square, Files.readString(square, StandardCharsets.UTF_8)
                    .replace("return side * side;", "return side;"), StandardCharsets.UTF_8);
            Map<String, Object> update = post(base + "/update");
            assertEquals(revisionAfterBuild + 1,
                    ((Number) update.get("revision")).longValue());
        } finally {
            first.close();
        }

        long restoredRevision;
        ConfigurableApplicationContext second = startService(repo, state);
        try {
            String base = baseUrl(second);
            restoredRevision = ((Number) get(base + "/snapshot").get("revision")).longValue();
            assertEquals(revisionAfterBuild + 1, restoredRevision,
                    "restart must restore the latest committed revision");

            Path circle = repo.resolve("pkgp/Circle.java");
            Files.writeString(circle, Files.readString(circle, StandardCharsets.UTF_8)
                    .replace("3.14 * radius * radius", "3.1415 * radius * radius"),
                    StandardCharsets.UTF_8);
            Map<String, Object> update = post(base + "/update");
            long nextRevision = ((Number) update.get("revision")).longValue();
            assertEquals(restoredRevision + 1, nextRevision,
                    "revision must keep increasing after restart, never restart from 1");
        } finally {
            second.close();
        }

        ConfigurableApplicationContext third = startService(repo, state);
        try {
            Map<String, Object> snapshot = get(baseUrl(third) + "/snapshot");
            assertEquals(revisionAfterBuild + 2,
                    ((Number) snapshot.get("revision")).longValue(),
                    "persisted higher revision must not be overwritten by a lower one");
        } finally {
            third.close();
        }
    }
}
