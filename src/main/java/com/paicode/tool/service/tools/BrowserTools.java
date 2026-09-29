package com.paicode.tool.service.tools;

import com.paicode.browser.service.connect.BrowserConnector;
import com.paicode.tool.entity.ToolDefinition;
import com.paicode.tool.entity.ToolSchema;

import java.util.List;
import java.util.function.Supplier;

/**
 * @Author beaker
 * @Date 2026/9/29 21:05
 * @Description 浏览器工具
 */
public class BrowserTools {

    private final Supplier<BrowserConnector> browserConnector;

    public BrowserTools(Supplier<BrowserConnector> browserConnector) {
        this.browserConnector = browserConnector;
    }

    public List<ToolDefinition> create() {
        return List.of(
                createBrowserConnectTool(),
                createBrowserDisconnectTool(),
                createBrowserStatusTool()
        );
    }

    private ToolDefinition createBrowserConnectTool() {
        return new ToolDefinition("browser_connect",
                "当浏览器页面返回登录页、权限不足或明确需要登录态时，自动连接已允许远程调试的本机 Chrome 并复用其登录态；公开页面不要提前调用。",
                ToolSchema.createParameters(),
                args ->
                        browserConnector == null ? "浏览器未初始化, 无法自动切换到 shared 模式" : browserConnector.get().connectDefault()
        );
    }

    private ToolDefinition createBrowserDisconnectTool() {
        return new ToolDefinition("browser_disconnect",
                "完成登录页面访问后, 可切回 disconnect 模式",
                ToolSchema.createParameters(),
                args ->
                        browserConnector == null ? "浏览器未初始化, 无法自动切换到 isolated 模式" : browserConnector.get().disconnect()
        );
    }

    private ToolDefinition createBrowserStatusTool() {
        return new ToolDefinition("browser_status",
                "查看当前浏览器 MCP 状态, autoConnect 引导和 CDP 端口探活状态",
                ToolSchema.createParameters(),
                args ->
                        browserConnector == null ? "浏览器连接尚未初始化, 无法查看浏览器状态" : browserConnector.get().status()
                );
    }
}
