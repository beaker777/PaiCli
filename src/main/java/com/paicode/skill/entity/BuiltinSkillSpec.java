package com.paicode.skill.entity;

import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/30 22:45
 * @Description 内置 Skill 规范
 */
public record BuiltinSkillSpec(String name, List<String> files) {
}
