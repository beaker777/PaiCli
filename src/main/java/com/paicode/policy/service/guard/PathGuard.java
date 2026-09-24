package com.paicode.policy.service.guard;

import com.paicode.policy.exception.PolicyException;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @Author beaker
 * @Date 2026/9/25 01:05
 * @Description 路径围栏, 所有文件类工具调用前都要经过检验
 *
 *  1. 绝对路径逃出项目根
 *  2. 相对路径用 .. 穿越
 *  3. 符号连接逃逸 (项目内的软链指向外部目录)
 */
@Getter
public class PathGuard {

    private final Path rootPath;

    public PathGuard(String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("项目根路径不能为空");
        }

        Path candidate = Paths.get(root).toAbsolutePath().normalize();

        // macOS 上 /var/folders → /private/var/folders 这类符号链接，必须先把根本身展开成真实路径，
        // 否则后面 resolveRealPath 会把目标展开成 /private/... 但根仍是 /var/...，startsWith 永远 false。
        Path real = candidate;
        try {
            if (Files.exists(candidate)) {
                real = candidate.toRealPath();
            }
        } catch (IOException ignored) {
        }
        this.rootPath = real;
    }

    /**
     * 校验路径是否在项目根之内, 返回安全的绝对路径。
     */
    public Path resolveSafe(String input) {
        if (input == null || input.isBlank()) {
            throw new PolicyException("路径不能为空");
        }

        Path raw = Paths.get(input);
        Path resolved = raw.isAbsolute()
                ? raw.normalize()
                : rootPath.resolve(raw).normalize();

        Path realResolved = resolveRealPath(resolved);

        if (!realResolved.startsWith(rootPath)) {
            throw new PolicyException("路径越界: " + input + " 不在项目根 " + rootPath + " 之内");
        }
        return realResolved;
    }

    /**
     * 向上找到最近的存在祖先，调用 toRealPath 解析其中的符号链接，再把剩余段接回。
     *
     * 这样做的目的：write_file 给一个尚不存在的目标路径时，仍能识别出"路径中段是个软链且指向外部"的越界情况。
     */
    private Path resolveRealPath(Path target) {
        Path existing = target;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return target.toAbsolutePath().normalize();
        }
        try {
            Path realExisting = existing.toRealPath();
            Path remainder = existing.relativize(target);
            return realExisting.resolve(remainder).normalize();
        } catch (IOException e) {
            return target.toAbsolutePath().normalize();
        }
    }
}
