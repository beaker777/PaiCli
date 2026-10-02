package com.paicode.tui.service.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicode.tui.entity.MessageRecord;
import com.paicode.tui.entity.SessionMeta;
import lombok.Getter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * @Author beaker
 * @Date 2026/10/1 18:55
 * @Description 对话历史快照
 *
 * 持久化对话历史到 ~/.paicode/history/ 目录, 保存到 sessionId.jsonl 文件, 每行对应一条 message
 * 保存 user, assistant, system 类型的消息
 */
@Getter
public class ConversationSnapshot {

    private static final Path HISTORY_DIR = Path.of(System.getProperty("user.home"), ".paicode", "history");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();

    private final String sessionId;
    private final Path sessionFile;
    private final List<MessageRecord> messages;

    public ConversationSnapshot(String sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.sessionFile = HISTORY_DIR.resolve(sessionId + ".jsonl");
        this.messages = new ArrayList<>();
    }

    /**
     * 追加消息。
     */
    public void append(MessageRecord message) {
        messages.add(message);
    }

    /**
     * 追加用户消息。
     */
    public void appendUser(String content) {
        append(MessageRecord.of("user", content));
    }

    /**
     * 追加 Assistant 消息。
     */
    public void appendAssistant(String content) {
        append(MessageRecord.of("assistant", content));
    }

    /**
     * 获取所有消息。
     */
    public List<MessageRecord> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 保存到文件 (简化版)
     */
    public void save() throws IOException {
        Files.createDirectories(HISTORY_DIR);
        // 追加模式写入 JSONL
        try (BufferedWriter writer = Files.newBufferedWriter(sessionFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (MessageRecord msg : messages) {
                MAPPER.writeValue(writer, msg);
                writer.newLine();
            }
        }
        messages.clear();
    }

    /**
     * 从文件加载会话 (简化版)
     */
    public static ConversationSnapshot load(String sessionId) throws IOException {
        Path file = HISTORY_DIR.resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) {
            return new ConversationSnapshot(sessionId);
        }

        ConversationSnapshot snapshot = new ConversationSnapshot(sessionId);
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                MessageRecord msg = MAPPER.readValue(line, MessageRecord.class);
                snapshot.messages.add(msg);
            }
        }
        return snapshot;
    }

    /**
     * 列出所有会话。
     */
    public static List<SessionMeta> listSessions() throws IOException {
        if (!Files.isDirectory(HISTORY_DIR)) {
            return List.of();
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(HISTORY_DIR, "*.jsonl")) {
            return StreamSupport.stream(stream.spliterator(), false)
                    .map(path -> {
                        String sessionId = path.getFileName().toString().replace(".jsonl", "");
                        try {
                            long size = Files.size(path);
                            long lastModified = Files.getLastModifiedTime(path).toMillis();
                            return new SessionMeta(
                                    sessionId,
                                    "会话 " + sessionId.substring(0, 8),
                                    lastModified,
                                    lastModified,
                                    (int) (size / 200)  // 估算消息数
                            );
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(SessionMeta::lastActiveAt).reversed())
                    .collect(Collectors.toList());
        }
    }

    /**
     * 删除会话。
     */
    public static void deleteSession(String sessionId) throws IOException {
        Path file = HISTORY_DIR.resolve(sessionId + ".jsonl");
        Files.deleteIfExists(file);
    }

    /**
     * 获取当前会话 ID（时间戳）。
     */
    public static String generateSessionId() {
        return "session_" + System.currentTimeMillis() + "_" + SESSION_SEQUENCE.incrementAndGet();
    }
}
