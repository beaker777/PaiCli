package com.paicode.snapshot.service;

import com.paicode.snapshot.entity.RestoreResult;
import com.paicode.snapshot.entity.TurnSnapshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

/**
 * @Author beaker
 * @Date 2026/10/3 22:34
 * @Description 快照服务
 */
public class SnapshotService implements AutoCloseable {

    private final SideGitManager manager;
    private final ExecutorService executor;
    private volatile Future<?> lastAsyncTask;

    public SnapshotService(SideGitManager manager) {
        this.manager = manager;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-snapshot-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static SnapshotService forProject(Path projectRoot) {
        return new SnapshotService(new SideGitManager(projectRoot));
    }

    public <T> T runTurn(String mode, String input, ThrowingSupplier<T> supplier) throws Exception {
        String turnId = turnId(mode);
        String summary = summarize(mode, input);
        snapshotBeforeTurn(turnId, summary);
        try {
            return supplier.get();
        } finally {
            snapshotAfterTurnAsync(turnId, summary);
        }
    }

    public void snapshotBeforeTurn(String turnId, String summary) {
        if (!manager.getConfig().enabled()) {
            return;
        }

        try {
            manager.preTurnSnapshot(turnId, summary);
        } catch (Exception e) {
            System.err.println("⚠️ pre-turn 快照失败: " + e.getMessage());
        }
    }

    public void snapshotAfterTurnAsync(String turnId, String summary) {
        if (!manager.getConfig().enabled()) {
            return;
        }

        lastAsyncTask = executor.submit(() -> {
            try {
                manager.postTurnSnapshot(turnId, summary);
            } catch (Exception e) {
                System.err.println("⚠️ post-turn 快照失败: " + e.getMessage());
            }
        });
    }

    public List<TurnSnapshot> listSnapshots(int limit) throws Exception {
        awaitIdle();
        return manager.listSnapshots(limit);
    }

    public RestoreResult restorePreTurn(int offset) throws Exception {
        awaitIdle();
        return manager.restorePreTurn(offset);
    }

    public void awaitIdle() throws Exception {
        Future<?> task = lastAsyncTask;
        if (task != null) {
            task.get(60, TimeUnit.SECONDS);
        }
    }

    private static String turnId(String mode) {
        String safeMode = mode == null || mode.isBlank() ?
                "turn" : mode.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        return safeMode + "-" + Instant.now().toEpochMilli();
    }

    private static String summarize(String mode, String input) {
        String normalized = input == null ? "" : input.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 120) {
            normalized = normalized.substring(0, 120) + "...";
        }

        return "mode=" + (mode == null ? "turn" : mode) + "\ninput=" + normalized;
    }

    public String status() {
        return manager.formatStatus();
    }

    public String clean() {
        return manager.cleanSnapshots();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
