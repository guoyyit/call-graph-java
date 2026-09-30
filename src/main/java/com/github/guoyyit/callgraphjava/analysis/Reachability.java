package com.github.guoyyit.callgraphjava.analysis;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Reachability {
    public Set<String> reachable(String start, Map<String, java.util.List<String>> graph) {
        return reachable(start, graph, Integer.MAX_VALUE);
    }

    public Set<String> reachable(String start, Map<String, List<String>> graph, int maxDepth) {
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        Deque<String> nodes = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        nodes.add(start);
        depths.add(0);
        while (!nodes.isEmpty()) {
            String node = nodes.poll();
            int depth = depths.poll();
            if (!visited.add(node) || depth >= maxDepth) {
                continue;
            }
            for (String successor : graph.getOrDefault(node, List.of())) {
                if (!visited.contains(successor)) {
                    nodes.add(successor);
                    depths.add(depth + 1);
                }
            }
        }
        return visited;
    }

    public boolean canReach(String start, String target, Map<String, List<String>> graph) {
        return reachable(start, graph).contains(target);
    }
}
