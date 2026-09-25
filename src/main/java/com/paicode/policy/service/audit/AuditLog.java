package com.paicode.policy.service.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.policy.entity.AuditEntry;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @Author beaker
 * @Date 2026/9/25 01:16
 * @Description 危险工具调用时的审计日志
 */
@Getter
public class AuditLog {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final int MAX_FIELD_CHARS = 1000;

    private final Path auditDir;
    private final Object writeLock = new Object();

    public AuditLog() {
        this(defaultAuditDir());
    }

    public AuditLog(Path auditDir) {
        this.auditDir = auditDir;
    }

    public void record(AuditEntry entry) {
        if (entry == null) return;

        try {
            synchronized (writeLock) {
                Files.createDirectories(auditDir);
                Path file = todayFile();

                String json = mapper.writeValueAsString(entry);
                Files.writeString(file, json + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            // 审计失败不能影响主流程
            System.err.println("⚠️ 审计日志写入失败: " + e.getMessage());
        }
    }

    /**
     * 读取今天审计文件最近 n 条记录，按写入顺序返回（最新的在末尾）。
     */
    public List<AuditEntry> readRecent(int n) {
        if (n <= 0) return List.of();
        Path file = todayFile();
        if (!Files.exists(file)) return List.of();

        try {
            List<String> lines = Files.readAllLines(file);
            int from = Math.max(0, lines.size() - n);

            List<AuditEntry> entries = new ArrayList<>();
            for (int i = from; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) continue;
                try {
                    entries.add(mapper.readValue(line, new TypeReference<AuditEntry>() {}));
                } catch (Exception ignored) {
                    // 单行格式错误跳过，不影响其他记录
                }
            }

            return entries;
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path todayFile() {
        return auditDir.resolve("audit-" + LocalDate.now().format(DATE_FMT) + ".jsonl");
    }

    private static Path defaultAuditDir() {
        String prop = System.getProperty("paicode.audit.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }

        String env = System.getenv("PAICODE_AUDIT_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }

        return Path.of(System.getProperty("user.home"), ".paicode", "audit");
    }

    public static String truncate(String s) {
        if (s == null) return null;

        String sanitized = sanitize(s);
        return sanitized.length() <= MAX_FIELD_CHARS ? sanitized : sanitized.substring(0, MAX_FIELD_CHARS) + "...(truncated)";
    }

    static String sanitize(String s) {
        String sanitized = s.replaceAll("(?i)Bearer\\s+[^\\s\"'}]+", "Bearer ***");
        sanitized = sanitized.replaceAll(
                "(?i)(\"?(?:token|key|password|secret|authorization)\"?\\s*[:=]\\s*\")([^\"]+)(\")",
                "$1***$3");
        sanitized = sanitized.replaceAll(
                "(?i)(\\b(?:token|key|password|secret|authorization)\\b\\s*[:=]\\s*)([^\\s,}]+)",
                "$1***");

        return sanitized;
    }
}
