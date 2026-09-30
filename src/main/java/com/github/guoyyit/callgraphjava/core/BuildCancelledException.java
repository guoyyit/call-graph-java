package com.github.guoyyit.callgraphjava.core;

/** 构建/查询被取消；调用方保证已持久化状态不被触碰。 */
public class BuildCancelledException extends RuntimeException {

    public BuildCancelledException() {
    }

    public BuildCancelledException(String message) {
        super(message);
    }
}
