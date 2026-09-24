package com.paicode.tool.service.register;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.llm.entity.Tool;
import com.paicode.policy.entity.AuditEntry;
import com.paicode.policy.exception.PolicyException;
import com.paicode.policy.service.audit.AuditLog;
import com.paicode.policy.service.guard.PathGuard;
import com.paicode.tool.entity.ToolDefinition;
import com.paicode.tool.entity.ToolExecutionResult;
import com.paicode.tool.entity.ToolInvocation;
import com.paicode.tool.service.tools.*;
import lombok.Getter;

import java.util.*;
import java.util.concurrent.*;

/**
 * @Author beaker
 * @Date 2026/9/8 22:07
 * @Description 工具注册表
 */
@Getter
public class ToolRegistry {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    private String projectPath = System.getProperty("user.dir");

    // 需要审计的工具 (与 ApprovalPolicy 的 DANGEROUS_TOOLS 保持一致)
    private static final Set<String> AUDIT_TOOLS = Set.of("write_file", "execute_command", "create_project");
    private PathGuard pathGuard = new PathGuard(projectPath);
    private final AuditLog auditLog = new AuditLog();

    private final WebSearchTools webSearchTools = new WebSearchTools();
    private final FileTools fileTools = new FileTools(() -> pathGuard);
    private final CodeTools codeTools = new CodeTools(() -> pathGuard);

    private final long commandTimeoutSeconds;
    private final long toolBatchTimeoutSeconds;
    public static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    public static final int MAX_PARALLEL_TOOLS = 4;
    public static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;

    public ToolRegistry() {
        this(DEFAULT_COMMAND_TIMEOUT_SECONDS, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
    }

    public ToolRegistry(long commandTimeoutSeconds) {
        this(commandTimeoutSeconds, Math.max(commandTimeoutSeconds + 5, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS));
    }

    // 注册工具
    public ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;

        register(fileTools.create());
        register(ShellTools.create(projectPath, this.commandTimeoutSeconds));
        register(codeTools.create());
        register(RagTools.create(projectPath));
        register(webSearchTools.create());
    }

    private void register(List<ToolDefinition> toolDefinitions) {
        for (ToolDefinition toolDefinition : toolDefinitions) {
            tools.put(
                    toolDefinition.name(),
                    toolDefinition
            );
        }
    }

    // 获取工具列表
    public List<Tool> getTools() {
        return tools.values().stream()
                .map(t -> new Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    // 执行工具调用
    public String executeTool(String name, String argumentJson) {
        ToolDefinition toolDefinition = tools.get(name);
        if (toolDefinition == null) {
            return "未知工具: " + name;
        }

        boolean shouldAudit = AUDIT_TOOLS.contains(name);
        long start = System.nanoTime();

        try {
            JsonNode args = mapper.readTree(argumentJson);
            Map<String, String> argMap = new HashMap<>();
            args.fields().forEachRemaining(
                    entry -> argMap.put(entry.getKey(), entry.getValue().asText())
            );

            String result = toolDefinition.executor().execute(argMap);
            if (shouldAudit) {
                auditLog.record(AuditEntry.allow(name, argumentJson, elapsedMillis(start)));
            }
            return result;
        } catch (PolicyException e) {
            if (shouldAudit) {
                auditLog.record(AuditEntry.denyByPolicy(name, argumentJson, e.getMessage(), elapsedMillis(start)));
            }

            return "🛡️ 策略拒绝: " + e.getMessage();
        } catch (Exception e) {
            if (shouldAudit) {
                auditLog.record(AuditEntry.error(name, argumentJson, e.getMessage(), elapsedMillis(start)));
            }

            return "工具执行失败: " + e.getMessage();
        }
    }

    // 并行执行同一轮 LLM 返回的多个 ToolCall
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }

        if (invocations.size() == 1) {
            // 单个工具调用直接执行
            ToolInvocation invocation = invocations.get(0);
            long startedAt = System.nanoTime();

            String result = executeTool(invocation.name(), invocation.argumentsJson());
            return List.of(ToolExecutionResult.completed(invocation, result, elapsedMillis(startedAt)));
        }

        // 创建线程池
        int parallelSize = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        ExecutorService executors = Executors.newFixedThreadPool(parallelSize, r -> {
            Thread thread = new Thread(r, "paicode-tool-executor");
            thread.setDaemon(true);
            return thread;
        });

        // 并行执行工具
        try {
            List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                    .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                        long startedAt = System.nanoTime();
                        String result = executeTool(invocation.name(), invocation.argumentsJson());
                        return ToolExecutionResult.completed(invocation, result, elapsedMillis(startedAt));
                    })
                    .toList();

            List<Future<ToolExecutionResult>> futures = executors
                    .invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ToolInvocation invocation = invocations.get(i);
                Future<ToolExecutionResult> future = futures.get(i);
                if (future.isCancelled()) {
                    results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                    continue;
                }

                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ToolExecutionResult.failed(invocation, "工具执行被中断"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause == null || cause.getMessage() == null ? "未知错误" : cause.getMessage();
                    results.add(ToolExecutionResult.failed(invocation, message));
                }
            }

            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "工具批次执行被中断"))
                    .toList();
        } finally {
            executors.shutdownNow();
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
        this.pathGuard = new PathGuard(projectPath);
    }
}
