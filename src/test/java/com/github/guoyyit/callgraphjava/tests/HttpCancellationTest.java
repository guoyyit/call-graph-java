package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.Application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the HTTP cancellation capability: the build identifier is
 * available before the build completes, cancelling a running build aborts it,
 * and cancelling a finished build yields a distinguishable result.
 */
class HttpCancellationTest {
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

    private void generateRepo(Path repo, int files) throws IOException {
        Files.createDirectories(repo);
        for (int index = 0; index < files; index++) {
            int next = (index + 1) % files;
            Files.writeString(repo.resolve("Gen" + index + ".java"),
                    "public class Gen" + index + " { public void go(Gen" + next
                            + " other) { other.go(new Gen" + next + "()); } }");
        }
    }

    private Map<?, ?> awaitBuild(String base, String buildId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            Map<?, ?> status = http.getForObject(base + "/builds/" + buildId, Map.class);
            if (!"running".equals(status.get("status"))) {
                return status;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("build did not reach a terminal state in time");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshot(String base) {
        return http.getForObject(base + "/snapshot", Map.class);
    }

    @Test
    void longBuildCanBeCancelledWhileRunning(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        generateRepo(repo, 600);
        Path state = tempDir.resolve("state");

        ConfigurableApplicationContext context = start(repo, state);
        try {
            String base = baseUrl(context);

            Map<?, ?> firstBuild = http.postForObject(base + "/build", null, Map.class);
            Map<?, ?> firstResult = awaitBuild(base, (String) firstBuild.get("buildId"));
            assertEquals("completed", firstResult.get("status"));
            Map<String, Object> committed = snapshot(base);
            assertEquals(1L, ((Number) committed.get("revision")).longValue());

            // The build identifier is returned before the build completes.
            Map<?, ?> started = http.postForObject(base + "/build", null, Map.class);
            String buildId = (String) started.get("buildId");
            assertNotNull(buildId);
            assertEquals("running", started.get("status"));

            // Queries stay available while the build runs and still serve the
            // last committed snapshot.
            assertEquals(committed, snapshot(base));

            ResponseEntity<Map> cancel = http.postForEntity(
                    base + "/builds/" + buildId + "/cancel", null, Map.class);
            assertEquals(HttpStatus.OK, cancel.getStatusCode());
            assertEquals("cancelRequested", cancel.getBody().get("status"));

            Map<?, ?> result = awaitBuild(base, buildId);
            assertEquals("cancelled", result.get("status"),
                    "clients must be able to observe that the build was cancelled");

            Map<String, Object> after = snapshot(base);
            assertEquals(committed, after,
                    "a cancelled build must leave the previous complete snapshot in place");
        } finally {
            context.close();
        }
    }

    @Test
    void cancellingCompletedBuildReturnsDistinguishableResult(@TempDir Path tempDir)
            throws IOException {
        Path repo = tempDir.resolve("repo");
        generateRepo(repo, 20);
        Path state = tempDir.resolve("state");

        ConfigurableApplicationContext context = start(repo, state);
        try {
            String base = baseUrl(context);
            Map<?, ?> started = http.postForObject(base + "/build", null, Map.class);
            String buildId = (String) started.get("buildId");
            Map<?, ?> result = awaitBuild(base, buildId);
            assertEquals("completed", result.get("status"));
            long revision = ((Number) snapshot(base).get("revision")).longValue();

            HttpClientErrorException error = assertThrows(HttpClientErrorException.class,
                    () -> http.postForEntity(base + "/builds/" + buildId + "/cancel", null,
                            Map.class));
            assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
            assertTrue(error.getResponseBodyAsString().contains("alreadyCompleted"),
                    "cancelling a finished build must return a distinguishable result: "
                            + error.getResponseBodyAsString());

            assertEquals(revision, ((Number) snapshot(base).get("revision")).longValue(),
                    "a late cancellation must not roll back the committed build");

            HttpClientErrorException unknown = assertThrows(HttpClientErrorException.class,
                    () -> http.postForEntity(base + "/builds/no-such-build/cancel", null,
                            Map.class));
            assertEquals(HttpStatus.NOT_FOUND, unknown.getStatusCode());
        } finally {
            context.close();
        }
    }
}
