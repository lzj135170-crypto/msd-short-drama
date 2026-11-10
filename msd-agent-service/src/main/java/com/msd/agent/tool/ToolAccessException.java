package com.msd.agent.tool;

/**
 * 工具调用被权限沙箱拒绝。
 */
public class ToolAccessException extends RuntimeException {

    /**
     * 使用可展示给模型的拒绝原因。
     */
    public ToolAccessException(String message) {
        super(message);
    }
}
