package com.github.guoyyit.callgraphjava.store;

import com.github.guoyyit.callgraphjava.analysis.BuildResult;

import java.util.Map;

/**
 * 持久化快照：调用图/解析表/文件哈希 + 元数据。
 * 解析表与调用图均可从 {@link BuildResult} 恢复。
 */
public record StoredSnapshot(long version,
                             long transactionId,
                             BuildResult result,
                             Map<String, String> fileHashes,
                             long committedAtMillis) {
}
