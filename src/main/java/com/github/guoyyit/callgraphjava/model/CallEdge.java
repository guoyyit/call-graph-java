package com.github.guoyyit.callgraphjava.model;

/**
 * 调用图中的一条有向边。同一调用点解析出多个候选时产生多条边。
 */
public record CallEdge(MethodId caller, MethodId callee, CallKind kind, int line, String sourceFile) {
}
