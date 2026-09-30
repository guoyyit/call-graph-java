package com.github.guoyyit.callgraphjava.graph;

import com.github.guoyyit.callgraphjava.model.CallEdge;
import com.github.guoyyit.callgraphjava.model.MethodId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 不可变方法级调用图。构建期一次性建立出边/入边邻接表，发布后全部字段为
 * 不可变结构，查询无需加锁，天然支持多读者并发。
 */
public final class CallGraph {

    private final Map<MethodId, List<CallEdge>> outgoing;
    private final Map<MethodId, List<CallEdge>> incoming;
    private final Set<MethodId> methods;
    private final Set<CallEdge> edges;

    private CallGraph(Map<MethodId, List<CallEdge>> outgoing,
                      Map<MethodId, List<CallEdge>> incoming,
                      Set<MethodId> methods,
                      Set<CallEdge> edges) {
        this.outgoing = outgoing;
        this.incoming = incoming;
        this.methods = methods;
        this.edges = edges;
    }

    public static CallGraph of(Collection<CallEdge> edges, Set<MethodId> allMethods) {
        Map<MethodId, List<CallEdge>> outgoing = new LinkedHashMap<>();
        Map<MethodId, List<CallEdge>> incoming = new LinkedHashMap<>();
        Set<MethodId> methods = new LinkedHashSet<>(allMethods);
        for (CallEdge edge : edges) {
            outgoing.computeIfAbsent(edge.caller(), k -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.callee(), k -> new ArrayList<>()).add(edge);
            methods.add(edge.caller());
            methods.add(edge.callee());
        }
        Map<MethodId, List<CallEdge>> immutableOut = immutableAdjacency(outgoing, methods);
        Map<MethodId, List<CallEdge>> immutableIn = immutableAdjacency(incoming, methods);
        return new CallGraph(immutableOut, immutableIn,
                Collections.unmodifiableSet(methods),
                Collections.unmodifiableSet(new LinkedHashSet<>(edges)));
    }

    private static Map<MethodId, List<CallEdge>> immutableAdjacency(
            Map<MethodId, List<CallEdge>> raw, Set<MethodId> methods) {
        for (MethodId method : methods) {
            raw.putIfAbsent(method, List.of());
        }
        Map<MethodId, List<CallEdge>> frozen = new LinkedHashMap<>();
        for (Map.Entry<MethodId, List<CallEdge>> e : raw.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    public List<CallEdge> outgoingEdges(MethodId method) {
        return outgoing.getOrDefault(method, List.of());
    }

    public List<CallEdge> incomingEdges(MethodId method) {
        return incoming.getOrDefault(method, List.of());
    }

    public Set<MethodId> methods() {
        return methods;
    }

    public Set<CallEdge> edges() {
        return edges;
    }

    public Map<MethodId, List<CallEdge>> adjacency() {
        return outgoing;
    }
}
