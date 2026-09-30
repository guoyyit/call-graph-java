package com.github.guoyyit.callgraphjava.core;

import java.util.concurrent.atomic.AtomicBoolean;

/** 协作式取消令牌：构建与查询在检查点轮询。 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 永不取消的令牌，供无需取消的查询便捷使用。 */
    public static final CancellationToken NONE = new CancellationToken();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 若已取消则抛出 {@link BuildCancelledException}。 */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new BuildCancelledException("operation cancelled");
        }
    }
}
