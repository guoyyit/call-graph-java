package com.github.guoyyit.callgraphjava.model;

import java.util.List;

/**
 * 单个源文件的解析产物。
 *
 * @param imports 源码 import 语句（含通配 import 原始文本，如 {@code java.util.*}）
 * @param staticImports 静态单成员导入的成员名（{@code import static a.B.m;} 记录 {@code m}）
 */
public record ParsedFile(String relativePath,
                         String packageName,
                         List<String> imports,
                         List<String> staticImports,
                         List<TypeInfo> types,
                         List<MethodInfo> methods,
                         List<CallSite> callSites,
                         String sha256) {
}
