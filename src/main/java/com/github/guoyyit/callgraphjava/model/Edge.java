package com.github.guoyyit.callgraphjava.model;

public record Edge(MethodId caller, MethodId callee, CallKind kind) {
    public String key() {
        return caller.key() + "->" + callee.key() + ":" + kind;
    }
}
