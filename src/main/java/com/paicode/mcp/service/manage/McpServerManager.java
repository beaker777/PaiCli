package com.paicode.mcp.service.manage;

import com.paicode.mcp.constant.McpServerStatus;
import com.paicode.mcp.entity.McpToolDescription;
import com.paicode.mcp.service.config.McpConfigLoader;
import com.paicode.mcp.service.config.McpServerConfig;
import com.paicode.mcp.service.transport.McpTransport;
import com.paicode.mcp.service.transport.impl.StdioTransport;
import com.paicode.mcp.service.transport.impl.StreamableHttpTransport;
import com.paicode.tool.service.register.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author beaker
 * @Date 2026/9/25 17:30
 * @Description MCP 服务端管理器
 */
public class McpServerManager implements AutoCloseable {

    private final ToolRegistry toolRegistry;
    private final Path projectDir;
    private final McpConfigLoader configLoader;
    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir) {
        this(toolRegistry, projectDir, new McpConfigLoader(projectDir));
    }

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir, McpConfigLoader configLoader) {
        this.toolRegistry = toolRegistry;
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.configLoader = configLoader;
    }

    /**
     * 在配置文件中加载 McpServers
     */
    public void loadConfiguredServers() throws IOException {
        Map<String, McpServerConfig> configs = configLoader.load();

        servers.clear();
        configs.forEach((name, config) -> servers.put(name, new McpServer(name, config)));
    }

    /**
     * 并行启动 mcp servers
     */
    public void startAll() {
        List<McpServer> targets = servers.values().stream()
                .filter(server -> !server.config().isDisabled())
                .toList();
        if (targets.isEmpty()) {
            return;
        }

        // 用专属 daemon executor，避免 npx/uvx 冷启动期间占满 ForkJoinPool.commonPool 影响其他并发任务。
        AtomicInteger threadId = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(targets.size(), 8),
                r -> {
                    Thread t = new Thread(r, "paicode-mcp-startup-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });

        try {
            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(server -> CompletableFuture.runAsync(() -> start(server), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 启动 mcp server
     */
    private void start(McpServer server) {
        // 先注销并关闭 server
        unregisterTools(server);
        server.close();

        // 不可用
        if (server.config().isDisabled()) {
            server.status(McpServerStatus.DISABLED);
            return;
        }

        server.status(McpServerStatus.STARTING);
        server.errorMessage(null);
        try {
            // 在单 server 启动路径里展开 ${VAR} 与校验 transport，
            // 单个失败仅标 ERROR，不会阻塞其他 server。
            configLoader.prepare(server.config());

            // 创建传输层
            McpTransport transport = createTransport(server.config());

            // 创建 Client
            McpClient client = new McpClient(server.name(), transport);
            client.initialize();

            // 检验 tools
            List<McpToolDescription> tools = client.listTools();
            validateNoDuplicateTools(server.name(), tools);

            // 注册 tools
            for (McpToolDescription descriptor : tools) {
                toolRegistry.registerMcpTool(descriptor, args -> invokeMcpTool(client, descriptor, args));
            }
            server.client(client);
            server.tools(tools);
            server.markStarted();
            server.status(McpServerStatus.READY);
        } catch (Exception e) {
            server.close();
            server.errorMessage(e.getMessage());
            server.status(McpServerStatus.ERROR);
        }
    }

    public synchronized String restart(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }

        unregisterTools(server);
        server.close();
        server.config().setDisabled(false);
        start(server);
        return server.status() == McpServerStatus.READY
                ? "✅ MCP server 已重启: " + name
                : "❌ MCP server 重启失败: " + name + " - " + server.errorMessage();
    }

    public synchronized String disable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }

        unregisterTools(server);
        server.close();
        server.config().setDisabled(true);
        server.status(McpServerStatus.DISABLED);
        server.errorMessage(null);
        return "⏸️ MCP server 已禁用: " + name;
    }

    public synchronized String enable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }

        server.config().setDisabled(false);
        start(server);
        return server.status() == McpServerStatus.READY
                ? "▶️ MCP server 已启用: " + name
                : "❌ MCP server 启用失败: " + name + " - " + server.errorMessage();
    }

    public String logs(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }

        List<String> lines = server.logs();
        if (lines.isEmpty()) {
            return "📭 MCP server 暂无 stderr 日志: " + name;
        }
        return String.join(System.lineSeparator(), lines);
    }

    /**
     * 按名称排序获取 mcp servers
     */
    public Collection<McpServer> servers() {
        return servers.values().stream()
                .sorted(Comparator.comparing(McpServer::name))
                .toList();
    }

    /**
     * 返回格式化的 server 状态
     */
    public String formatStatus() {
        StringBuilder sb = new StringBuilder("🔌 MCP Servers\n");
        if (servers.isEmpty()) {
            sb.append("  未配置 MCP server。配置文件: ~/.paicode/mcp.json 或 .paicode/mcp.json");
            return sb.toString();
        }

        for (McpServer server : servers()) {
            String status = switch (server.status()) {
                case READY -> "● ready";
                case STARTING -> "… starting";
                case DISABLED -> "○ disabled";
                case ERROR -> "✗ error";
            };

            String tools = server.status() == McpServerStatus.READY
                    ? server.tools().size() + (server.tools().size() == 1 ? " tool" : " tools")
                    : "—";
            String uptime = server.status() == McpServerStatus.READY ? "uptime " + formatDuration(server.uptime()) : "";
            String pid = server.processId() == null ? "" : "pid " + server.processId();
            String error = server.status() == McpServerStatus.ERROR && server.errorMessage() != null
                    ? server.errorMessage()
                    : "";
            sb.append(String.format("  %-14s %-11s %-6s %-9s %-10s %s %s%n",
                    server.name(), status, server.transportName(), tools, uptime, pid, error));
        }

        return sb.toString().trim();
    }

    /**
     * 启动总结
     */
    public String startupSummary() {
        if (servers.isEmpty()) {
            return "🔌 MCP server：未配置（可创建 ~/.paicli/mcp.json 或 .paicli/mcp.json）";
        }

        long ready = servers.values().stream().filter(s -> s.status() == McpServerStatus.READY).count();
        int tools = servers.values().stream().mapToInt(s -> s.tools().size()).sum();
        StringBuilder sb = new StringBuilder("🔌 启动 MCP server（" + servers.size() + " 个）...\n");
        for (McpServer server : servers()) {
            if (server.status() == McpServerStatus.READY) {
                sb.append(String.format("   ✓ %-14s %-6s %3d 工具%n",
                        server.name(), server.transportName(), server.tools().size()));
            } else if (server.status() == McpServerStatus.DISABLED) {
                sb.append(String.format("   ○ %-14s %-6s disabled%n", server.name(), server.transportName()));
            } else {
                sb.append(String.format("   ✗ %-14s %-6s 启动失败: %s%n",
                        server.name(), server.transportName(), server.errorMessage()));
            }
        }
        sb.append("   ").append(ready).append("/").append(servers.size())
                .append(" 就绪，共 ").append(tools).append(" 个 MCP 工具");
        return sb.toString();
    }

    private static String invokeMcpTool(McpClient client, McpToolDescription descriptor, String argumentsJson) {
        try {
            return client.callTool(descriptor.name(), argumentsJson);
        } catch (Exception e) {
            return "MCP 工具调用失败 (" + descriptor.serverName() + "/" + descriptor.name() + "): " + e.getMessage();
        }
    }

    /**
     * 创建 transport
     */
    private McpTransport createTransport(McpServerConfig config) throws IOException {
        if (config.isHttp()) {
            return new StreamableHttpTransport(config.getUrl(), config.getHeaders());
        }
        return new StdioTransport(config.getCommand(), config.getArgs(), config.getEnv(), projectDir);
    }

    /**
     * 验证无重复工具
     */
    private void validateNoDuplicateTools(String serverName, List<McpToolDescription> tools) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (McpToolDescription tool : tools) {
            counts.merge(tool.name(), 1, Integer::sum);
        }

        List<String> duplicates = new ArrayList<>();
        counts.forEach((name, count) -> {
            if (count > 1) duplicates.add(name);
        });

        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("MCP server " + serverName + " 返回重复工具名: " + duplicates);
        }
    }

    /**
     * 注销 mcp server
     */
    private void unregisterTools(McpServer server) {
        for (McpToolDescription tool : server.tools()) {
            toolRegistry.unregisterMcpTool(tool.namespacedName());
        }

        server.tools(List.of());
    }

    /**
     * 格式化持续时间
     */
    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) return seconds + "s";

        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";

        return (minutes / 60) + "h";
    }

    @Override
    public void close() {
        for (McpServer server : servers.values()) {
            unregisterTools(server);
            server.close();
        }
    }
}
