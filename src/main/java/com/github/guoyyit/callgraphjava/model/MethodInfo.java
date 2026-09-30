package com.github.guoyyit.callgraphjava.model;

import java.util.Map;

/**
 * 单个方法的元信息。
 *
 * @param locals 过程内可静态推导的局部变量/形参：变量名 -> 源码书写的声明类型
 * @param returnType 源码书写的返回类型（构造器为声明类型自身）
 */
public record MethodInfo(MethodId id,
                         boolean isStatic,
                         boolean isAbstract,
                         String sourceFile,
                         Map<String, String> locals,
                         String returnType) {
}
