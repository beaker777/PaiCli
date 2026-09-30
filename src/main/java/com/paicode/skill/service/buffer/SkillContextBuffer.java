package com.paicode.skill.service.buffer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author beaker
 * @Date 2026/9/30 23:19
 * @Description 单个 Agent 实例的 skill 注入缓冲区
 */
public class SkillContextBuffer {

    private static final int MAX_SKILLS = 3;

    private final Map<String, String> entries = new LinkedHashMap<>();

    /**
     * 向 buffer 中添加 skill, 使用滑动窗口
     */
    public synchronized void push(String skillName, String body) {
        if (skillName == null || skillName.isBlank() || body == null) {
            return;
        }

        entries.remove(skillName);
        entries.put(skillName, body);
        while (entries.size() > MAX_SKILLS) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
    }

    /**
     * 取出全部已积累 skill body 并清空。返回拼接好的 markdown 段，可直接前置到 user message。
     */
    public synchronized String drain() {
        if (entries.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, String>> snapshot = new ArrayList<>(entries.entrySet());
        entries.clear();

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : snapshot) {
            sb.append("## 已加载 Skill：").append(e.getKey()).append('\n')
                    .append(e.getValue().trim()).append('\n')
                    .append('\n');
        }
        sb.append("---\n");
        return sb.toString();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
