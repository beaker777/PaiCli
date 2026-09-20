package com.paicode.cli;

import com.paicode.agent.ReAct.Agent;
import com.paicode.agent.PlanAndExecute.PlanAndExecuteAgent;
import com.paicode.cli.DTO.KeyReadResult;
import com.paicode.cli.DTO.PrefillResult;
import com.paicode.cli.DTO.PromptInput;
import com.paicode.cli.constant.EscapeSequenceType;
import com.paicode.cli.parser.CliCommandParser;
import com.paicode.cli.parser.DTO.Decision;
import com.paicode.cli.parser.DTO.ParsedCommand;
import com.paicode.cli.parser.PlanReviewInputParser;
import com.paicode.cli.parser.constant.CommandType;
import com.paicode.agent.PlanAndExecute.service.review.DTO.PlanReviewDecision;
import com.paicode.plan.ExecutionPlan;
import com.paicode.agent.PlanAndExecute.service.review.PlanReviewHandler;
import com.paicode.rag.CodeIndex;
import com.paicode.rag.service.retrieve.CodeRetriever;
import com.paicode.rag.DTO.CodeRelation;
import com.paicode.rag.DTO.IndexResult;
import com.paicode.rag.DTO.IndexStats;
import com.paicode.rag.DTO.SearchResult;
import com.paicode.rag.service.retrieve.SearchResultFormatter;
import org.jline.reader.*;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlocking;
import org.jline.utils.NonBlockingReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/9 19:56
 * @Description PaiCode v4.0 支持 RAG 检索代码库
 */
public class Main {

    private static final String VERSION = "4.0.0";
    private static final String ENV_FILE = ".env";

    // 日志相关配置
    private static final String LOG_DIR_PROPERTY = "paicode.log.dir";
    private static final String LOG_LEVEL_PROPERTY = "paicode.log.level";
    private static final String LOG_MAX_HISTORY_PROPERTY = "paicode.log.maxHistory";
    private static final String LOG_MAX_FILE_SIZE_PROPERTY = "paicode.log.maxFileSize";
    private static final String LOG_TOTAL_SIZE_CAP_PROPERTY = "paicode.log.totalSizeCap";

    // terminal 处理
    private static final String BRACKETED_PASTE_BEGIN = "[200~";
    private static final String BRACKETED_PASTE_END = "\u001b[201~";
    private static final int CTRL_O = 15;
    private static final String ARROW_UP = "[A";
    private static final String ARROW_DOWN = "[B";
    private static final String APP_ARROW_UP = "OA";
    private static final String APP_ARROW_DOWN = "OB";

