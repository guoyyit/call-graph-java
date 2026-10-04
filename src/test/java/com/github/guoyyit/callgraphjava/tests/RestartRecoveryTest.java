package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.Application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies restart recovery through the real service lifecycle: the Spring
 * application is started and stopped between requests, exactly as a
 * long-running deployment would be.
 */
class RestartRecoveryTest {
    private final RestTemplate http = new RestTemplate();

    private ConfigurableApplicationContext start(Path repo, Path state) {
        return SpringApplication.run(Application.class,
                "--server.port=0",
                "--callgraph.repo-path=" + repo.toAbsolutePath(),
                "--callgraph.state-path=" + state.toAbsolutePath(),
                "--logging.level.root=WARN");
    }

    private String baseUrl(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port")
                + "/api/callgraph";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshot(String base) {
        return http.getForObject(base + "/snapshot", Map.class);
    }

    private void buildAndAwait(String base) {
        Map<?, ?> started = http.postForObject(base + "/build", null, Map.class);
        String buildId = (String) started.get("buildId");
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            Map<?, ?> status = http.getForObject(base + "/builds/" + buildId, Map.class);
            if ("completed".equals(status.get("status"))) {
                return;
            }
            if ("failed".equals(status.get("status")) || "cancelled".equals(status.get("status"))) {
                throw new IllegalStateException("build did not complete: " + status);
            }
            sleep(50);
        }
        throw new IllegalStateException("build did not complete in time");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

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
    void restartRestoresLatestCommittedStateWithoutRebuild(@TempDir Path tempDir)
            throws IOException {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");

        Map<String, Object> before;
        List<?> calleesBefore;
        Map<?, ?> sccBefore;
        ConfigurableApplicationContext first = start(repo, state);
        try {
            String base = baseUrl(first);
            buildAndAwait(base);
            before = snapshot(base);
            calleesBefore = http.getForObject(base + "/methods/pkgq.Canvas/render", List.class);
            sccBefore = http.getForObject(base + "/scc", Map.class);
        } finally {
            first.close();
        }
        assertTrue(((Number) before.get("revision")).longValue() > 0);

        ConfigurableApplicationContext second = start(repo, state);
        try {
            String base = baseUrl(second);
            Map<String, Object> after = snapshot(base);
            assertEquals(before, after,
                    "revision and all counts must survive a restart without any rebuild");
            assertEquals(calleesBefore,
                    http.getForObject(base + "/methods/pkgq.Canvas/render", List.class),
                    "query results must be identical after restart");
            assertEquals(sccBefore, http.getForObject(base + "/scc", Map.class));
        } finally {
            second.close();
        }
    }

    @Test
    void revisionKeepsIncreasingAcrossRestarts(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        copyFixture("shapes", repo);
        Path state = tempDir.resolve("state");

        long firstRevision;
        long secondRevision;
        ConfigurableApplicationContext first = start(repo, state);
        try {
            String base = baseUrl(first);
            buildAndAwait(base);
            firstRevision = ((Number) snapshot(base).get("revision")).longValue();

            Path square = repo.resolve("pkgp/Square.java");
            Files.writeString(square, Files.readString(square).replace("return side * side;",
                    "return side;"));
            Map<?, ?> update = http.postForObject(base + "/update", null, Map.class);
            secondRevision = ((Number) update.get("revision")).longValue();
            assertTrue(secondRevision > firstRevision);
        } finally {
            first.close();
        }

        ConfigurableApplicationContext second = start(repo, state);
        try {
            String base = baseUrl(second);
            assertEquals(secondRevision, ((Number) snapshot(base).get("revision")).longValue(),
                    "restart must recover the latest committed revision");

            Path circle = repo.resolve("pkgp/Circle.java");
            Files.writeString(circle, Files.readString(circle).replace("3.14", "3.1415"));
            Map<?, ?> update = http.postForObject(base + "/update", null, Map.class);
            long thirdRevision = ((Number) update.get("revision")).longValue();
            assertTrue(thirdRevision > secondRevision,
                    "revision must never fall back to a smaller value after restart");
        } finally {
            second.close();
        }

        ConfigurableApplicationContext third = start(repo, state);
        try {
            Map<String, Object> persisted = snapshot(baseUrl(third));
            assertTrue(((Number) persisted.get("revision")).longValue() > secondRevision,
                    "the persisted higher revision must survive yet another restart");
        } finally {
            third.close();
        }
    }
}
