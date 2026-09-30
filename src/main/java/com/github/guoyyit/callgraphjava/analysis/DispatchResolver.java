package com.github.guoyyit.callgraphjava.analysis;

import com.github.guoyyit.callgraphjava.model.CallKind;
import com.github.guoyyit.callgraphjava.model.CallSite;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.TypeInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 类层次分析（Class Hierarchy Analysis）分派器。
 *
 * <ul>
 *   <li>{@link CallKind#DIRECT}：静态/私有/构造/super 调用，目标属主在解析阶段已确定，
 *       在该属主（及其仓库内祖先）上按 name+arity 求唯一最近声明；</li>
 *   <li>{@link CallKind#VIRTUAL}/{@link CallKind#INTERFACE}：接收者静态类型为 {@code T} 时，
 *       枚举全部可赋值给 {@code T} 的仓库内类型 {@code C}（含 {@code T} 自身），
 *       对每个 {@code C} 求一次动态分派的最近声明目标，汇总去重后即“按类型层次可推导的
 *       全部候选目标”。这是 CHA 语义下的合理上近似：不做过程间指针分析，
 *       因此候选集可能大于某次运行的真实目标，但不会漏掉类型层次允许的目标。</li>
 * </ul>
 */
public final class DispatchResolver {

    private final TypeHierarchy hierarchy;

    public DispatchResolver(TypeHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public List<MethodId> resolve(CallSite callSite) {
        if (callSite.kind() == CallKind.DIRECT) {
            return resolveDirect(callSite);
        }
        return resolveVirtual(callSite);
    }

    private List<MethodId> resolveDirect(CallSite callSite) {
        String owner = callSite.staticTargetOwner();
        if (owner == null || !hierarchy.exists(owner)) {
            return List.of();
        }
        if ("<init>".equals(callSite.targetName())) {
            TypeInfo ownerInfo = hierarchy.typeInfo(owner);
            if (ownerInfo == null) {
                return List.of();
            }
            for (MethodId method : ownerInfo.declaredMethods()) {
                if ("<init>".equals(method.name())
                        && (callSite.arity() < 0 || method.arity() == callSite.arity())) {
                    return List.of(method);
                }
            }
            return List.of();
        }
        // super(...) 等场景下最近声明可能在父类；visibleMethods 已覆盖祖先链。
        List<MethodId> matches = hierarchy.visibleMethods(
                owner, callSite.targetName(), callSite.arity());
        if (matches.isEmpty()) {
            return List.of();
        }
        // 静态方法不会被继承链上的同名方法覆盖：优先返回属主自身声明的匹配；
        // 其余直接调用（super 等）绑定祖先链上的第一个匹配。
        for (MethodId method : matches) {
            if (method.ownerType().equals(owner)) {
                return List.of(method);
            }
        }
        return List.of(matches.get(0));
    }

    private List<MethodId> resolveVirtual(CallSite callSite) {
        String receiverType = callSite.receiverType();
        if (receiverType == null) {
            return List.of();
        }
        receiverType = TypeNameResolver.stripArrays(receiverType);
        Set<MethodId> targets = new LinkedHashSet<>();
        if (!hierarchy.exists(receiverType)) {
            return List.of();
        }
        Set<String> concreteTypes = hierarchy.subtypesIncludingSelf(receiverType);
        for (String concrete : concreteTypes) {
            MethodId target = hierarchy.resolveSingleTarget(
                    concrete, callSite.targetName(), callSite.arity());
            if (target != null) {
                targets.add(target);
            } else {
                // 该具体类型上存在同名同 arity 但描述符歧义的方法：退化为祖先链全部候选，
                // 保证不漏报（上近似）。
                targets.addAll(hierarchy.visibleMethods(
                        concrete, callSite.targetName(), callSite.arity()));
            }
        }
        return new ArrayList<>(targets);
    }
}
