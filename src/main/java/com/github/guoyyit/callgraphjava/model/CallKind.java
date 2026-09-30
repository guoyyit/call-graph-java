package com.github.guoyyit.callgraphjava.model;

/**
 * 调用种类：
 * DIRECT —— 静态/私有/构造/父类调用，编译期唯一确定目标；
 * VIRTUAL —— 普通虚调用（invokevirtual）；
 * INTERFACE —— 接口调用（invokeinterface），同样按类型层次分派。
 */
public enum CallKind {
    DIRECT,
    VIRTUAL,
    INTERFACE;

    public boolean isDispatched() {
        return this == VIRTUAL || this == INTERFACE;
    }
}
