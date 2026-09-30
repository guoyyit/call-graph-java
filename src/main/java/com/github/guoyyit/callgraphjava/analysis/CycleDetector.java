package com.github.guoyyit.callgraphjava.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CycleDetector {
    private Map<String, List<String>> graph;
    private final Map<String, Integer> indices = new HashMap<>();
    private final Map<String, Integer> lowLinks = new HashMap<>();
    private final Deque<String> stack = new ArrayDeque<>();
    private final Map<String, Boolean> onStack = new HashMap<>();
    private final List<List<String>> components = new ArrayList<>();
    private int nextIndex;

    public List<List<String>> stronglyConnectedComponents(Map<String, List<String>> graph) {
        this.graph = graph;
        indices.clear();
        lowLinks.clear();
        stack.clear();
        onStack.clear();
        components.clear();
        nextIndex = 0;
        for (String node : allNodes(graph)) {
            if (!indices.containsKey(node)) {
                strongConnect(node);
            }
        }
        return new ArrayList<>(components);
    }

    public Map<String, List<List<String>>> findCycles(Map<String, List<String>> graph) {
        List<List<String>> sccs = stronglyConnectedComponents(graph);
        Map<String, List<List<String>>> result = new HashMap<>();
        for (List<String> component : sccs) {
            List<List<String>> cycles = cyclesInComponent(component, graph);
            for (String node : component) {
                result.put(node, cycles);
            }
        }
        return result;
    }

    private void strongConnect(String node) {
        indices.put(node, nextIndex);
        lowLinks.put(node, nextIndex);
        nextIndex++;
        stack.push(node);
        onStack.put(node, true);
        for (String successor : graph.getOrDefault(node, List.of())) {
            if (!indices.containsKey(successor)) {
                strongConnect(successor);
                lowLinks.put(node, Math.min(lowLinks.get(node), lowLinks.get(successor)));
            } else if (Boolean.TRUE.equals(onStack.get(successor))) {
                lowLinks.put(node, Math.min(lowLinks.get(node), indices.get(successor)));
            }
        }
        if (lowLinks.get(node).equals(indices.get(node))) {
            List<String> component = new ArrayList<>();
            String member;
            do {
                member = stack.pop();
                onStack.put(member, false);
                component.add(member);
            } while (!member.equals(node));
            components.add(component);
        }
    }

    private List<List<String>> cyclesInComponent(List<String> component, Map<String, List<String>> graph) {
        if (component.size() > 1) {
            return List.of(component);
        }
        String only = component.get(0);
        if (graph.getOrDefault(only, List.of()).contains(only)) {
            return List.of(component);
        }
        return List.of();
    }

    private List<String> allNodes(Map<String, List<String>> graph) {
        java.util.LinkedHashSet<String> nodes = new java.util.LinkedHashSet<>(graph.keySet());
        for (List<String> successors : graph.values()) {
            nodes.addAll(successors);
        }
        return new ArrayList<>(nodes);
    }
}