    public static void main(String[] args) throws Exception {
        printBanner();
        configureLogging();

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

            // 输出命令目录
            printStartupHints();

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
                    case UNKNOWN_COMMAND -> {
                        System.out.println("未知命令: " + command.payload());
                        System.out.println("可用命令: /plan, /clear, /memory, /save, /index, /search, /graph, /exit\n");
                        continue;
                    }
                    case EXIT -> {
                        System.out.println("\n再见!");
                        return;
                    }
                    case CLEAR -> {
                        reactAgent.clearHistory();
                        System.out.println("对话历史已清空\n");
                        continue;
                    }
                    case MEMORY_STATUS -> {
                        System.out.println("记忆状态: ");
                        System.out.println(reactAgent.getSystemStatus());
                        System.out.println();
                        continue;
                    }
                    case MEMORY_SAVE -> {
                        String fact = command.payload();
                        if (fact == null || fact.isBlank()) {
                            System.out.println("请提供需要保存的内容, 例如: /save 这个项目使用 Java17");
                        } else {
                            reactAgent.getMemoryManager().storeFact(fact);
                            System.out.println("已保存到长期记忆: " + fact + "\n");
                        }
                        continue;
                    }
                    case SWITCH_PLAN -> {
                        // 不携带命令, 下个任务使用 plan 模式
                        if (command.payload() == null || command.payload().isEmpty()) {
                            nextTaskUsePlanMode = true;
                            System.out.println("下一次输入将使用 PlanAndExecute 模式, 输入任务前按 Esc 可取消.\n");

                            continue;
                        }

                        // 携带命令, 直接使用 plan 模式
                        input = command.payload();
                    }
                    case INDEX_CODE -> {
                        String indexPath = command.payload() != null ? command.payload() : ".";
                        System.out.println("正在索引代码库: " + indexPath);

                        CodeIndex indexer = new CodeIndex();
                        IndexResult result = indexer.index(indexPath);
                        System.out.println(result.message() + "\n");

                        // 同步项目路径到 toolRegistry
                        String absPath = new File(indexPath).getAbsolutePath();
                        reactAgent.getToolRegistry().setProjectPath(absPath);
                        continue;
                    }
                    case SEARCH_CODE -> {
                        String query = command.payload();
                        if (query == null || query.isBlank()) {
                            System.out.println("请提供检索关键词, 例如: /search 用户登录实现");
                            continue;
                        }

                        System.out.println("正在检索: " + query);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {
                            IndexStats stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {
                                System.out.println("代码库尚未建立索引, 请先使用 /index 命令");
                                continue;
                            }

                            List<SearchResult> searchResults = retriever.hybridSearch(query, 5);
                            if (searchResults.isEmpty()) {
                                System.out.println("未找到相关内容");
                            } else {
                                System.out.println(SearchResultFormatter.formatForCli(query, searchResults) + "\n");
                            }
                        } catch (Exception e) {
                            System.out.println("检索失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case GRAPH_QUERY -> {
                        String className = command.payload();
                        if (className == null || className.isBlank()) {
                            System.out.println("请提供类名, 例如 /graph User");
                            continue;
                        }

                        System.out.println("正在查询类关系图谱: " + className);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {
                            IndexStats stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {
                                System.out.println("代码库尚未建立索引, 请先使用 /index 命令");
                                continue;
                            }

                            List<CodeRelation> relations = retriever.getRelationGraph(className);
                            if (relations.isEmpty()) {
                                System.out.println("未找到相关关系\n");
                            } else {
                                System.out.println("找到 " + relations.size() + " 条关系:\n");
                                for (CodeRelation rel : relations) {
                                    String arrow = rel.relationType().equals("contains") ? "├── contains -->"
                                            : rel.relationType().equals("extends") ? "└── extends -->"
                                              : rel.relationType().equals("implements") ? "└── implements -->"
                                                : rel.relationType().equals("calls") ? "├── calls -->"
                                                  : "├── " + rel.relationType() + " -->";
                                    System.out.printf("   %s %s [%s]%n", rel.fromName(), arrow,
                                            rel.toName() != null ? rel.toName() : "unknown");
                                }
                                System.out.println();
                            }
                        } catch (Exception e) {
                            System.out.println("查询失败: " + e.getMessage() + "\n");
                        }
                        continue;
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
                if (response != null && !response.isBlank()) {
                    System.out.println("Agent: " + response);
                    System.out.println();
                }
            }
        }
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

        PrefillResult prefill = readPrefillInputFromTerminal(terminal, lineReader);
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

    private static PrefillResult readPrefillInputFromTerminal(Terminal terminal, LineReader lineReader) {
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
                    return readEscapeInput(terminal, lineReader);
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

    private static PrefillResult readEscapeInput(Terminal terminal, LineReader lineReader) throws IOException, InterruptedException {
        String sequence = readInputBurst(terminal, 30, 25, 250);
        EscapeSequenceType escapeSequenceType = classifyEscapeSequence(sequence);


        // 只输入了 Esc 确定为退出
        if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
            return PrefillResult.canceledInput();
        }

        // 如果为粘贴, 返回粘贴的文本
        if (escapeSequenceType == EscapeSequenceType.BRACKET_PASTE) {
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

        if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE) {
            return PrefillResult.seed(seedBufferForHistoryNavigation(lineReader, sequence));
        }

        // 其他情况默认退出
        return PrefillResult.canceledInput();
    }

    private static String readInputBurst(Terminal terminal, long firstWaitMs, long idleWaitMs, long maxWaitMs)
            throws IOException, InterruptedException {
        NonBlockingReader reader = terminal.reader();
        StringBuilder buffer = new StringBuilder();
        long start = System.currentTimeMillis();
        long waitMs = firstWaitMs;

        while (System.currentTimeMillis() - start < maxWaitMs) {
            int next = reader.read(waitMs);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                break;
            }

            buffer.append((char) next);
            waitMs = idleWaitMs;
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

     private static String seedBufferForHistoryNavigation(LineReader lineReader, String sequence) {
        if (lineReader == null || sequence == null || sequence.isEmpty()) {
            return "";
        }

        // 如果是上箭头, 显示历史信息
        if (isUpArrowSequence(sequence)) {
            return latestHistoryEntry(lineReader.getHistory());
        }

        if (isDownArrowSequence(sequence)) {
            return "";
        }

        return "";
    }

    private static boolean isUpArrowSequence(String sequence) {
        return ARROW_UP.equals(sequence) || APP_ARROW_UP.equals(sequence);
    }

    private static boolean isDownArrowSequence(String sequence) {
        return ARROW_DOWN.equals(sequence) || APP_ARROW_DOWN.equals(sequence);
    }

    private static String latestHistoryEntry(History history) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        int lastIndex = history.last();
        if (lastIndex < 0) {
            return "";
        }

        String entry = history.get(lastIndex);
        return entry == null ? "" : entry;
    }

    private static boolean isSubmitKey(int key) {
        return key == '\n' || key == '\r';
    }

    private static PlanAndExecuteAgent createPlanAgent(String apiKey, Terminal terminal, LineReader lineReader) {
        System.out.println("使用 PlanAndExecute-and-Execute 模式\n");
        return new PlanAndExecuteAgent(apiKey, createPlanReviewHandler(terminal, lineReader));
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
                KeyReadResult keyReadResult = readSingleKeyFromTerminal(terminal);
                if (keyReadResult.ignoredControlSequence()) {
                    continue;
                }

                Integer key = keyReadResult.key();
                if (key != null) {
                    // Enter (13 或 10)
                    if (key == '\n' || key == '\r') {
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

                // 如果无法读取单键，回退为行输入模式
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

    private static KeyReadResult readSingleKeyFromTerminal(Terminal terminal) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return KeyReadResult.unavailable();
                }

                // 如果是 ESC，需要根据后续字节进行判断
                if (key == 27) {
                    String escapeSequence = readInputBurst(terminal, 80, 20, 120);
                    EscapeSequenceType escapeSequenceType = classifyEscapeSequence(escapeSequence);
                    if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
                        return KeyReadResult.keyPressed(27);
                    }
                    if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE || escapeSequenceType == EscapeSequenceType.BRACKET_PASTE) {
                        return KeyReadResult.ignoredSequence();
                    }
                }

                // 其他 key 直接返回
                return KeyReadResult.keyPressed(key);
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return KeyReadResult.unavailable();
        }
    }

