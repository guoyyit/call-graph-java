package com.github.guoyyit.callgraphjava.graph;

import com.github.guoyyit.callgraphjava.model.MethodId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SCC 分析结果：方法 -> 分量 id；id -> 分量成员；以及递归分量上的方法与环路列表。
 */
public record SccResult(Map<MethodId, Integer> componentOf,
                        Map<Integer, Set<MethodId>> components,
                        Set<MethodId> recursiveMethods,
                        List<Set<MethodId>> cycles) {
}
