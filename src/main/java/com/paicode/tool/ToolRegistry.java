package com.paicode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.llm.DTO.Tool;
import com.paicode.tool.service.CodeTools;
import com.paicode.tool.service.FileTools;
import com.paicode.tool.service.ShellTools;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author beaker
 * @Date 2026/9/8 22:07
 * @Description 工具注册表
 */
public class ToolRegistry {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    // 注册工具
    public ToolRegistry() {
        register(FileTools.create());
        register(ShellTools.create());
        register(CodeTools.create());
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

        try {
            JsonNode args = mapper.readTree(argumentJson);
            Map<String, String> argMap = new HashMap<>();
            args.fields().forEachRemaining(
                    entry -> argMap.put(entry.getKey(), entry.getValue().asText())
            );

            return toolDefinition.executor().execute(argMap);
        } catch (Exception e) {
            return "执行工具失败: " + e.getMessage();
        }
    }
}
