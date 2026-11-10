package com.msd.agent.service;

/**
 * AgentScope 未启用或初始化失败。
 */
public class AgentUnavailableException extends RuntimeException {

    /**
     * 仅带可展示给调用方的信息。
     */
    public AgentUnavailableException(String message) {
        super(message);
    }

    /**
     * 保留原始异常，供服务端日志排查。
     */
    public AgentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
