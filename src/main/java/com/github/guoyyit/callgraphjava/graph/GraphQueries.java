package com.github.guoyyit.callgraphjava.graph;

import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.model.CallEdge;
import com.github.guoyyit.callgraphjava.model.MethodId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 不可变调用图上的只读查询：调用者/被调用者、跨包边、可达性与调用链。 */
public final class GraphQueries {

    private final CallGraph graph;

    public GraphQueries(CallGraph graph) {
        this.graph = graph;
    }

    public Set<MethodId> callers(MethodId method) {
        Set<MethodId> result = new LinkedHashSet<>();
        for (CallEdge edge : graph.incomingEdges(method)) {
            result.add(edge.caller());
        }
        return Set.copyOf(result);
    }

    public Set<MethodId> callees(MethodId method) {
        Set<MethodId> result = new LinkedHashSet<>();
        for (CallEdge edge : graph.outgoingEdges(method)) {
            result.add(edge.callee());
        }
        return Set.copyOf(result);
    }

    /** 跨包：两个方法属主类型的包名不同（默认包与任何具名包之间也算跨包）。 */
    public boolean isCrossPackageEdge(MethodId caller, MethodId callee) {
        return !packageOf(caller.ownerType()).equals(packageOf(callee.ownerType()));
    }

    /** 全图中所有跨包边（去重后的调用方集合）。 */
    public Set<CallEdge> crossPackageEdges() {
        Set<CallEdge> result = new LinkedHashSet<>();
        for (CallEdge edge : graph.edges()) {
            if (isCrossPackageEdge(edge.caller(), edge.callee())) {
                result.add(edge);
            }
        }
        return Set.copyOf(result);
    }

    public boolean isReachable(MethodId source, MethodId target) {
        return reachableFrom(source, CancellationToken.NONE).contains(target);
    }

    public Set<MethodId> reachableFrom(MethodId source, CancellationToken token) {
        Set<MethodId> visited = new LinkedHashSet<>();
        if (!graph.methods().contains(source)) {
            return Set.of();
        }
        Deque<MethodId> queue = new ArrayDeque<>();
        queue.add(source);
        visited.add(source);
        int checked = 0;
        while (!queue.isEmpty()) {
            MethodId current = queue.poll();
            for (CallEdge edge : graph.outgoingEdges(current)) {
                if (visited.add(edge.callee())) {
                    queue.add(edge.callee());
                }
            }
            if ((++checked & 0xFF) == 0) {
                token.throwIfCancelled();
            }
        }
        return Set.copyOf(visited);
    }

    /** BFS 最短调用链；不可达时返回空列表，起点即终点返回单元素列表。 */
    public List<MethodId> findPath(MethodId source, MethodId target) {
        if (!graph.methods().contains(source)) {
            return List.of();
        }
        if (source.equals(target)) {
            return List.of(source);
        }
        java.util.Map<MethodId, MethodId> parent = new java.util.HashMap<>();
        Set<MethodId> visited = new HashSet<>();
        Deque<MethodId> queue = new ArrayDeque<>();
        queue.add(source);
        visited.add(source);
        while (!queue.isEmpty()) {
            MethodId current = queue.poll();
            for (CallEdge edge : graph.outgoingEdges(current)) {
                if (visited.add(edge.callee())) {
                    parent.put(edge.callee(), current);
                    if (edge.callee().equals(target)) {
                        return backtrack(parent, source, target);
                    }
                    queue.add(edge.callee());
                }
            }
        }
        return List.of();
    }

    private List<MethodId> backtrack(java.util.Map<MethodId, MethodId> parent,
                                     MethodId source, MethodId target) {
        List<MethodId> path = new ArrayList<>();
        MethodId current = target;
        while (!current.equals(source)) {
            path.add(current);
            current = parent.get(current);
        }
        path.add(source);
        java.util.Collections.reverse(path);
        return List.copyOf(path);
    }

    static String packageOf(String ownerFqn) {
        int dot = ownerFqn.lastIndexOf('.');
        return dot < 0 ? "" : ownerFqn.substring(0, dot);
    }
}
