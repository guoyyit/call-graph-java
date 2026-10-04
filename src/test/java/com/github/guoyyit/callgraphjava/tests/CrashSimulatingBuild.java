package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.service.CallGraphService;

import java.nio.file.Path;

/**
 * Helper process for {@link CrashRecoveryTest}. Starts a build and halts the
 * JVM (like {@code kill -9}, no shutdown hooks) after the journal BEGIN
 * record is durable but before the change is committed, simulating a crash
 * in the middle of an uncommitted build.
 */
public final class CrashSimulatingBuild {
    private CrashSimulatingBuild() {
    }

    public static void main(String[] args) {
        Path repo = Path.of(args[0]);
        Path state = Path.of(args[1]);
        CallGraphService service = new CallGraphService(repo, state) {
            @Override
            protected void beforeCommit(ProgressToken token) {
                Runtime.getRuntime().halt(1);
            }
        };
        service.build(repo, ProgressToken.neverCancelled());
    }
}
