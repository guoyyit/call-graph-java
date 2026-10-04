package com.github.guoyyit.callgraphjava.service;

import com.github.guoyyit.callgraphjava.build.ProgressToken;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs call graph builds asynchronously so that clients receive a build
 * identifier before the build completes and can cancel it while it is still
 * running. Terminal build records are retained so that a cancellation that
 * arrives after completion gets a distinguishable answer instead of a silent
 * "not found".
 */
public class BuildManager {
    private static final int MAX_RETAINED_BUILDS = 512;

    public enum Status {
        RUNNING, COMPLETED, CANCELLED, FAILED
    }

    public enum CancelResult {
        CANCEL_REQUESTED, ALREADY_COMPLETED, ALREADY_CANCELLED, ALREADY_FAILED, NOT_FOUND
    }

    public record BuildInfo(String buildId, Status status, long revision, String error) {
    }

    private static final class BuildRecord {
        private final String buildId;
        private final ProgressToken token = new ProgressToken();
        private volatile Status status = Status.RUNNING;
        private volatile long revision = -1;
        private volatile String error;

        private BuildRecord(String buildId) {
            this.buildId = buildId;
        }

        private BuildInfo toInfo() {
            return new BuildInfo(buildId, status, revision, error);
        }
    }

    private final CallGraphService service;
    private final ExecutorService executor;
    private final Map<String, BuildRecord> builds = new ConcurrentHashMap<>();
    private final AtomicInteger submitted = new AtomicInteger();

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

    /**
     * Submits a full build and returns its identifier immediately, before the
     * build has completed.
     */
    public String submitBuild() {
        evictTerminatedIfNeeded();
        String buildId = UUID.randomUUID().toString();
        BuildRecord record = new BuildRecord(buildId);
        builds.put(buildId, record);
        submitted.incrementAndGet();
        executor.execute(() -> runBuild(record));
        return buildId;
    }

    public Optional<BuildInfo> info(String buildId) {
        BuildRecord record = builds.get(buildId);
        return record == null ? Optional.empty() : Optional.of(record.toInfo());
    }

    /**
     * Requests cancellation of a running build. A build that already reached a
     * terminal state is reported distinctly and is never rolled back.
     */
    public CancelResult cancel(String buildId) {
        BuildRecord record = builds.get(buildId);
        if (record == null) {
            return CancelResult.NOT_FOUND;
        }
        return switch (record.status) {
            case RUNNING -> {
                record.token.cancel();
                yield CancelResult.CANCEL_REQUESTED;
            }
            case COMPLETED -> CancelResult.ALREADY_COMPLETED;
            case CANCELLED -> CancelResult.ALREADY_CANCELLED;
            case FAILED -> CancelResult.ALREADY_FAILED;
        };
    }

    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runBuild(BuildRecord record) {
        try {
            service.build(service.repositoryRoot(), record.token);
            record.revision = service.snapshot().revision();
            record.status = Status.COMPLETED;
        } catch (CancellationException cancelled) {
            record.revision = service.snapshot().revision();
            record.status = Status.CANCELLED;
        } catch (RuntimeException | Error failure) {
            record.error = failure.toString();
            record.revision = service.snapshot().revision();
            record.status = Status.FAILED;
        }
    }

    private void evictTerminatedIfNeeded() {
        if (builds.size() < MAX_RETAINED_BUILDS) {
            return;
        }
        builds.values().removeIf(record -> record.status != Status.RUNNING);
    }
}
