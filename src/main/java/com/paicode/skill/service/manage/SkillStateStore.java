package com.paicode.skill.service.manage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @Author beaker
 * @Date 2026/10/1 00:07
 * @Description skill 启用状态持久化
 */
public class SkillStateStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    public SkillStateStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /**
     * 解析禁用的 skill 列表
     */
    public synchronized Set<String> disabled() {
        if (!Files.exists(file)) {
            return Set.of();
        }

        try {
            String content = Files.readString(file);
            if (content.isBlank()) {
                return Set.of();
            }

            ObjectNode root = (ObjectNode) MAPPER.readTree(content);
            Set<String> result = new LinkedHashSet<>();
            if (root.has("disabled") && root.get("disabled").isArray()) {
                root.get("disabled").forEach(node -> {
                    if (node.isTextual() && !node.asText().isBlank()) {
                        result.add(node.asText());
                    }
                });
            }
            return result;
        } catch (Exception e) {
            System.err.println("⚠️ skills.json 解析失败，忽略禁用列表: " + e.getMessage());
            return Set.of();
        }
    }

    /**
     * 禁用 skill
     */
    public synchronized void disable(String name) {
        Set<String> set = new LinkedHashSet<>(disabled());
        set.add(name);
        write(set);
    }

    /**
     * 启用 skill
     */
    public synchronized void enable(String name) {
        Set<String> set = new LinkedHashSet<>(disabled());
        set.remove(name);
        write(set);
    }

    private void write(Set<String> disabled) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.putPOJO("disabled", disabled);
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            System.err.println("⚠️ skills.json 写入失败: " + e.getMessage());
        }
    }
}
