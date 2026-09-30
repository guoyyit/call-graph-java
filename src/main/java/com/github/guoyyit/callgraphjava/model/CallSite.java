package com.github.guoyyit.callgraphjava.model;

import java.util.List;

/**
 * 方法体内的一个调用点。解析阶段确定 {@link #receiverType}，
 * 分派阶段填充 {@link #resolvedTargets}（虚调用可能有多个候选）。
 *
 * <p>调用目标按「方法名 + 形参数 arity」匹配（重载场景是文档化的上近似），
 * {@code arity == -1} 表示方法引用等参数数静态未知的情形，匹配任意 arity。
 */
public final class CallSite {

    private final String sourceFile;
    private final MethodId enclosingMethod;
    private final int line;
    private CallKind kind;
    private final String targetName;
    private final int arity;
    private final String scope;
    private String staticTargetOwner;
    private String receiverType;
    private List<MethodId> resolvedTargets = List.of();

    public CallSite(String sourceFile,
                    MethodId enclosingMethod,
                    int line,
                    CallKind kind,
                    String targetName,
                    int arity,
                    String scope,
                    String staticTargetOwner) {
        this.sourceFile = sourceFile;
        this.enclosingMethod = enclosingMethod;
        this.line = line;
        this.kind = kind;
        this.targetName = targetName;
        this.arity = arity;
        this.scope = scope;
        this.staticTargetOwner = staticTargetOwner;
    }

    public String sourceFile() { return sourceFile; }
    public MethodId enclosingMethod() { return enclosingMethod; }
    public int line() { return line; }
    public CallKind kind() { return kind; }
    public String targetName() { return targetName; }
    public int arity() { return arity; }
    public String scope() { return scope; }
    public String staticTargetOwner() { return staticTargetOwner; }
    public String receiverType() { return receiverType; }
    public List<MethodId> resolvedTargets() { return resolvedTargets; }

    public void setReceiverType(String receiverType) {
        this.receiverType = receiverType;
    }

    public void setKind(CallKind kind) {
        this.kind = kind;
    }

    public void setStaticTargetOwner(String staticTargetOwner) {
        this.staticTargetOwner = staticTargetOwner;
    }

    public void setResolvedTargets(List<MethodId> resolvedTargets) {
        this.resolvedTargets = resolvedTargets;
    }
}
