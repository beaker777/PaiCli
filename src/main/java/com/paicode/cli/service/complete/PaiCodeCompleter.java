package com.paicode.cli.service.complete;

import com.paicode.cli.entity.CommandOption;
import com.paicode.cli.entity.SlashCommandHint;
import com.paicode.mcp.entity.resource.McpResourceDescription;
import com.paicode.mcp.service.mention.AtMentionCompleter;
import com.paicode.skill.entity.Skill;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.paicode.cli.entity.SlashCommandHint.slashCommandHints;

/**
 * @Author beaker
 * @Date 2026/9/29 22:41
 * @Description Tab 后自动补全
 */
public class PaiCodeCompleter implements Completer {

    private final Supplier<List<McpResourceDescription>> resourceSupplier;
    private final Supplier<List<Skill>> skillSupplier;

    public PaiCodeCompleter(Supplier<List<McpResourceDescription>> resourceSupplier) {
        this(resourceSupplier, List::of);
    }

    public PaiCodeCompleter(Supplier<List<McpResourceDescription>> resourceSupplier, Supplier<List<Skill>> skillSupplier) {
        this.resourceSupplier = resourceSupplier;
        this.skillSupplier = skillSupplier == null ? List::of : skillSupplier;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line == null || candidates == null) {
            return;
        }

        // 输入 "/" 开头, 补全命令
        String input = line.line() == null ? "" : line.line();
        if (input.startsWith("/")) {
            completeSlashCommand(line, candidates);
            return;
        }

        // 输入以 "image" 开头, 补全图片路径
        String word = line.word() == null ? "" : line.word();
        if (word.startsWith("@image:")) {
            completeImagePath(line, candidates);
            return;
        }

