package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.Application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpBuildCancellationTest {
    private final RestTemplate http = new RestTemplate();

    private Path largeRepo(Path repo, int count) throws IOException {
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

    private String baseUrl(ConfigurableApplicationContext context) {
        return "http://localhost:"
                + context.getEnvironment().getProperty("local.server.port") + "/api/callgraph";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String url) {
        return http.getForObject(url, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String url) {
        return http.postForObject(url, null, Map.class);
    }

    private Map<String, Object> awaitStatus(String base, String buildId, String wanted)
            throws InterruptedException {
        for (int attempt = 0; attempt < 600; attempt++) {
            Map<String, Object> status = get(base + "/builds/" + buildId);
            if (wanted.equals(status.get("status"))) {
                return status;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("build " + buildId + " did not reach status " + wanted);
    }

    @Test
    void longBuildCanBeCancelledWhileRunning(@TempDir Path tempDir) throws Exception {
        Path repo = largeRepo(tempDir.resolve("repo"), 800);
        Path state = tempDir.resolve("state");
        ConfigurableApplicationContext context = startService(repo, state);
        try {
            String base = baseUrl(context);
            Map<String, Object> firstBuild = post(base + "/build");
            awaitStatus(base, (String) firstBuild.get("buildId"), "completed");
            Map<String, Object> committed = get(base + "/snapshot");
            assertTrue(((Number) committed.get("revision")).longValue() > 0);

            Map<String, Object> started = post(base + "/build");
            String buildId = (String) started.get("buildId");
            assertNotNull(buildId, "client must receive a build id before the build finishes");
            assertEquals("running", get(base + "/builds/" + buildId).get("status"));

            Map<String, Object> snapshotDuringBuild = get(base + "/snapshot");
            assertEquals(committed.get("revision"), snapshotDuringBuild.get("revision"),
                    "queries must stay available and see the last committed snapshot during build");

            Map<String, Object> cancel = post(base + "/builds/" + buildId + "/cancel");
            assertEquals("cancelRequested", cancel.get("status"));

            Map<String, Object> cancelled = awaitStatus(base, buildId, "cancelled");
            assertEquals("cancelled", cancelled.get("status"));

            Map<String, Object> afterCancel = get(base + "/snapshot");
            assertEquals(committed.get("revision"), afterCancel.get("revision"),
                    "cancelled build must not change the visible snapshot");
            assertEquals(committed.get("edgeCount"), afterCancel.get("edgeCount"));
            assertEquals(committed.get("methodCount"), afterCancel.get("methodCount"));
        } finally {
            context.close();
        }
    }

    @Test
    void cancellingCompletedBuildReturnsDistinguishableResult(@TempDir Path tempDir)
            throws Exception {
        Path repo = largeRepo(tempDir.resolve("repo"), 10);
        Path state = tempDir.resolve("state");
        ConfigurableApplicationContext context = startService(repo, state);
        try {
            String base = baseUrl(context);
            Map<String, Object> started = post(base + "/build");
            String buildId = (String) started.get("buildId");
            awaitStatus(base, buildId, "completed");

            HttpClientErrorException error = assertThrows(HttpClientErrorException.class,
                    () -> post(base + "/builds/" + buildId + "/cancel"));
            assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
            assertTrue(error.getResponseBodyAsString().contains("alreadyCompleted"),
                    "completed build cancellation must be distinguishable");

            Map<String, Object> status = get(base + "/builds/" + buildId);
            assertEquals("completed", status.get("status"));
            assertNotNull(status.get("revision"),
                    "completed build status must expose the committed revision");
        } finally {
            context.close();
        }
    }

    @Test
    void cancellingUnknownBuildReturnsNotFound(@TempDir Path tempDir) throws Exception {
        Path repo = largeRepo(tempDir.resolve("repo"), 5);
        Path state = tempDir.resolve("state");
        ConfigurableApplicationContext context = startService(repo, state);
        try {
            String base = baseUrl(context);
            HttpClientErrorException error = assertThrows(HttpClientErrorException.class,
                    () -> http.postForEntity(base + "/builds/no-such-id/cancel", null,
                            String.class));
            assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        } finally {
            context.close();
        }
    }
}
