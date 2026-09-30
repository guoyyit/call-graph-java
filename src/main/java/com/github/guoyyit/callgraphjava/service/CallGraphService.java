package com.github.guoyyit.callgraphjava.service;

import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.incremental.ChangeSet;

import java.nio.file.Path;

/**
 * 调用图服务：串行化更新（单写者），并发只读查询（多读者，引用当前不可变快照）。
 * 取消在发布新快照之前生效，已持久化状态保持不变。
 */
public interface CallGraphService {

    /** 当前可见快照版本号；尚无构建时返回 -1。 */
    long currentVersion();

    /** 获取当前已提交快照；不存在返回 null。 */
    GraphSnapshot currentSnapshot();

    /** 全量构建并原子提交。 */
    GraphSnapshot buildFull(CancellationToken token);

    /** 应用指定变更集并原子提交，结果与全量重建一致。 */
    GraphSnapshot applyChanges(ChangeSet changes, CancellationToken token);

    /** 扫描工作区差异并增量提交。 */
    GraphSnapshot buildIncremental(CancellationToken token);

    /** 启动时从持久化位置恢复；接着重做未提交 WAL。 */
    void recover();

    /** 关闭并释放资源。 */
    void shutdown();

    Path repoRoot();
}
