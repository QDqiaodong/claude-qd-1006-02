package com.waterworks.plant.common;

/**
 * 冲突异常：重放参数与首次裁决不一致、或并发裁决时样本结论已被先到者改变。统一转 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
