package com.paicode.agent.MultiAgent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.agent.MultiAgent.DTO.AgentMessage;
import com.paicode.agent.MultiAgent.DTO.ExecutionStep;
import com.paicode.agent.MultiAgent.constant.AgentMessageType;
import com.paicode.agent.MultiAgent.constant.AgentRole;
import com.paicode.agent.MultiAgent.constant.StepStatus;
import com.paicode.llm.DeepSeekClient;
import com.paicode.memory.MemoryManager;
import com.paicode.tool.ToolRegistry;
import com.paicode.utils.AnsiStyle;
import org.jline.jansi.Ansi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @Author beaker
 * @Date 2026/9/21 18:54
 * @Description 多 Agent 编排器 - 主 Agent
 */
public class AgentOrchestrator {

    private final static Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private final static ObjectMapper mapper = new ObjectMapper();
    private final static int MAX_RETRIES_PER_STEP = 2;

    private final DeepSeekClient llmClient;
    private final SubAgent planner;
    private final List<SubAgent> workers;
    private final SubAgent reviewer;
    private final MemoryManager memoryManager;
    private final ToolRegistry toolRegistry;

    public AgentOrchestrator(String apiKey) {
        this(apiKey, new ToolRegistry());
    }

    public AgentOrchestrator(String apiKey, ToolRegistry toolRegistry) {
        this(new DeepSeekClient(apiKey), toolRegistry, new MemoryManager(new DeepSeekClient(apiKey)));
    }

    public AgentOrchestrator(String apikey, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this(new DeepSeekClient(apikey), toolRegistry, memoryManager);
    }