        // 正常补全内容
        new AtMentionCompleter(resourceSupplier).complete(reader, line, candidates);
        completeLocalPathMention(line, candidates);
    }

    private void completeSlashCommand(ParsedLine line, List<Candidate> candidates) {
        String input = line.line() == null ? "" : line.line();
        if (completeModel(input, candidates)
                || completeConfig(input, candidates)
                || completeMcp(input, candidates)
                || completeSkill(input, candidates)
                || completeTask(input, candidates)
                || completeBrowser(input, candidates)
                || completeSnapshot(input, candidates)) {
            return;
        }

        int cursor = Math.max(0, Math.min(line.cursor(), input.length()));
        String prefix = input.substring(0, cursor);
        String word = line.word() == null ? "" : line.word();
        int replacementStart = Math.max(0, prefix.length() - word.length());

        for (SlashCommandHint hint : slashCommandHints()) {
            String command = hint.insertText();
            if (!command.startsWith(prefix)) {
                continue;
            }

            String value = command.substring(Math.min(replacementStart, command.length()));
            candidates.add(new Candidate(
                    value,
                    hint.display(),
                    "PaiCode 命令",
                    hint.description(),
                    null,
                    null,
                    true
            ));
        }
    }

    private boolean completeModel(String input, List<Candidate> candidates) {
        if (input.length() > 6 && !input.regionMatches(true, 0, "/model", 0, 6)) {
            return false;
        }
        if (!input.equalsIgnoreCase("/model") && !input.regionMatches(true, 0, "/model ", 0, 7)) {
            return false;
        }

        String value = input.length() <= 7 ? "" : input.substring(7);
        addMatching(candidates, "模型", value,
                option("glm-5.1", "GLM-5.1 长上下文"),
                option("glm-5v-turbo", "GLM-5V 多模态"),
                option("deepseek", "DeepSeek，读取配置模型"),
                option("step", "StepFun，读取配置模型"),
                option("kimi", "Kimi/Moonshot，读取配置模型"),
                option("freellmapi", "本地 FreeLLMAPI，读取配置模型"));
        return true;
    }

    private boolean completeMcp(String input, List<Candidate> candidates) {
        if (!input.equalsIgnoreCase("/mcp") && !input.regionMatches(true, 0, "/mcp ", 0, 5)) {
            return false;
        }

        // 补全 mcp 命令
        String payload = input.length() <= 5 ? "" : input.substring(5);
        String[] parts = payload.trim().isEmpty() ? new String[0] : payload.trim().split("\\s+");
        if (parts.length <= 1 && !payload.endsWith(" ")) {
            addMatching(candidates, "MCP 命令", payload,
                    option("restart ", "重启 MCP server", "/mcp restart <name>"),
                    option("logs ", "查看 MCP server stderr", "/mcp logs <name>"),
                    option("disable ", "禁用 MCP server", "/mcp disable <name>"),
                    option("enable ", "启用 MCP server", "/mcp enable <name>"),
                    option("resources ", "查看 MCP resources", "/mcp resources <name>"),
                    option("prompts ", "查看 MCP prompts", "/mcp prompts <name>"));
            return true;
        }

        // 补全 mcp server name
        String sub = parts.length == 0 ? "" : parts[0].toLowerCase();
        if (List.of("restart", "logs", "disable", "enable", "resources", "prompts").contains(sub)) {
            String prefix = payload.endsWith(" ") ? "" : parts.length >= 2 ? parts[parts.length - 1] : "";
            addServerCandidates(candidates, prefix);
            return true;
        }
        return true;
    }

    private boolean completeConfig(String input, List<Candidate> candidates) {
        if (!input.equalsIgnoreCase("/config") && !input.regionMatches(true, 0, "/config ", 0, 8)) {
            return false;
        }

        // 补全命令内容
        String payload = input.length() <= 8 ? "" : input.substring(8);
        String[] parts = payload.trim().isEmpty() ? new String[0] : payload.trim().split("\\s+");
        if (parts.length <= 1 && !payload.endsWith(" ")) {
            addMatching(candidates, "配置", payload,
                    option("provider ", "配置 provider", "/config provider <name>"));
            return true;
        }

        // 补全 provider 配置命令
        if (parts.length >= 1 && "provider".equalsIgnoreCase(parts[0])) {
            String prefix = payload.endsWith(" ") ? "" : parts[parts.length - 1];
            if (parts.length <= 2 && !payload.contains("--")) {
                addMatching(candidates, "Provider", prefix,
                        option("freellmapi ", "本地 FreeLLMAPI"),
                        option("glm ", "GLM"),
                        option("deepseek ", "DeepSeek"),
                        option("step ", "StepFun"),
                        option("kimi ", "Kimi/Moonshot"));
                return true;
            }
            addMatching(candidates, "配置项", prefix,
                    option("--base-url ", "OpenAI-compatible base URL"),
                    option("--api-key ", "API Key"),
                    option("--model ", "模型名"),
                    option("--default", "设为默认 provider"));
            return true;
        }
        return true;
    }

    private boolean completeSkill(String input, List<Candidate> candidates) {
        if (!input.equalsIgnoreCase("/skill") && !input.regionMatches(true, 0, "/skill ", 0, 7)) {
            return false;
        }

        // 补全 skill 命令
        String payload = input.length() <= 7 ? "" : input.substring(7);
        String[] parts = payload.trim().isEmpty() ? new String[0] : payload.trim().split("\\s+");
        if (parts.length <= 1 && !payload.endsWith(" ")) {
            addMatching(candidates, "Skill 命令", payload,
                    option("list", "查看 skill 列表"),
                    option("show ", "查看 SKILL.md 全文"),
                    option("on ", "启用 skill"),
                    option("off ", "禁用 skill"),
                    option("reload", "重新扫描 skill 目录"));
            return true;
        }

        // 补全 skill name
        String sub = parts.length == 0 ? "" : parts[0].toLowerCase();
        if (List.of("show", "on", "off").contains(sub)) {
            String prefix = payload.endsWith(" ") ? "" : parts.length >= 2 ? parts[parts.length - 1] : "";
            addSkillCandidates(candidates, prefix);
            return true;
        }
        return true;
    }

    private boolean completeTask(String input, List<Candidate> candidates) {
        if (!input.equalsIgnoreCase("/task") && !input.regionMatches(true, 0, "/task ", 0, 6)) {
            return false;
        }

        String payload = input.length() <= 6 ? "" : input.substring(6);
        addMatching(candidates, "后台任务", payload,
                option("list", "查看后台任务列表"),
                option("add ", "提交后台任务"),
                option("cancel ", "取消后台任务"),
                option("log ", "查看后台任务结果"));
        return true;
    }

    private boolean completeBrowser(String input, List<Candidate> candidates) {
        if (!input.equalsIgnoreCase("/browser") && !input.regionMatches(true, 0, "/browser ", 0, 9)) {
            return false;
        }

        String payload = input.length() <= 9 ? "" : input.substring(9);
        addMatching(candidates, "浏览器", payload,
                option("status", "查看浏览器会话状态"),
                option("connect", "复用登录态 Chrome"),
                option("tabs", "查看 shared 模式真实 Chrome tab"),
                option("disconnect", "切回 isolated 模式"));
        return true;
    }

    private boolean completeSnapshot(String input, List<Candidate> candidates) {
        if (!input.equalsIgnoreCase("/snapshot") && !input.regionMatches(true, 0, "/snapshot ", 0, 10)) {
            return false;
        }

        String payload = input.length() <= 10 ? "" : input.substring(10);
        addMatching(candidates, "快照", payload,
                option("status", "查看 Side-Git 快照状态"),
                option("clean", "清理当前项目快照"));
        return true;
    }

    private void completeImagePath(ParsedLine line, List<Candidate> candidates) {
        String word = line.word() == null ? "" : line.word();
        String prefix = word.substring("@image:".length());
        boolean angle = prefix.startsWith("<");
        String pathPrefix = angle ? prefix.substring(1) : prefix;

        for (Candidate candidate : localPathCandidates(pathPrefix, "图片路径")) {
            String value = angle ? "@image:<" + candidate.value() : "@image:" + candidate.value();
            candidates.add(new Candidate(value, value, candidate.group(), candidate.descr(), null, null, true));
        }
    }

    private void completeLocalPathMention(ParsedLine line, List<Candidate> candidates) {
        String word = line.word() == null ? "" : line.word();
        if (!word.startsWith("@") || word.startsWith("@image:") || word.startsWith("@clipboard")) {
            return;
        }

        String prefix = word.substring(1);
        if (prefix.contains(":")) {
            return;
        }

        boolean angle = prefix.startsWith("<");
        String pathPrefix = angle ? prefix.substring(1) : prefix;
        for (Candidate candidate : localPathCandidates(pathPrefix, "本地路径")) {
            String value = angle ? "@<" + candidate.value() : "@" + candidate.value();
            candidates.add(new Candidate(value, value, candidate.group(), candidate.descr(), null, null, true));
        }
    }


    /**
     * 候选项从 McpManager 中获取
     *
     * @param candidates 返回的补全 MCP server name
     * @param prefix 用户输入
     */
    private void addServerCandidates(List<Candidate> candidates, String prefix) {
        List<String> servers = resourceSupplier.get().stream()
                .map(McpResourceDescription::serverName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted()
                .toList();
        for (String server : servers) {
            if (matches(server, prefix)) {
                candidates.add(new Candidate(server, server, "MCP server", "来自 resource cache", null, null, true));
            }
        }
    }

    /**
     * 候选项从 SkillManager 中获取
     *
     * @param candidates 返回的补全 Skill 名
     * @param prefix 用户输入
     */
    private void addSkillCandidates(List<Candidate> candidates, String prefix) {
        for (Skill skill : skillSupplier.get()) {
            if (skill == null || !matches(skill.name(), prefix)) {
                continue;
            }
            candidates.add(new Candidate(
                    skill.name(),
                    skill.name(),
                    "Skill",
                    skill.description(),
                    null,
                    null,
                    true
            ));
        }
    }

    /**
     * @param candidates 返回的补全命令
     * @param prefix 用户输入
     * @param options 候选项
     */
    private static void addMatching(List<Candidate> candidates, String group, String prefix, CommandOption... options) {
        for (CommandOption option : options) {
            if (matches(option.value(), prefix)) {
                candidates.add(new Candidate(
                        option.value(),
                        option.display() == null ? option.value().trim() : option.display(),
                        group,
                        option.description(),
                        null,
                        null,
                        option.value().endsWith(" ")
                ));
            }
        }
    }

    private static List<Candidate> localPathCandidates(String prefix, String group) {
        Path base;
        String filePrefix;

        if (prefix == null || prefix.isBlank()) {
            base = Path.of(".");
            filePrefix = "";
        } else {
            Path typed = Path.of(prefix);
            base = typed.getParent() == null ? Path.of(".") : typed.getParent();
            filePrefix = typed.getFileName() == null ? "" : typed.getFileName().toString();
        }

        if (!Files.isDirectory(base)) {
            return List.of();
        }

        List<Candidate> result = new ArrayList<>();
        try (var stream = Files.list(base)) {
            stream.sorted().limit(50).forEach(path -> {
                String name = path.getFileName().toString();
                if (!name.startsWith(filePrefix)) {
                    return;
                }
                boolean dir = java.nio.file.Files.isDirectory(path);
                String value = base.equals(Path.of("."))
                        ? name
                        : base.resolve(name).toString();
                if (dir) {
                    value += File.separator;
                }
                result.add(new Candidate(value, value, group, dir ? "目录" : "文件", null, null, !dir));
            });
        } catch (Exception ignored) {
            return List.of();
        }
        return result;
    }

    private static boolean matches(String value, String prefix) {
        return prefix == null || prefix.isBlank() || value.toLowerCase().startsWith(prefix.toLowerCase());
    }

    private static CommandOption option(String value, String description) {
        return new CommandOption(value, description, null);
    }

    private static CommandOption option(String value, String description, String display) {
        return new CommandOption(value, description, display);
    }
}
