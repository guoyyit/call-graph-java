package com.github.guoyyit.callgraphjava.service;

import com.github.guoyyit.callgraphjava.build.ProgressToken;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BuildManager {
    public enum BuildStatus {
        RUNNING, CANCELLED, COMPLETED, FAILED
    }

    public enum CancelResult {
        CANCEL_REQUESTED, NOT_FOUND, ALREADY_COMPLETED, ALREADY_CANCELLED, ALREADY_FAILED
    }

    public record BuildInfo(String buildId, BuildStatus status, Long revision, String error) {
    }

    private static final long RETENTION_MILLIS = 15 * 60 * 1000L;

    private static final class BuildHandle {
        private final String id;
        private final ProgressToken token = new ProgressToken();
        private volatile BuildStatus status = BuildStatus.RUNNING;
        private volatile Long revision;
        private volatile String error;
        private volatile long finishedAtMillis;

        private BuildHandle(String id) {
            this.id = id;
        }
    }

    private final CallGraphService service;
    private final ExecutorService executor;
    private final Map<String, BuildHandle> builds = new ConcurrentHashMap<>();

    public BuildManager(CallGraphService service) {
        this(service, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "callgraph-build");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public BuildManager(CallGraphService service, ExecutorService executor) {
        this.service = service;
        this.executor = executor;
    }

    public String startBuild() {
        evictExpired();
        BuildHandle handle = new BuildHandle(UUID.randomUUID().toString());
        builds.put(handle.id, handle);
        executor.execute(() -> runBuild(handle));
        return handle.id;
    }

    public CancelResult cancel(String buildId) {
        BuildHandle handle = builds.get(buildId);
        if (handle == null) {
            return CancelResult.NOT_FOUND;
        }
        if (handle.status == BuildStatus.RUNNING) {
            handle.token.cancel();
            return CancelResult.CANCEL_REQUESTED;
        }
        return switch (handle.status) {
            case COMPLETED -> CancelResult.ALREADY_COMPLETED;
            case CANCELLED -> CancelResult.ALREADY_CANCELLED;
            case FAILED -> CancelResult.ALREADY_FAILED;
            case RUNNING -> CancelResult.CANCEL_REQUESTED;
        };
    }

    public BuildInfo info(String buildId) {
        BuildHandle handle = builds.get(buildId);
        if (handle == null) {
            return null;
        }
        return new BuildInfo(handle.id, handle.status, handle.revision, handle.error);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void runBuild(BuildHandle handle) {
        try {
            service.build(service.repositoryRoot(), handle.token);
            handle.revision = service.snapshot().revision();
            handle.status = BuildStatus.COMPLETED;
        } catch (CancellationException e) {
            handle.status = BuildStatus.CANCELLED;
        } catch (RuntimeException e) {
            handle.status = BuildStatus.FAILED;
            handle.error = e.getMessage();
        } finally {
            handle.finishedAtMillis = System.currentTimeMillis();
        }
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        builds.values().removeIf(handle -> handle.status != BuildStatus.RUNNING
                && now - handle.finishedAtMillis > RETENTION_MILLIS);
    }
}
