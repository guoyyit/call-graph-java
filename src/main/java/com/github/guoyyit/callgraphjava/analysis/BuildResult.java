package com.github.guoyyit.callgraphjava.analysis;

import com.github.guoyyit.callgraphjava.graph.CallGraph;
import com.github.guoyyit.callgraphjava.model.ParsedFile;

import java.util.Map;

/**
 * 一次构建的产物：不可变调用图 + 解析后的文件表（用于后续增量）。
 */
public record BuildResult(CallGraph graph,
                          Map<String, ParsedFile> parsedFiles,
                          TypeHierarchy hierarchy,
                          Map<String, String> fileHashes) {
}
