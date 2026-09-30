package com.paicode.tool.service.tools;

import com.paicode.skill.entity.Skill;
import com.paicode.skill.service.buffer.SkillContextBuffer;
import com.paicode.skill.service.manage.SkillRegistry;
import com.paicode.tool.entity.Param;
import com.paicode.tool.entity.ToolDefinition;
import com.paicode.tool.entity.ToolSchema;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/10/1 01:24
 * @Description Skill 工具
 */
public class SkillTools {

    public static List<ToolDefinition> create(SkillRegistry skillRegistry, SkillContextBuffer skillContextBuffer) {
        return List.of(
                createLoadSkillTool(skillRegistry, skillContextBuffer)
        );
    }

    private static ToolDefinition createLoadSkillTool(SkillRegistry skillRegistry, SkillContextBuffer skillContextBuffer) {
        return new ToolDefinition("load_skill",
                "加载系统已经索引的 Skill 完整指引, 可用 Skill 请查看系统提示词中的“可用 Skills”部分。"
                        + "当某个 Skill 的描述与当前任务匹配时调用此工具, 必须传入准确的 kebab-case Skill 名称。"
                        + "加载后的完整正文会在下一轮用户消息开头，以“## 已加载 Skill：<name>”段落出现, 同一会话中不要重复加载同一个 Skill。",
                ToolSchema.createParameters(
                        new Param("name", "string", "准确的 kebab-case skill 名称 (如 web-access)", true)
                ),
                args -> {
                    String name = args.get("name");
                    if (name == null || name.isBlank()) {
                        return "load_skill 失败: name 不能为空";
                    }
                    if (skillRegistry == null) {
                        return  "load_skill 失败: Skill 系统未初始化";
                    }

                    Skill skill = skillRegistry.findSkill(name);
                    if (skill == null) {
                        Skill anySkill = skillRegistry.findAnySkill(name);
                        if (anySkill == null) {
                            return "Skill '" + name + "' 未找到, 可用 /skill list 查看可用 skill";
                        }
                        return "Skill '" + name + "' 已被禁用, 可用 /skill on " + name + " 启用";
                    }

                    String body = skill.body();
                    int originalLen = body == null ? 0 : body.length();
                    int max = 5 * 1024;
                    String injected = body == null ? "" : body;
                    if (injected.length() > max) {
                        injected = injected.substring(0, max)
                                + "\n\n...(skill body truncated, full content via /skill show " + name + ")";
                    }
                    if (skillContextBuffer != null) {
                        skillContextBuffer.push(name, injected);
                    }

                    return "已加载 skill '" + name + "' 的完整指引（" + originalLen
                            + " bytes），将在下一轮上下文中以 \"## 已加载 Skill：" + name + "\" 段出现。";
                });
    }
}
