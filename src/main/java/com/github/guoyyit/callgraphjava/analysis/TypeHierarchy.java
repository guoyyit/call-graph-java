package com.github.guoyyit.callgraphjava.analysis;

import com.github.guoyyit.callgraphjava.model.TypeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TypeHierarchy {
    private static final String OBJECT = "java.lang.Object";

    private final Map<String, TypeInfo> types;
    private final Map<String, List<String>> children = new HashMap<>();

    public TypeHierarchy(Collection<?> types) {
        this.types = new HashMap<>();
        for (Object raw : types) {
            TypeInfo info = (TypeInfo) raw;
            this.types.put(info.qualifiedName(), info);
        }
        for (TypeInfo info : this.types.values()) {
            for (String parent : directParents(info)) {
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(info.qualifiedName());
            }
        }
    }

    public java.util.List<String> allSubtypes(String type) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : children.getOrDefault(current, List.of())) {
                if (result.add(child)) {
                    queue.add(child);
                }
            }
        }
        return new ArrayList<>(result);
    }

    public java.util.List<String> allSupertypes(String type) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            TypeInfo info = types.get(current);
            if (info == null) {
                continue;
            }
            for (String parent : directParents(info)) {
                if (!parent.equals(OBJECT) && result.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return new ArrayList<>(result);
    }

    public List<String> concreteSubtypes(String type) {
        List<String> result = new ArrayList<>();
        for (String candidate : allSubtypes(type)) {
            TypeInfo info = types.get(candidate);
            if (info != null && !info.abstractType()) {
                result.add(candidate);
            }
        }
        return result;
    }

    public boolean exists(String type) {
        return types.containsKey(type);
    }

    public TypeInfo typeInfo(String type) {
        return types.get(type);
    }

    public Set<String> allTypes() {
        return new LinkedHashSet<>(types.keySet());
    }

    private List<String> directParents(TypeInfo info) {
        List<String> parents = new ArrayList<>();
        if (info.superClass() != null) {
            parents.add(info.superClass());
        }
        parents.addAll(info.interfaces());
        if (parents.isEmpty() && !info.qualifiedName().equals(OBJECT)) {
            parents.add(OBJECT);
        }
        return parents;
    }
}
