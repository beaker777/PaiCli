package com.paicode.cli;

import com.paicode.agent.Agent;
import com.paicode.agent.PlanAndExecuteAgent;
import com.paicode.cli.DTO.PrefillResult;
import com.paicode.cli.DTO.PromptInput;
import com.paicode.cli.constant.AgentMode;
import com.paicode.cli.parser.CliCommandParser;
import com.paicode.cli.parser.DTO.Decision;
import com.paicode.cli.parser.DTO.ParsedCommand;
import com.paicode.cli.parser.PlanReviewInputParser;
import com.paicode.cli.parser.constant.CommandType;
import com.paicode.plan.DTO.PlanReviewDecision;
import com.paicode.plan.ExecutionPlan;
import com.paicode.plan.PlanReviewHandler;
import org.jline.reader.*;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Scanner;

/**
 * @Author beaker
 * @Date 2026/9/9 19:56
 * @Description PaiCode v2.0 支持 ReAct 和 Plan
 */
public class Main {

    private static final String VERSION = "2.0.0";
    private static final String ENV_FILE = ".env";
    private static final String BRACKETED_PASTE_BEGIN = "[200~";
    private static final String BRACKETED_PASTE_END = "\u001b[201~";
    private static final int CTRL_O = 15;

    public static void main(String[] args) throws IOException {
        printBanner();

        // 加载 API Key
        String apiKey = loadApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("错误: 未找到 API_KEY");
            System.err.println("请在 .env 文件中添加: API_KEY=your_api_key_here");
            System.exit(1);
        }

        System.out.println("API Key 已加载\n");

        // 使用 try-with-resource 确保 Terminal 正确关闭
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();
            lineReader.option(LineReader.Option.BRACKETED_PASTE, true);

            // 默认使用 ReAct 模式
            Agent reactAgent = new Agent(apiKey);
            System.out.println("使用 ReAct 模式\n");
            boolean nextTaskUsePlanMode = false;

            System.out.println("💡 提示:");
            System.out.println("   - 输入你的问题或任务");
            System.out.println("   - 输入 'mode' 切换执行模式");
            System.out.println("   - 输入 '/plan' 进入 Plan-and-Execute 模式"); // 执行完毕后会自动返回 ReAct 模式
            System.out.println("   - 输入 '/plan 任务内容' 直接用计划模式执行任务");
            System.out.println("   - 默认模式是 ReAct");
            System.out.println("   - 输入 'clear' 清空对话历史");
            System.out.println("   - 输入 'exit' 或 'quit' 退出\n");