    public AgentOrchestrator(DeepSeekClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;

        this.planner = new SubAgent("planner", AgentRole.PLANNER, llmClient, toolRegistry);
        this.workers = List.of(
                new SubAgent("worker-01", AgentRole.WORKER, llmClient, toolRegistry),
                new SubAgent("worker-02", AgentRole.WORKER, llmClient, toolRegistry)
        );
        this.reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, toolRegistry);
    }

    /**
     * 运行多 Agent 任务
     */
    public String run(String userInput) {
        log.info("Multi-Agent run started: inputLength={}", userInput == null ? 0 : userInput.length());
        memoryManager.addUserMessage(userInput);

        // 规划阶段, planner 制定任务
        System.out.println(AnsiStyle.heading("第一阶段, 规划: "));
        System.out.println("规划者正在分析任务...\n");

        AgentMessage planMessage = AgentMessage.task("orchestrator", "请为以下任务制定执行计划: \n" + userInput);
        AgentMessage planResult = planner.execute(planMessage);
        planner.clearHistory();

        if (planResult.type() == AgentMessageType.ERROR) {
            return "规划失败, LLM 调用出错: " + planResult.content();
        }
        if (planResult.content() == null || planResult.content().isBlank()) {
            return "规划失败, 未能生成有效计划";
        }

        // 解析计划
        List<ExecutionStep> steps = parsePlan(planResult.content());
        if (steps.isEmpty()) {
            return "规划失败, 无法解析执行计划\n原始输出: " + planResult.content();
        }

        System.out.println(AnsiStyle.heading("执行计划"));
        System.out.println(summarizeSteps(steps) + "\n");

        // 执行计划, 按顺序分配给 worker
        System.out.println(AnsiStyle.heading("第二阶段, 执行:"));
        Map<String, Integer> retryCount = new ConcurrentHashMap<>();
        int singleStepCursor = 0;
        int batchIndex = 0;

        while (true) {
            List<ExecutionStep> executable = getExecutableTasks(steps);
            if (executable.isEmpty()) {
                break;
            }
            batchIndex ++;

            if (executable.size() == 1) {
                // 单个任务, 直接执行
                ExecutionStep step = executable.get(0);
                SubAgent worker = workers.get(singleStepCursor % workers.size());
                String context = buildStepContext(steps, step);

                runStep(step, steps, retryCount, worker, reviewer, context, System.out);
                worker.clearHistory();
                singleStepCursor ++;
            } else {
                // 多个任务并行执行
                System.out.println("批次 #" + batchIndex + ": " + executable.size() + " 个独立步骤并行执行" +
                        " (最多 " + workers.size() + " 个并发 Worker)\n");
                runBatchParallel(executable, steps, retryCount);
            }
        }

        // 处理因前置任务失败无法执行的步骤
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                System.out.println("步骤 [" + step.id() + "] 因前置步骤失败被跳过: " + step.description());
            }
        }

        // 汇总结果
        String finalResult = buildFinalResult(steps);
        memoryManager.addAssistantMessage("[多 Agent 结果]: " + finalResult);

        return finalResult;
    }

    /**
     * 解析规划者输出的 JSON 计划
     */
    private List<ExecutionStep> parsePlan(String planJson) {
        try {
            String cleaned = planJson.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();

            JsonNode root = mapper.readTree(cleaned);
            JsonNode stepsNode = root.path("steps");

            // 获取 steps
            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                // 尝试 task 字段
                stepsNode = root.path("tasks");
            }
            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                log.warn("Plan JSON has no 'steps' or 'tasks' array");
                return List.of();
            }

            List<ExecutionStep> steps = new ArrayList<>();
            Map<String, String> idMapping = new HashMap<>();
            int stepIndex = 1;

            // 创建 steps
            for (JsonNode stepNode : stepsNode) {
                String originalId = stepNode.path("id").asText();
                String newId = "step_" + stepIndex ++;
                idMapping.put(originalId, newId);

                String description = stepNode.path("description").asText();
                String type = stepNode.path("type").asText("COMMAND");
                steps.add(ExecutionStep.pending(newId, description, type, new ArrayList<>()));
            }

            // 解析依赖关系
            stepIndex = 1;
            for (JsonNode stepNode : stepsNode) {
                String newId = "step_" + stepIndex ++;
                JsonNode depsNode = stepNode.path("dependencies");

                if (depsNode.isArray()) {
                    List<String> deps = new ArrayList<>();
                    for (JsonNode dep : depsNode) {
                        String depId = idMapping.getOrDefault(dep.asText(), dep.asText());
                        deps.add(depId);
                    }

                    // index 自增过, 且 list 从 0 开始
                    int idx = stepIndex - 2;
                    if (idx >= 0 && idx < steps.size()) {
                        ExecutionStep old = steps.get(idx);
                        steps.set(idx, new ExecutionStep(
                                old.id(), old.description(), old.type(), deps, old.result(), old.status()));
                    }
                }
            }

            return steps;
        } catch (Exception e) {
            log.error("Failed to parse plan Json", e);
            return List.of();
        }
    }

    /**
     * 总结执行任务的步骤
     */
    private String summarizeSteps(List<ExecutionStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (ExecutionStep step : steps) {
            String deps = step.dependencies().isEmpty() ? "无" : String.join(", ", step.dependencies());
            sb.append(String.format("[%s] %s (依赖 %s)%n", step.id(), step.description(), deps));
        }

        return sb.toString();
    }

    /**
     * 获取可执行的任务
     */
    List<ExecutionStep> getExecutableTasks(List<ExecutionStep> steps) {
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> step.dependencies().stream()
                        .allMatch(dep -> statusMap.get(dep) == StepStatus.COMPLETED))
                .toList();
    }

    /**
     * 创建执行步骤的上下文
     */
    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        StringBuilder context = new StringBuilder();
        context.append("总任务上下文: \n");

        // 将完成的依赖加入上下文
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && currentStep.dependencies().contains(step.id())) {
                context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                        .append(step.description()).append("\n");

                if (step.result() != null && !step.result().isBlank()) {
                    String preview = step.result().length() > 500 ? step.result().substring(0, 500) + "..." : step.result();
                    context.append("结果: ").append(preview).append("\n");
                }
                context.append("\n");
            }
        }

        return context.toString();
    }

    /**
     * 执行单个任务 (worker 执行 + reviewer 审查 + 最多两次重试)
     */
    private void runStep(ExecutionStep step, List<ExecutionStep> steps, Map<String, Integer> retryCount,
                         SubAgent worker, SubAgent reviewer, String context, PrintStream out) {
        // 执行任务
        out.println(worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());

        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = worker.executeWithContext(taskMsg, context, out);

        if (result.type() == AgentMessageType.ERROR) {
            updateStep(steps, step.id(), step.withFailed(result.content()));
            out.println("步骤 [" + step.id() + "] 执行失败: " + result.content());
        }
        if (result.content() == null || result.content().isBlank()) {
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            out.println("步骤 [" + step.id() + "] 执行失败: 执行结果为空\n");
        }

        // 进行审查
        out.println(reviewer.getName() + " 审查步骤 [" + step.id() + "] 的结果");
        AgentMessage reviewResult = reviewer.review(step.description(), result.content(), out);
        reviewer.clearHistory();

        if (reviewResult.type() ==AgentMessageType.ERROR) {
            log.warn("Reviewer failed for step {}: {}", step.id(), reviewResult.content());
            out.println("步骤 [" + step.id() + "] 审查阶段 LLM 调用失败，保留当前执行结果\n");
            updateStep(steps, step.id(), step.withResult(result.content()));

            return;
        }

        // 解析审查结果
        boolean approved = parseReviewApproval(reviewResult.content());
        String acceptedResult = result.content();

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println("步骤 [" + step.id() + "] 审查通过\n");
            return;
        }

        // 解析审查反馈的问题
        Integer retries = retryCount.getOrDefault(step.id(), 0);
        String issues = parseReviewIssues(reviewResult.content());
        log.info("Step {} rejected (retry {}/{}): {}", step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        // 进行重试
        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retries ++;
            retryCount.put(step.id(), retries);
            out.println("步骤 [" + step.id() + "] 审查未通过, 正在重新执行...");
            out.println("反馈: " + issues + "\n");

            String feedbackContext = context + "\n\n之前执行的结果被审查阶段拒绝, 原因: " + issues;
            AgentMessage retryResult = worker.executeWithContext(taskMsg, feedbackContext);
            if (retryResult.type() == AgentMessageType.ERROR) {
                log.warn("Step {} retry {} failed at LLM layer: {}", step.id(), retries, retryResult.content());
                issues = "重试时 LLM 调用失败: " + retryResult.content();
                approved = false;
                continue;
            }
            if (retryResult.content() == null || retryResult.content().isBlank()) {
                log.info("Step {} retry {} returned empty result", step.id(), retries);
                issues = "执行结果为空";
                acceptedResult = "执行结果为空";
                approved = false;
                continue;
            }

            acceptedResult = retryResult.content();
            AgentMessage retryReview = reviewer.review(step.description(), acceptedResult, out);
            reviewer.clearHistory();

            if (retryReview.type() == AgentMessageType.ERROR) {
                log.warn("Reviewer failed for step {} retry {}: {}", step.id(), retries, retryReview.content());
                approved = true;
                issues = "";
                break;
            }

            approved = parseReviewApproval(retryReview.content());
            issues = parseReviewIssues(retryReview.content());
        }

        updateStep(steps, step.id(), step.withResult(acceptedResult));
        if (approved) {
            out.println("步骤 [" + step.id() + "] 重试后审查通过\n");
        } else {
            out.println("步骤 [" + step.id() + "] 超过最大重试次数，保留当前结果\n");
        }
    }

    /**
     * 遍历查找待更新的 step (stepId 是字符串)
     */
    private synchronized void updateStep(List<ExecutionStep> steps, String stepId, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    /**
     * 解析 reviewer 的审查结果, 解析失败时默认不通过
     */
    private boolean parseReviewApproval(String reviewContent) {
        if (reviewContent == null || reviewContent.isBlank()) {
            log.warn("Reviewer returned empty content, default to rejected");
            return false;
        }

        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode approvedNode = root.path("approved");

            if (approvedNode.isMissingNode() || approvedNode.isNull()) {
                log.warn("Reviewer JSON missing 'approved' field, defaulting to rejected");
                return false;
            }
            return approvedNode.asBoolean(false);
        } catch (Exception e) {
            // 无法解析 JSON: 必须同时不含否定关键词且含有肯定关键词, 才视为通过
            String lower = reviewContent.toLowerCase();
            boolean hasNegativeKeyword = lower.contains("未通过") || lower.contains("不通过")
                    || lower.contains("不合格") || lower.contains("有问题")
                    || lower.contains("\"approved\": false") || lower.contains("\"approved\":false");
            boolean hasPositiveKeyword = lower.contains("通过") || lower.contains("合格")
                    || lower.contains("\"approved\": true") || lower.contains("\"approved\":true");

            if (hasNegativeKeyword) {
                return false;
            }
            if (!hasPositiveKeyword) {
                log.warn("Reviewer output unparseable and contains no explicit approval, defaulting to rejected");
                return false;
            }
            return true;
        }
    }

    /**
     * 解析 reviewer 反馈的问题
     */
    private String parseReviewIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isBlank()) {
            return "";
        }

        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);

            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isArray() && !issuesNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();

                for (JsonNode issueNode : issuesNode) {
                    sb.append("-").append(issueNode.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            JsonNode suggestionsNode = root.path("suggestions");
            if (suggestionsNode.isArray() && !suggestionsNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();

                for (JsonNode suggestion : suggestionsNode) {
                    sb.append("-").append(suggestion.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            // 返回 summary 作为兜底
            String summary = root.path("summary").asText();
            if (!summary.isBlank()) {
                return summary;
            }
        } catch (Exception ignored) {

        }

        return "审查未通过, 请改进执行结果";
    }

    /**
     * 并行执行一批任务
     */
    private void runBatchParallel(List<ExecutionStep> batch, List<ExecutionStep> steps, Map<String, Integer> retryCount) {
        int parallelSize = Math.min(batch.size(), workers.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelSize, r -> {
            Thread t = new Thread(r, "paicode-multi-agent");
            t.setDaemon(true);
            return t;
        });
        BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);
        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (ExecutionStep step : batch) {
            // 创建 PrintStream
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);

            // 执行步骤
            String context = buildStepContext(steps, step);
            futures.add(executor.submit(() -> {
                SubAgent worker = null;
                SubAgent localReviewer = new SubAgent("reviewer-" + step.id(), AgentRole.REVIEWER, llmClient, toolRegistry);

                try {
                    worker = workerPool.take();
                    runStep(step, steps, retryCount, worker, localReviewer, context, stepOut);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                    stepOut.println("步骤 [" + step.id() + "] 被中断\n");
                } catch (RuntimeException e) {
                    log.error("Parallel step {} failed unexpectedly", step.id(), e);
                    updateStep(steps, step.id(), step.withFailed("并行执行异常: " + e.getMessage()));
                    stepOut.println("步骤 [" + step.id() + "] 并行执行异常: " + e.getMessage());
                } finally {
                    if (worker != null) {
                        worker.clearHistory();
                        workerPool.offer(worker);
                    }
                    stepOut.flush();
                }

                return;
            }));
        }

        // 获取结果
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Batch wait interrupted");
            } catch (ExecutionException e) {
                log.error("Parallel step task failed", e.getCause());
            }
        }
        executor.shutdownNow();

        // 输出结果, 按 step_id 的顺序 flush 输出
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream baos = buffers.get(step.id());
            if (baos != null && baos.size() > 0) {
                System.out.println(baos.toString(StandardCharsets.UTF_8));
                System.out.flush();
            }
        }
    }

    /**
     * 构建最终汇总返回给客户
     */
    private String buildFinalResult(List<ExecutionStep> steps) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
        boolean hasFailedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);

        if (allCompleted) {
            result.append("多 Agent 协作任务完成!\n\n");
        } else if (hasFailedSteps) {
            result.append("多 Agent 协作任务未完全完成, 存在失败步骤\n\n");
        } else {
            result.append("多 Agent 协作任务部分完成, 仍有未执行步骤\n\n");
        }
        result.append("执行总结: \n");

        for (ExecutionStep step : steps) {
            result.append("[").append(step.id()).append("] ");
            if (step.status() == StepStatus.COMPLETED) {
                result.append("✅ ");
            } else if (step.status() == StepStatus.FAILED) {
                result.append("❌ ");
            } else {
                result.append("⏳ ");
            }
            result.append(step.description()).append("\n");

            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 120
                        ? step.result().substring(0, 120) + "..."
                        : step.result();
                result.append("   结果: ").append(preview).append("\n");
            }
        }

        return result.toString();
    }
}
