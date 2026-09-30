package com.github.guoyyit.callgraphjava.service;

import com.github.guoyyit.callgraphjava.analysis.BuildResult;
import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.graph.GraphQueries;
import com.github.guoyyit.callgraphjava.graph.SccAnalyzer;
import com.github.guoyyit.callgraphjava.graph.SccResult;
import com.github.guoyyit.callgraphjava.model.ParsedFile;

import java.util.Map;

/**
 * 某一已提交时刻的完整不可变快照（MVCC 版本）。
 *
 * <p>查询线程一旦拿到该对象引用，访问的永远是同一套自洽数据：底层调用图、解析表、
 * 文件哈希都在构建期冻结。更新线程通过替换服务内的单个 volatile 引用发布新快照，
 * 旧快照对仍持有它的查询继续有效，直到没有引用后被 GC 回收。
 */
public final class GraphSnapshot {

    private final long version;
    private final long transactionId;
    private final BuildResult result;
    private final Map<String, String> fileHashes;
    private final GraphQueries queries;
    private volatile SccResult cachedScc;

    public GraphSnapshot(long version,
                         long transactionId,
                         BuildResult result,
                         Map<String, String> fileHashes) {
        this.version = version;
        this.transactionId = transactionId;
        this.result = result;
        this.fileHashes = Map.copyOf(fileHashes);
        this.queries = new GraphQueries(result.graph());
    }

    public long version() {
        return version;
    }

    public long transactionId() {
        return transactionId;
    }

    public BuildResult result() {
        return result;
    }

    public Map<String, String> fileHashes() {
        return fileHashes;
    }

    public Map<String, ParsedFile> parsedFiles() {
        return result.parsedFiles();
    }

    public GraphQueries queries() {
        return queries;
    }

    public SccAnalyzer sccAnalyzer() {
        return new SccAnalyzer(result.graph());
    }

    /** SCC 计算成本较高且结果不可变，首次查询时缓存（双重检查，计算可取消）。 */
    public SccResult scc(CancellationToken token) {
        SccResult local = cachedScc;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cachedScc == null) {
                cachedScc = new SccAnalyzer(result.graph()).analyze(token);
            }
            return cachedScc;
        }
    }
}