            while (true) {
                // 获取用户输入
                PromptInput promptInput;
                try {
                    promptInput = readPromptInput(terminal, lineReader, nextTaskUsePlanMode);
                } catch (UserInterruptException e) {
                    // ctrl + c 跳过
                    continue;
                } catch (EndOfFileException e) {
                    // ctrl + d 退出
                    break;
                }

                if (promptInput.canceled()) {
                    if (nextTaskUsePlanMode) {
                        nextTaskUsePlanMode = false;
                        System.out.println("已取消待执行的 plan, 回到默认的 React 模式. \n");
                    }
                    continue;
                }

                // 获取用户输入 (非 Esc)
                String input = promptInput.text().trim();
                if (input.isEmpty()) {
                    continue;
                }

                // 处理特殊命令
                ParsedCommand command = CliCommandParser.parse(input);
                switch (command.type()) {
                    case EXIT -> {
                        System.out.println("\n再见!");
                        return;
                    }
                    case CLEAR -> {
                        reactAgent.clearHistory();
                        System.out.println("对话历史已清空\n");
                        continue;
                    }
                    case SWITCH_PLAN -> {
                        // 不携带命令, 下个任务使用 plan 模式
                        if (command.payload() == null || command.payload().isEmpty()) {
                            nextTaskUsePlanMode = true;
                            System.out.println("下一次输入将使用 Plan 模式, 输入任务前按 Esc 可取消.\n");

                            continue;
                        }

                        // 携带命令, 直接使用 plan 模式
                        input = command.payload();
                    }
                    case NONE -> {
                    }
                }


                // 运行 Agent
                System.out.println();
                String response;
                if (nextTaskUsePlanMode || command.type() == CommandType.SWITCH_PLAN) {
                    PlanAndExecuteAgent planAgent = createPlanAgent(apiKey, terminal, lineReader);
                    response = planAgent.run(input);
                    nextTaskUsePlanMode = false;
                } else {
                    response = reactAgent.run(input);
                }
                System.out.println("Agent: " + response);
                System.out.println();
            }
        }
    }

    /**
     * 从 .env 文件加载 API Key
     */
    private static String loadApiKey() {
        File envFile = new File(ENV_FILE);

        // 先尝试从当前目录读取
        if (envFile.exists()) {
            return readApiKeyFromFile(envFile);
        }

        // 再尝试从用户主目录读取
        envFile = new File(System.getProperty("user.home"), ENV_FILE);
        if (envFile.exists()) {
            return readApiKeyFromFile(envFile);
        }

        // 最后尝试从环境变量读取
        String envKey = System.getenv("GLM_API_KEY");
        if (envKey != null && !envKey.isEmpty()) {
            return envKey;
        }

        return null;
    }

    private static String readApiKeyFromFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("DEEPSEEK_API_KEY=")) {
                    return line.substring("DEEPSEEK_API_KEY=".length()).trim();
                }
            }
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
        return null;
    }

    private static PlanAndExecuteAgent createPlanAgent(String apiKey, Terminal terminal, LineReader lineReader) {
        System.out.println("使用 Plan-and-Execute 模式\n");
        return new PlanAndExecuteAgent(apiKey, createPlanReviewHandler(terminal, lineReader));
    }

    private static PromptInput readPromptInput(Terminal terminal, LineReader lineReader, boolean allowEscCancel)
            throws UserInterruptException, EndOfFileException {
        // 不允许使用 Esc 取消
        if (!allowEscCancel) {
            // 直接读取用户输入并返回
            return PromptInput.submitted(lineReader.readLine("你: "));
        }

        // 允许使用 Esc 取消
        String prompt = "你: ";
        System.out.println(prompt);
        System.out.flush();

        PrefillResult prefill = readPrefillInputFromTerminal(terminal);
        if (prefill == null) {
            return PromptInput.submitted(lineReader.readLine(""));
        }

        if (prefill.canceled()) {
            System.out.println();
            return PromptInput.canceledInput();
        }

        if (prefill.submitted()) {
            System.out.println();
            return PromptInput.submitted("");
        }

        return PromptInput.submitted(lineReader.readLine("", null, (MaskingCallback) null, prefill.seedBuffer()));
    }

    private static PrefillResult readPrefillInputFromTerminal(Terminal terminal) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();

            try {
                int key = terminal.reader().read();
                // 未读取到有效内容
                if (key < 0) {
                    return null;
                }

                // 处理读取到 Esc
                if (key == 27) {
                    return readEscapeInput(terminal);
                }

                // 处理读取到回车
                if (isSubmitKey(key)) {
                    return PrefillResult.submittedInput();
                }

                // 正常读取内容
                String rawInput = switch (key) {
                    case 8, 127 -> "";
                    default -> Character.toString((char) key);
                };

                rawInput += readInputBurst(terminal, 20, 25, 250);
                return PrefillResult.seed(prepareSeedBuffer(rawInput));
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static PrefillResult readEscapeInput(Terminal terminal) throws IOException, InterruptedException {
        String sequence = readInputBurst(terminal, 30, 25, 250);

        // 只输入了 Esc 确定为退出
        if (sequence.isEmpty()) {
            return PrefillResult.canceledInput();
        }

        // 如果为粘贴, 返回粘贴的文本
        if (sequence.startsWith(BRACKETED_PASTE_BEGIN)) {
            String pastedText = sequence.substring(BRACKETED_PASTE_BEGIN.length());
            while (!pastedText.contains(BRACKETED_PASTE_END)) {
                String burst = readInputBurst(terminal, 30, 25, 500);
                if (burst.isEmpty()) {
                    break;
                }
                pastedText += burst;
            }

            return PrefillResult.seed(prepareSeedBuffer(stripBracketedPasteEndMarker(pastedText)));
        }

        // 其他情况默认退出
        return PrefillResult.canceledInput();
    }

    private static String readInputBurst(Terminal terminal, long firstWaitMs, long idleWaitMs, long maxWaitMs)
            throws IOException, InterruptedException {
        StringBuilder buffer = new StringBuilder();
        long start = System.currentTimeMillis();
        long firstDeadline = start + firstWaitMs;
        long idleDeadline = 0;

        while (System.currentTimeMillis() - start < maxWaitMs) {
            // 在 maxWaitMs 之内读取用户输入的内容
            if (terminal.reader().ready()) {
                int next = terminal.reader().read();
                if (next < 0) {
                    break;
                }
                buffer.append((char) next);
                idleDeadline = System.currentTimeMillis() + idleWaitMs;
                continue;
            }

            long now = System.currentTimeMillis();
            if (buffer.isEmpty()) {
                if (now >= firstDeadline) {
                    // 如果超过 firstWaitMs 还没有内容直接返回
                    break;
                }
            } else if (now >= idleDeadline) {
                // 如果输入最后一个字符后超过 idleWaitMs 还没有内容直接返回
                break;
            }

            Thread.sleep(5);
        }

        return buffer.toString();
    }

    static String prepareSeedBuffer(String rawInput) {
        if (rawInput == null || rawInput.isEmpty()) {
            return "";
        }
        return normalizeLineEndings(rawInput);
    }

    static String normalizeLineEndings(String rawInput) {
        return rawInput
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static String stripBracketedPasteEndMarker(String rawInput) {
        int endMarkerIndex = rawInput.indexOf(BRACKETED_PASTE_END);
        if (endMarkerIndex >= 0) {
            return rawInput.substring(0, endMarkerIndex);
        }
        return rawInput;
    }

    private static boolean isSubmitKey(int key) {
        return key == '\n' || key == '\r' || key == 13 || key == 10;
    }

    private static PlanReviewHandler createPlanReviewHandler(Terminal terminal, LineReader lineReader) {
        return (String goal, ExecutionPlan plan) -> {
            boolean expanded = false;
            System.out.println(plan.summarize());
            System.out.println("计划已生成。");
            System.out.println("   - 回车：按当前计划执行");
            System.out.println("   - Ctrl+O：展开完整计划");
            System.out.println("   - ESC：折叠或取消本次计划");
            System.out.println("   - I：输入补充要求后重新规划\n");

            while (true) {
                Integer key = readSingleKeyFromTerminal(terminal);
                if (key != null) {
                    // Enter (13 或 10)
                    if (key == '\n' || key == '\r' || key == 13 || key == 10) {
                        System.out.println();
                        return PlanReviewDecision.execute();
                    }

                    // ESC (27)
                    if (key == 27) {
                        System.out.println();
                        if (expanded) {
                            expanded = false;
                            System.out.println(plan.summarize());
                            System.out.println("已退出完整计划视图，继续按 Enter / Ctrl+O / ESC / I。\n");
                            continue;
                        }
                        return PlanReviewDecision.cancel();
                    }

                    // I 或 i
                    if (key == 'i' || key == 'I') {
                        System.out.println();
                        String supplementInput = lineReader.readLine("补充> ").trim();
                        Decision supplementDecision = PlanReviewInputParser.parse(supplementInput);
                        return mapReviewDecision(supplementDecision);
                    }

                    // Ctrl+O
                    if (key == CTRL_O) {
                        System.out.println();
                        System.out.println(plan.visualize());
                        expanded = true;
                        System.out.println("👆 已展开完整计划，继续按 Enter / Ctrl+O / ESC / I。\n");
                        continue;
                    }

                    System.out.println();
                    System.out.println("未识别按键，请按 Enter / Ctrl+O / ESC / I。\n");
                    continue;
                }

                // 如果无法读取单键，回退到行输入模式
                String decisionInput = lineReader.readLine("操作/补充> ").trim();
                if (decisionInput.equalsIgnoreCase("/view")) {
                    System.out.println();
                    System.out.println(plan.visualize());
                    expanded = true;
                    System.out.println("👆 已展开完整计划，继续输入 Enter / /cancel / 补充要求。\n");
                    continue;
                }
                Decision decision = PlanReviewInputParser.parse(decisionInput);
                return mapReviewDecision(decision);
            }
        };
    }

    private static Integer readSingleKeyFromTerminal(Terminal terminal) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return null;
                }

                // 如果是 ESC，需要 drain 掉后续的方向键序列字节
                if (key == 27) {
                    drainEscapeSequence(terminal);
                }

                return key;
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return null;
        }
    }


    private static void drainEscapeSequence(Terminal terminal) {
        try {
            // 短暂等待，让后续字节到达
            Thread.sleep(50);
            // 检查并丢弃所有待读字节（如方向键序列 [A, [B 等）
            while (terminal.reader().ready()) {
                terminal.reader().read();
            }
        } catch (Exception ignored) {
        }
    }

    private static PlanReviewDecision mapReviewDecision(Decision decision) {
        return switch (decision.type()) {
            case EXECUTE -> PlanReviewDecision.execute();
            case CANCEL -> PlanReviewDecision.cancel();
            case SUPPLEMENT -> PlanReviewDecision.supplement(decision.feedback());
        };
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                                                          ║");
        System.out.println("║   ██████╗  █████╗ ██╗ ██████╗██╗     ██╗                ║");
        System.out.println("║   ██╔══██╗██╔══██╗██║██╔════╝██║     ██║                ║");
        System.out.println("║   ██████╔╝███████║██║██║     ██║     ██║                ║");
        System.out.println("║   ██╔═══╝ ██╔══██║██║██║     ██║     ██║                ║");
        System.out.println("║   ██║     ██║  ██║██║╚██████╗███████╗██║                ║");
        System.out.println("║   ╚═╝     ╚═╝  ╚═╝╚═╝ ╚═════╝╚══════╝╚═╝                ║");
        System.out.println("║                                                          ║");
        System.out.printf("║      Plan-and-Execute Agent CLI %-8s                 ║%n", "v" + VERSION);
        System.out.println("║                                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
