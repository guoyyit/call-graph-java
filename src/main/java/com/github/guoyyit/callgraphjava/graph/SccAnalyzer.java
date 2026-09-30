package com.github.guoyyit.callgraphjava.graph;

import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.model.CallEdge;
import com.github.guoyyit.callgraphjava.model.MethodId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 强连通分量分析：迭代式 Tarjan。
 *
 * <p>大仓库中方法节点可达数十万、递归深度可能超过 JVM 栈容量，因此使用显式帧栈；
 * 每处理一批节点检查取消令牌。大小 &gt;= 2 的分量，或带自环的单节点分量，
 * 其成员均归类为“环路上的方法”。
 */
public final class SccAnalyzer {

    private final CallGraph graph;

    public SccAnalyzer(CallGraph graph) {
        this.graph = graph;
    }

    public SccResult analyze(CancellationToken token) {
        Map<MethodId, Integer> index = new HashMap<>();
        Map<MethodId, Integer> lowLink = new HashMap<>();
        Deque<MethodId> stack = new ArrayDeque<>();
        Set<MethodId> onStack = new LinkedHashSet<>();
        Map<MethodId, Integer> componentOf = new LinkedHashMap<>();
        Map<Integer, Set<MethodId>> components = new LinkedHashMap<>();

        int[] counter = {0};
        int[] componentId = {0};
        int examined = 0;

        for (MethodId root : graph.methods()) {
            if (index.containsKey(root)) {
                continue;
            }
            // 帧：(节点, 下一条待处理出边下标)
            Deque<Frame> dfs = new ArrayDeque<>();
            dfs.push(new Frame(root, 0));
            assignIndex(root, counter, index, lowLink, stack, onStack);

            while (!dfs.isEmpty()) {
                if ((++examined & 0x3FF) == 0) {
                    token.throwIfCancelled();
                }
                Frame frame = dfs.peek();
                List<CallEdge> edges = graph.outgoingEdges(frame.node);
                if (frame.nextEdge < edges.size()) {
                    CallEdge edge = edges.get(frame.nextEdge++);
                    MethodId successor = edge.callee();
                    if (!index.containsKey(successor)) {
                        assignIndex(successor, counter, index, lowLink, stack, onStack);
                        dfs.push(new Frame(successor, 0));
                    } else if (onStack.contains(successor)) {
                        lowLink.merge(frame.node, index.get(successor), Math::min);
                    }
                } else {
                    dfs.pop();
                    if (lowLink.get(frame.node).equals(index.get(frame.node))) {
                        Set<MethodId> component = new LinkedHashSet<>();
                        MethodId popped;
                        do {
                            popped = stack.pop();
                            onStack.remove(popped);
                            component.add(popped);
                            componentOf.put(popped, componentId[0]);
                        } while (!popped.equals(frame.node));
                        components.put(componentId[0]++, Set.copyOf(component));
                    }
                    if (!dfs.isEmpty()) {
                        MethodId parent = dfs.peek().node;
                        lowLink.merge(parent, lowLink.get(frame.node), Math::min);
                    }
                }
            }
        }

        Set<MethodId> recursiveMethods = new LinkedHashSet<>();
        List<Set<MethodId>> cycles = new ArrayList<>();
        for (Set<MethodId> component : components.values()) {
            boolean cycle = component.size() >= 2 || hasSelfEdge(component.iterator().next());
            if (cycle) {
                recursiveMethods.addAll(component);
                cycles.add(component);
            }
        }
        return new SccResult(Map.copyOf(componentOf), Map.copyOf(components),
                Set.copyOf(recursiveMethods), List.copyOf(cycles));
    }

    private boolean hasSelfEdge(MethodId node) {
        for (CallEdge edge : graph.outgoingEdges(node)) {
            if (edge.callee().equals(node)) {
                return true;
            }
        }
        return false;
    }

    private void assignIndex(MethodId node,
                             int[] counter,
                             Map<MethodId, Integer> index,
                             Map<MethodId, Integer> lowLink,
                             Deque<MethodId> stack,
                             Set<MethodId> onStack) {
        index.put(node, counter[0]);
        lowLink.put(node, counter[0]);
        counter[0]++;
        stack.push(node);
        onStack.add(node);
    }

    private static final class Frame {
        private final MethodId node;
        private int nextEdge;

        private Frame(MethodId node, int nextEdge) {
            this.node = node;
            this.nextEdge = nextEdge;
        }
    }
}
