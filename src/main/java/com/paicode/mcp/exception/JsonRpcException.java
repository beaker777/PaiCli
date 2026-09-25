package com.paicode.mcp.exception;

/**
 * @Author beaker
 * @Date 2026/9/25 13:38
 * @Description JsonRPC 异常
 */
public class JsonRpcException extends RuntimeException {

    private final int code;

    public JsonRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
