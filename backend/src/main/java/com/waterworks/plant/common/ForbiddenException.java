package com.waterworks.plant.common;

/**
 * 越权访问异常：身份明确但角色不允许，或身份无法确认。统一转 403。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
