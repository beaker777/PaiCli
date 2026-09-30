package com.paicode.skill.entity;

import com.paicode.skill.constant.Source;

import java.nio.file.Path;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/30 00:00
 * @Description Skill 负责为 agent 提供决策能力
 */
public record Skill(String name, String description, String version, String author,
                    List<String> tags, Source source, String body,
                    Path skillMdPath, Path referencesDir) {

    public Skill {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name 不能为空");
        }

        if (description == null) {
            description = "";
        }
        if (tags == null) {
            tags = List.of();
        } else {
            tags = List.copyOf(tags);
        }
        if (body == null) {
            body = "";
        }
    }

    public String displaySource() {
        return switch (source) {
            case BUILTIN -> "builtin";
            case USER -> "user";
            case PROJECT -> "project";
        };
    }
}
