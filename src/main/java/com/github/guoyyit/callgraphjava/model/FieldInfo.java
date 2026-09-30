package com.github.guoyyit.callgraphjava.model;

/** 类型直接声明的字段：名称 + 源码书写的声明类型（简单名或 FQN）。 */
public record FieldInfo(String name, String rawType) {
}
