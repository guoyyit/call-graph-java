package com.github.guoyyit.callgraphjava.analysis;

import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.MethodInfo;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import com.github.guoyyit.callgraphjava.model.TypeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全仓库类型层次索引：父子关系、祖先链、可赋值子类型闭包，以及“在某类型上可见的
 * 同名同参数数方法”查询。外部库类型（仓库内无 TypeInfo）在链上被跳过。
 */
public final class TypeHierarchy {

    private final Map<String, TypeInfo> types;
    private final Map<String, Set<String>> children;
    private final Map<MethodId, MethodInfo> methodInfos;

    private TypeHierarchy(Map<String, TypeInfo> types,
                          Map<String, Set<String>> children,
                          Map<MethodId, MethodInfo> methodInfos) {
        this.types = Map.copyOf(types);
        this.children = children;
        this.methodInfos = Map.copyOf(methodInfos);
    }

    public static TypeHierarchy build(Collection<TypeInfo> typeInfos) {
        return build(typeInfos, List.of());
    }

    public static TypeHierarchy build(Collection<TypeInfo> typeInfos,
                                      Collection<ParsedFile> files) {
        Map<String, TypeInfo> types = new LinkedHashMap<>();
        for (TypeInfo t : typeInfos) {
            types.put(t.fqn(), t);
        }
        Map<String, Set<String>> children = new HashMap<>();
        for (TypeInfo t : typeInfos) {
            if (t.superClass() != null && types.containsKey(t.superClass())) {
                children.computeIfAbsent(t.superClass(), k -> new HashSet<>()).add(t.fqn());
            }
            for (String itf : t.interfaces()) {
                if (types.containsKey(itf)) {
                    children.computeIfAbsent(itf, k -> new HashSet<>()).add(t.fqn());
                }
            }
        }
        Map<MethodId, MethodInfo> methodInfos = new HashMap<>();
        for (ParsedFile file : files) {
            for (MethodInfo m : file.methods()) {
                methodInfos.put(m.id(), m);
            }
        }
        return new TypeHierarchy(types, children, methodInfos);
    }

    public TypeInfo typeInfo(String fqn) {
        return types.get(fqn);
    }

    public boolean exists(String fqn) {
        return fqn != null && types.containsKey(fqn);
    }

    public Set<String> allTypes() {
        return types.keySet();
    }

    public MethodInfo methodInfo(MethodId id) {
        return methodInfos.get(id);
    }

    /** 祖先链（含自身），从最具体类型向父类/父接口排列；缺失的外部类型不中断遍历（跳过）。 */
    public List<String> ancestors(String fqn) {
        List<String> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(fqn);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!seen.add(current)) {
                continue;
            }
            chain.add(current);
            TypeInfo info = types.get(current);
            if (info == null) {
                continue;
            }
            if (info.superClass() != null && types.containsKey(info.superClass())) {
                stack.push(info.superClass());
            }
            for (String itf : info.interfaces()) {
                if (types.containsKey(itf)) {
                    stack.push(itf);
                }
            }
        }
        return chain;
    }

    /** 全部可赋值给 {@code fqn} 的仓库内类型（含自身）：沿 children 图闭包。 */
    public Set<String> subtypesIncludingSelf(String fqn) {
        Set<String> result = new HashSet<>();
        if (!types.containsKey(fqn)) {
            return result;
        }
        Deque<String> stack = new ArrayDeque<>();
        stack.push(fqn);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!result.add(current)) {
                continue;
            }
            for (String child : children.getOrDefault(current, Set.of())) {
                stack.push(child);
            }
        }
        return result;
    }

    /**
     * 在类型 {@code ownerFqn} 上按 name+arity 可命中的方法（含继承自仓库内祖先类型的方法）。
     * 顺序：自身 -> 父类 -> 父接口，首个声明了匹配方法的祖先优先（模拟动态分派的可见性），
     * 但所有层级的匹配方法均返回，供 CHA 在跨子类型时给出全部候选。
     */
    public List<MethodId> visibleMethods(String ownerFqn, String name, int arity) {
        List<MethodId> result = new ArrayList<>();
        for (String ancestor : ancestors(ownerFqn)) {
            TypeInfo info = types.get(ancestor);
            if (info == null) {
                continue;
            }
            for (MethodId method : info.declaredMethods()) {
                if (method.name().equals(name) && (arity < 0 || method.arity() == arity)) {
                    result.add(method);
                }
            }
        }
        return result;
    }

    /** 在某一具体类型（含其祖先）上解析一次虚调用的“最近声明”目标。 */
    public MethodId resolveSingleTarget(String concreteType, String name, int arity) {
        for (String ancestor : ancestors(concreteType)) {
            TypeInfo info = types.get(ancestor);
            if (info == null) {
                continue;
            }
            MethodId found = null;
            boolean ambiguous = false;
            for (MethodId method : info.declaredMethods()) {
                if (!method.name().equals(name) || (arity >= 0 && method.arity() != arity)) {
                    continue;
                }
                if (found == null) {
                    found = method;
                } else if (!found.descriptor().equals(method.descriptor())) {
                    // 同名同 arity 但形参描述符不同：真实重载，二者都是合法候选，
                    // 单次“最近目标”无法二选一，标记歧义由调用方退回全部候选。
                    ambiguous = true;
                }
            }
            if (ambiguous) {
                return null;
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public Map<String, TypeInfo> types() {
        return types;
    }
}
