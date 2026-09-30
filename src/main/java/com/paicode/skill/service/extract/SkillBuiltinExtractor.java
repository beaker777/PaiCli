package com.paicode.skill.service.extract;

import com.paicode.skill.entity.BuiltinSkillSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/30 22:41
 * @Description 内置 Skill 解压器
 *
 * 把 jar 内 resources/skills/<name>/ 解压到 ~/.paicode/skills-cache/<name>/。
 */
public class SkillBuiltinExtractor {

    // 当 web-access 有破坏性更新时更新版本, 触发缓存重建
    public static final String CURRENT_VERSION = "1.0.0";

    private static final List<BuiltinSkillSpec> BUILTIN_SKILLS = List.of(
            new BuiltinSkillSpec("web-access", List.of(
                    "SKILL.md",
                    "references/cdp-cheatsheet.md",
                    "references/site-patterns/github.com.md",
                    "references/site-patterns/juejin.cn.md",
                    "references/site-patterns/mp.weixin.qq.com.md",
                    "references/site-patterns/x.com.md",
                    "references/site-patterns/xiaohongshu.com.md",
                    "references/site-patterns/zhuanlan.zhihu.com.md"
            ))
    );

    private final Path cacheRoot;

    public SkillBuiltinExtractor(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public Path cacheRoot() {
        return cacheRoot;
    }

    public List<String> builtinSkillNames() {
        return BUILTIN_SKILLS.stream()
                .map(BuiltinSkillSpec::name)
                .toList();
    }

    /**
     * 解压所有的内置 skill
     */
    public void extractAll() throws IOException {
        Files.createDirectories(cacheRoot);
        for (BuiltinSkillSpec builtinSkill : BUILTIN_SKILLS) {
            extract(builtinSkill);
        }
    }

    /**
     * 将 resource 中的内容解压到 cache
     */
    private void extract(BuiltinSkillSpec spec) throws IOException {
        Path skillDir = cacheRoot.resolve(spec.name());
        Path versionFile = skillDir.resolve(".version");

        // 版本一致直接返回
        if (Files.exists(versionFile)) {
            String existing = Files.readString(versionFile).trim();
            if (CURRENT_VERSION.equals(existing)) {
                return;
            }
        }

        // 版本不同重新缓存
        if (Files.exists(skillDir)) {
            deleteRecursive(skillDir);
        }
        Files.createDirectories(skillDir);
        for (String relative : spec.files()) {
            String resourcePath = "skills/" + spec.name() + "/" + relative;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    System.err.println("⚠️ 内置 skill 资源缺失: " + resourcePath);
                    continue;
                }

                Path target = skillDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(versionFile, CURRENT_VERSION);
    }

    /**
     * 递归删除文件夹
     */
    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }
}
