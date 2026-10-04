package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.service.CallGraphService;

import java.nio.file.Path;

public final class CrashHarness {
    private CrashHarness() {
    }

    public static void main(String[] args) {
        Path repo = Path.of(args[0]);
        Path state = Path.of(args[1]);
        int haltAfterChecks = Integer.parseInt(args[2]);
        CallGraphService service = new CallGraphService(repo, state);
        service.build(repo, new ProgressToken() {
            private int checks;

            @Override
            public boolean isCancelled() {
                checks++;
                if (checks >= haltAfterChecks) {
                    Runtime.getRuntime().halt(42);
                }
                return false;
            }
        });
        Runtime.getRuntime().exit(7);
    }
}
