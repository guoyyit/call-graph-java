package com.github.guoyyit.callgraphjava.model;

/**
 * 方法的唯一标识：声明类型全限定名 + 方法名 + 形参描述符。
 */
public record MethodId(String ownerType, String name, String descriptor) {

    public static MethodId of(String ownerType, String name, String descriptor) {
        return new MethodId(ownerType, name, descriptor == null ? "()" : descriptor);
    }

    /** 形如 {@code com.foo.Bar#run(java.lang.String)} 的稳定文本表示。 */
    public String fqn() {
        return ownerType + "#" + name + descriptor;
    }

    /** 从 {@link #fqn()} 的文本表示还原。 */
    public static MethodId parse(String text) {
        int hash = text.indexOf('#');
        int paren = text.indexOf('(', hash);
        String owner = text.substring(0, hash);
        String name = text.substring(hash + 1, paren);
        String descriptor = text.substring(paren);
        return new MethodId(owner, name, descriptor);
    }

    /** 形参数量（descriptor 形如 "(java.lang.String,int)"）。 */
    public int arity() {
        String params = descriptor.substring(1, descriptor.length() - 1);
        if (params.isBlank()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < params.length(); i++) {
            if (params.charAt(i) == ',') {
                count++;
            }
        }
        return count;
    }

    /** 形参 FQN 列表。 */
    public java.util.List<String> parameterTypes() {
        String params = descriptor.substring(1, descriptor.length() - 1);
        if (params.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(params.split(",")).map(String::trim).toList();
    }
}
