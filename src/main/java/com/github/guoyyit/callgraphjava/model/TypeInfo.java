package com.github.guoyyit.callgraphjava.model;

import java.util.List;

/**
 * 源码中声明的一个类型（类/接口/枚举/记录）。
 *
 * @param fqn         全限定名
 * @param kind        class / interface / enum / record / annotation
 * @param superClass  父类全限定名，接口或判定不到时为 null（java.lang.Object 之上的外部库类型不解析）
 * @param interfaces  直接实现/继承的接口全限定名
 * @param declaredMethods 该类型直接声明的方法
 * @param fields      该类型直接声明的字段（源码书写类型，供接收者推导）
 */
public record TypeInfo(String fqn,
                       String kind,
                       String superClass,
                       List<String> interfaces,
                       List<MethodId> declaredMethods,
                       List<FieldInfo> fields) {
}
