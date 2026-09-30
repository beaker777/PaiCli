package com.paicode.skill.service.manage;

import com.paicode.skill.constant.Source;
import com.paicode.skill.entity.ParseResult;
import com.paicode.skill.entity.Skill;
import com.paicode.skill.service.parser.SkillFrontmatterParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * @Author beaker
 * @Date 2026/10/1 00:48
 * @Description skill 注册
 */
public class SkillRegistry {

    private final Path builtinCacheRoot;
    private final Path userSkillsDir;
    private final Path projectSkillsDir;
    private final SkillStateStore stateStore;

    private final Map<String, Skill> skillsByName = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public SkillRegistry(Path builtinCacheRoot, Path userSkillsDir, Path projectSkillsDir, SkillStateStore stateStore) {
        this.builtinCacheRoot = builtinCacheRoot;
        this.userSkillsDir = userSkillsDir;
        this.projectSkillsDir = projectSkillsDir;
        this.stateStore = stateStore;
    }

    public synchronized void reload() {
        skillsByName.clear();
        warnings.clear();

        loadDirectory(builtinCacheRoot, Source.BUILTIN);
        loadDirectory(userSkillsDir, Source.USER);
        loadDirectory(projectSkillsDir, Source.PROJECT);
    }

    public synchronized List<Skill> allSkills() {
        return skillsByName.values().stream()
                .sorted(Comparator.comparing(Skill::name))
                .toList();
    }

    public synchronized List<Skill> enabledSkills() {
        Set<String> disabled = stateStore == null ? Set.of() : stateStore.disabled();
        return allSkills().stream()
                .filter(s -> !disabled.contains(s.name()))
                .toList();
    }

    public synchronized Skill findSkill(String name) {
        if (name == null) return null;

        Skill skill = skillsByName.get(name);
        if (skill == null) return null;
        Set<String> disabled = stateStore == null ? Set.of() : stateStore.disabled();
        if (disabled.contains(name)) return null;

        return skill;
    }

    public synchronized Skill findAnySkill(String name) {
        if (name == null) return null;
        return skillsByName.get(name);
    }

    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    public SkillStateStore stateStore() {
        return stateStore;
    }

    private void loadDirectory(Path dir, Source source) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }

        try (var stream = Files.list(dir)) {
            List<Path> entries = stream
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
            for (Path entry : entries) {
                Path skillMd = entry.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) {
                    continue;
                }

                Skill skill = parseSkill(entry, skillMd, source);
                if (skill != null) {
                    skillsByName.put(skill.name(), skill);
                }
            }
        } catch (IOException e) {
            warnings.add("扫描 skill 目录失败 " + dir + ": " + e.getMessage());
            System.err.println("⚠️ 扫描 skill 目录失败 " + dir + ": " + e.getMessage());
        }
    }

    private Skill parseSkill(Path skillDir, Path skillMd, Source source) {
        String content;
        try {
            content = Files.readString(skillMd);
        } catch (IOException e) {
            warnings.add("读取 SKILL.md 失败 " + skillMd + ": " + e.getMessage());
            System.err.println("⚠️ 读取 SKILL.md 失败 " + skillMd + ": " + e.getMessage());
            return null;
        }

        ParseResult parsed = SkillFrontmatterParser.parse(content);
        for (String w : parsed.warnings()) {
            warnings.add(skillMd + ": " + w);
            System.err.println("⚠️ Skill " + skillMd + " frontmatter: " + w);
        }

        Map<String, Object> fm = parsed.frontmatter();
        String name = stringField(fm, "name");
        if (name == null || name.isBlank()) {
            name = skillDir.getFileName().toString();
        }
        String description = stringField(fm, "description");
        if (description == null) description = "";
        String version = stringField(fm, "version");
        String author = stringField(fm, "author");
        List<String> tags = listField(fm, "tags");

        Path referencesDir = skillDir.resolve("references");
        if (!Files.isDirectory(referencesDir)) {
            referencesDir = null;
        }

        return new Skill(
                name,
                description,
                version,
                author,
                tags,
                source,
                parsed.body(),
                skillMd,
                referencesDir
        );
    }

    private static String stringField(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        return v instanceof String s ? s : null;
    }

    private static List<String> listField(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        if (v instanceof List<?> list) {
            return list.stream()
                    .filter(x -> x instanceof String)
                    .map(x -> (String) x)
                    .toList();
        }
        return Collections.emptyList();
    }
}
