package com.paicode.tool.service.tools;

import com.paicode.tool.entity.Param;
import com.paicode.tool.entity.ToolDefinition;
import com.paicode.tool.entity.ToolSchema;

import java.util.List;
import java.util.function.Consumer;

/**
 * @Author beaker
 * @Date 2026/9/29 21:36
 * @Description 记忆工具
 */
public class MemoryTools {

    public static List<ToolDefinition> create(Consumer<String> memorySaver) {
        return List.of(createSaveMemory(memorySaver));
    }

    private static ToolDefinition createSaveMemory(Consumer<String> memorySaver) {
        return new ToolDefinition("save_memory",
                "当且仅当用户明确说 “记一下”“记住”“以后记得” 或要求保存长期偏好/稳定事实时调用，把精炼事实写入长期记忆；不要保存一次性任务请求、临时文件名或模型猜测。",
                ToolSchema.createParameters(new Param("fact", "string", "要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用", true)),
                    args -> {
                        String fact = args.get("fact");
                        if (fact == null || fact.isBlank()) {
                            return "保存长期记忆失败: 长期记忆为空";
                        }
                        if (memorySaver == null) {
                            return "保存长期记忆失败: 记忆保存器未被初始化";
                        }

                        String normalized = fact.trim();
                        memorySaver.accept(normalized);
                        return "💾 已保存到长期记忆: " + normalized;
                    }
                );
    }
}
