package com.github.guoyyit.callgraphjava.service;

import com.github.guoyyit.callgraphjava.analysis.CycleDetector;
import com.github.guoyyit.callgraphjava.analysis.Reachability;
import com.github.guoyyit.callgraphjava.model.Edge;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.MethodMetrics;
import com.github.guoyyit.callgraphjava.store.GraphStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QueryService {
    private final GraphStore store;

    public QueryService(GraphStore store) {
        this.store = store;
    }

    public GraphSnapshot snapshot() {
        return store.current();
    }

    public List<Edge> callees(MethodId method) {
        return snapshot().edges().stream()
                .filter(edge -> edge.caller().equals(method))
                .toList();
    }

    public List<Edge> callers(MethodId method) {
        return snapshot().edges().stream()
                .filter(edge -> edge.callee().equals(method))
                .toList();
    }

    public List<Edge> virtualCandidates(MethodId caller, String name) {
        return callees(caller).stream()
                .filter(edge -> edge.callee().name().equals(name))
                .toList();
    }

    public List<Edge> crossPackageEdges() {
        return snapshot().edges().stream()
                .filter(edge -> !packageOf(edge.caller().owner()).equals(packageOf(edge.callee().owner())))
                .toList();
    }

    public Set<MethodId> reachable(MethodId start) {
        Map<String, List<String>> graph = outgoingKeyGraph();
        Set<String> keys = new Reachability().reachable(start.key(), graph);
        return toMethodIds(keys);
    }

    public boolean canReach(MethodId start, MethodId target) {
        return new Reachability().canReach(start.key(), target.key(), outgoingKeyGraph());
    }

    public Map<String, Integer> stronglyConnectedComponents() {
        List<List<String>> components = new CycleDetector().stronglyConnectedComponents(outgoingKeyGraph());
        Map<String, Integer> assignment = new LinkedHashMap<>();
        for (int index = 0; index < components.size(); index++) {
            for (String key : components.get(index)) {
                assignment.put(key, index);
            }
        }
        return assignment;
    }

    public Set<MethodId> methodsInCycles() {
        Map<String, List<List<String>>> cycles = new CycleDetector().findCycles(outgoingKeyGraph());
        Set<MethodId> result = new LinkedHashSet<>();
        for (Map.Entry<String, List<List<String>>> entry : cycles.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.add(MethodId.parse(entry.getKey()));
            }
        }
        return result;
    }

    public boolean isRecursive(MethodId method) {
        return methodsInCycles().contains(method);
    }

    public MethodMetrics metrics(MethodId method) {
        List<Edge> outgoing = callees(method);
        List<Edge> incoming = callers(method);
        Map<String, Integer> scc = stronglyConnectedComponents();
        boolean inCycle = isRecursive(method);
        Set<MethodId> reachable = reachable(method);
        return new MethodMetrics(method, incoming.size(), outgoing.size(),
                scc.getOrDefault(method.key(), -1), inCycle, new ArrayList<>(reachable));
    }

    private Map<String, List<String>> outgoingKeyGraph() {
        GraphSnapshot snapshot = snapshot();
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (Edge edge : snapshot.edges()) {
            graph.computeIfAbsent(edge.caller().key(), key -> new ArrayList<>())
                    .add(edge.callee().key());
        }
        return graph;
    }

    private Set<MethodId> toMethodIds(Set<String> keys) {
        Set<MethodId> result = new LinkedHashSet<>();
        for (String key : keys) {
            result.add(MethodId.parse(key));
        }
        return result;
    }

    private String packageOf(String owner) {
        int dot = owner.lastIndexOf('.');
        return dot < 0 ? "" : owner.substring(0, dot);
    }
}
