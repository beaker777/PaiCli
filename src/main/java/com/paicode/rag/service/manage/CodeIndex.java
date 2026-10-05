package com.paicode.rag.service.manage;

import com.paicode.rag.entity.*;
import com.paicode.rag.service.chunk.CodeChunker;
import com.paicode.rag.service.chunk.VectorStore;
import com.paicode.rag.service.embedding.EmbeddingClient;
import com.paicode.rag.service.retrieve.CodeAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/17 17:49
 * @Description 代码索引管理器
 */
public class CodeIndex {

    private static final Logger log = LoggerFactory.getLogger(CodeIndex.class);
    private final EmbeddingClient embeddingClient;
    private final CodeChunker chunker;
    private final CodeAnalyzer analyzer;
    private final ProgressListener progressListener;

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message);

        static ProgressListener noop() {
            return message -> {
            };
        }
    }

    public CodeIndex() {
        this(new EmbeddingClient(), ProgressListener.noop());
    }

    public CodeIndex(EmbeddingClient embeddingClient) {
        this(embeddingClient, ProgressListener.noop());
    }

    public CodeIndex(ProgressListener progressListener) {
        this(new EmbeddingClient(), progressListener);
    }

    public CodeIndex(EmbeddingClient embeddingClient, ProgressListener progressListener) {
        this.embeddingClient = embeddingClient;
        this.chunker = new CodeChunker();
        this.analyzer = new CodeAnalyzer();
        this.progressListener = progressListener == null ? ProgressListener.noop() : progressListener;
    }

    public IndexResult index(String projectPath) throws Exception {
        Path root = Paths.get(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            String message = "路径不存在: " + projectPath;
            emit("❌ " + message);
            return new IndexResult(0, 0, message);
        }

        emit("🔍 开始索引: " + root);

        // 获取项目文件
        List<Path> filesToIndex = new ArrayList<>();
        collectFiles(root, filesToIndex);
        System.out.println("发现 " + filesToIndex.size() + " 个文件待索引");

        List<CodeChunkEntry> entries = new ArrayList<>();
        List<CodeRelation> allRelations = new ArrayList<>();

        int processed = 0;
        int total = filesToIndex.size();

        for (Path file : filesToIndex) {
            processed ++;
            if (processed % 10 == 0 || processed == total) {
                emit(String.format("   进度: %d/%d (%s)", processed, total, file.getFileName()));
            }

            try {
                // 分块
                List<CodeChunk> chunks = chunker.chunkFile(file);

                // 生成 embeddings 并组装条目
                for (CodeChunk chunk : chunks) {
                    float[] embedding = embeddingClient.embed(chunk.toEmbeddingText());
                    entries.add(new CodeChunkEntry(chunk, embedding));
                }

                // 分析关系
                if (file.toString().endsWith(".java")) {
                    allRelations.addAll(analyzer.analyzeFile(file));
                }
            } catch (Exception e) {
                String message = "   ⚠️ 索引失败: " + file + " - " + e.getMessage();
                emit(message);
                log.warn("code index failed for file {}", file, e);
            }
        }

        // 持久化到 SQLite
        try (VectorStore store = new VectorStore(root.toString())) {
            store.clearProject();
            store.insertChunks(entries);
            store.insertRelations(allRelations);

            IndexStats stats = store.getStats();
            String msg = String.format("索引完成: %d 个代码块, %d 条关系", stats.chunkCount(), stats.relationCount());
            emit("✅ " + msg);

            return new IndexResult(stats.chunkCount(), stats.relationCount(), msg);
        } catch (Exception e) {
            String error = "持久化失败: " + e.getMessage();
            emit("❌ " + error);
            log.warn("code index persistence failed for root {}", root, e);
            return new IndexResult(0, 0, error);
        }
    }

    private void emit(String message) {
        progressListener.onProgress(message);
    }

    private void collectFiles(Path root, List<Path> files) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    // 跳过常见非代码目录
                    if (dirName.equals("node_modules") || dirName.equals("target")
                            || dirName.equals("build") || dirName.equals(".git")
                            || dirName.equals(".idea") || dirName.equals(".vscode")
                            || dirName.equals("dist") || dirName.equals("out")
                            || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    // 只索引文本代码文件
                    if (name.endsWith(".java") || name.endsWith(".py")
                            || name.endsWith(".js") || name.endsWith(".ts")
                            || name.endsWith(".go") || name.endsWith(".rs")
                            || name.endsWith(".c") || name.endsWith(".cpp")
                            || name.endsWith(".h") || name.endsWith(".md")
                            || name.endsWith(".xml") || name.endsWith(".properties")
                            || name.endsWith(".yaml") || name.endsWith(".yml")
                            || name.endsWith(".json") || name.endsWith(".sh")
                            || name.endsWith(".gradle") || name.endsWith(".kt")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            String message = "遍历文件失败: " + e.getMessage();
            emit("❌ " + message);
            log.warn("code index file traversal failed for root {}", root, e);
        }
    }
}
