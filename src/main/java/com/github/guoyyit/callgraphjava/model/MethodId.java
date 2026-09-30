package com.github.guoyyit.callgraphjava.model;

import java.util.ArrayList;
import java.util.List;

public record MethodId(String owner, String name, List<String> paramTypes) {
    public MethodId {
        paramTypes = List.copyOf(paramTypes);
    }

    public int arity() {
        return paramTypes.size();
    }

    public String key() {
        return owner + "#" + name + "(" + String.join(",", paramTypes) + ")";
    }

    @Override
    public String toString() {
        return key();
    }

    public static MethodId of(String owner, String name) {
        return new MethodId(owner, name, List.of());
    }

    public static MethodId parse(String text) {
        int hash = text.indexOf('#');
        int open = text.indexOf('(', hash);
        int close = text.lastIndexOf(')');
        String owner = text.substring(0, hash);
        String name = text.substring(hash + 1, open);
        String inside = text.substring(open + 1, close);
        List<String> params = inside.isEmpty() ? List.of() : new ArrayList<>(List.of(inside.split(",")));
        return new MethodId(owner, name, params);
    }
}