    private static EscapeSequenceType classifyEscapeSequence(String sequence) {
        if (sequence == null || sequence.isBlank()) {
            return EscapeSequenceType.STANDALONE_ESC;
        }
        if (sequence.startsWith(BRACKETED_PASTE_BEGIN)) {
            return EscapeSequenceType.BRACKET_PASTE;
        }
        if (sequence.startsWith("[") || sequence.startsWith("O")) {
            return EscapeSequenceType.CONTROL_SEQUENCE;
        }
        return EscapeSequenceType.OTHER;
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
        System.out.printf("║      RAG-Enhanced Agent CLI %-8s                    ║%n", "v" + VERSION);
        System.out.println("║                                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * 加载配置
     */
    private static void configureLogging() {
        configureLogProperty(LOG_DIR_PROPERTY, "PAICODE_LOG_DIR",
                Path.of(System.getProperty("user.home"), ".paicode", "logs").toString());
        configureLogProperty(LOG_LEVEL_PROPERTY, "PAICODE_LOG_LEVEL", "INFO");
        configureLogProperty(LOG_MAX_HISTORY_PROPERTY, "PAICODE_LOG_MAX_HISTORY", "7");
        configureLogProperty(LOG_MAX_FILE_SIZE_PROPERTY, "PAICODE_LOG_MAX_FILE_SIZE", "10MB");
        configureLogProperty(LOG_TOTAL_SIZE_CAP_PROPERTY, "PAICODE_LOG_TOTAL_SIZE_CAP", "100MB");

        try {
            Files.createDirectories(Path.of(System.getProperty(LOG_DIR_PROPERTY)));
        } catch (IOException e) {
            System.err.println("创建日志目录失败: " + e.getMessage());
        }
    }

    private static void configureLogProperty(String propertyName, String envKey, String defaultValue) {
        String configuredValue = System.getProperty(propertyName);
        if (configuredValue == null || configuredValue.isBlank()) {
            configuredValue = loadConfigValue(envKey, defaultValue);
        }
        if (configuredValue != null && !configuredValue.isBlank()) {
            if (LOG_DIR_PROPERTY.equals(propertyName)) {
                configuredValue = expandHome(configuredValue.trim());
            }
            System.setProperty(propertyName, configuredValue.trim());
        }
    }

    private static String loadConfigValue(String key, String defaultValue) {
        String sysValue = System.getProperty(key);
        if (sysValue != null && !sysValue.isBlank()) {
            return sysValue.trim();
        }

        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        File currentEnv = new File(ENV_FILE);
        if (currentEnv.exists()) {
            String value = readValueFromFile(currentEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        File homeEnv = new File(System.getProperty("user.home"), ENV_FILE);
        if (homeEnv.exists()) {
            String value = readValueFromFile(homeEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return defaultValue;
    }

    private static String readValueFromFile(File file, String key) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith(key + "=")) {
                    return line.substring((key + "=").length()).trim();
                }
            }
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
        return null;
    }

    private static String expandHome(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.equals("~")) {
            return System.getProperty("user.home");
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        }
        return value;
    }

    private static void printStartupHints() {
        System.out.println("提示:");
        for (String hint : startupHints()) {
            System.out.println("   - " + hint);
        }
        System.out.println();
    }

    private static List<String> startupHints() {
        return List.of(
                "输入你的问题或任务",
                "输入 '/plan' 后，下一条任务使用 PlanAndExecute-and-Execute 模式",
                "输入 '/plan 任务内容' 直接用计划模式执行这条任务",
                "计划生成后可直接执行、补充要求重规划，或取消",
                "输入 '/index [路径]' 为代码库建立向量索引",
                "输入 '/search <查询>' 语义检索代码",
                "输入 '/graph <类名>' 查看代码关系图谱",
                "默认模式是 ReAct",
                "输入 '/clear' 清空对话历史",
                "输入 '/memory' 查看记忆状态",
                "输入 '/save 事实内容' 手动保存关键事实",
                "输入 '/exit' 或 '/quit' 退出"
        );
    }

    /**
     * 从 .env 文件加载 API Key
     */
    private static String loadApiKey() {
       return loadConfigValue("DEEPSEEK_API_KEY", null);
    }
}
