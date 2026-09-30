package com.github.guoyyit.callgraphjava.store;

import java.util.List;

/**
 * 预写日志的一条记录：批次号 + 该批次变更的相对路径（含删除标记）。
 * 提交快照成功后日志才允许清除，保证崩溃后可精确重做而不重放已提交批次。
 */
public record WalEntry(long transactionId, List<String> changedPaths, List<String> deletedPaths) {
}
