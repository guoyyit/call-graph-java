package com.github.guoyyit.callgraphjava.build;

import java.util.concurrent.atomic.AtomicBoolean;

public class ProgressToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public static ProgressToken neverCancelled() {
        return new ProgressToken() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }
}
