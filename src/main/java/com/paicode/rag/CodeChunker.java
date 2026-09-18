package com.paicode.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.paicode.rag.DTO.CodeChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/16 19:33
 * @Description 代码分块器, 将代码拆分为适合 embedding 的粒度
 * - Java 文件: 类级别 + 方法级别分块
 * - 非 Java 文件: 整个文件作为一个分块
 */
public class CodeChunker {

    // 设置语言级别为 Java17
    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    // 单个 chunk 的最大字符数, 2000 字符 -> 4000 ~ 6000 tokens, 适配 8192 上下文
    private final static int MAX_CHUNK_CHARS = 2000;

    /**
     * 对单个文件进行分块
     */
    public List<CodeChunk> chunkFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String relativePath = filePath.toString();

        // 非 Java 文件
        if (!relativePath.endsWith(".java")) {
            return chunkLargeText(relativePath, content);
        }

        // Java 文件
        return chunkJavaFile(filePath, content);
    }

    /**
     * 将大文本进行分段, 每段不超过 MAX_CHUNK_CHARS
     */
    private List<CodeChunk> chunkLargeText(String filePath, String content) {
        if (content.length() < MAX_CHUNK_CHARS) {
            return List.of(CodeChunk.fileChunk(filePath, content));
        }

        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\n");
        StringBuilder segment = new StringBuilder();
        // 分段数量
        int segIndex = 1;
        // 起始行号
        int startLine = 1;

        for (int i = 0; i < lines.length; i++) {
            if (segment.length() + lines[i].length() > MAX_CHUNK_CHARS && !segment.isEmpty()) {
                chunks.add(new CodeChunk(filePath, "file",
                        filePath + "#" + segIndex, segment.toString(), startLine, i));
                segment.setLength(0);
                segIndex ++;
                startLine = i + 1;
            }

            segment.append(lines[i]).append("\n");
        }

        if (!segment.isEmpty()) {
            chunks.add(new CodeChunk(filePath, "file",
                    filePath + "#" + segIndex, segment.toString(), startLine, lines.length));
        }

        return chunks;
    }

    /**
     * 将 Java 文件进行分块, 使用 AST 分块
     */
    private List<CodeChunk> chunkJavaFile(Path filePath, String content) {
        List<CodeChunk> chunks = new ArrayList<>();
        ParseResult<CompilationUnit> result = parser.parse(content);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            // 解析失败, 使用长文本分块
            return chunkLargeText(filePath.toString(), content);
        }

        CompilationUnit cu = result.getResult().get();

        // 遍历所有类 / 接口声明
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(declaration -> {
            Integer classStart = declaration.getBegin().map(p -> p.line).orElse(0);
            Integer classEnd = declaration.getEnd().map(p -> p.line).orElse(0);
            String className = declaration.getNameAsString();

            // 提取类声明文本, 内容最多只取到前 5 行
            String classHeader = extractLines(content, classStart, Math.min(classEnd + 5, classEnd));

            // 类级别 chunk
            chunks.add(CodeChunk.classChunk(filePath.toString(), className, classHeader, classStart, classEnd));

            // 方法级别 chunk
            declaration.getMethods().forEach(method -> {
                Integer methodStart = method.getBegin().map(p -> p.line).orElse(0);
                Integer methodEnd = method.getEnd().map(p -> p.line).orElse(0);
                String methodSignature = method.getDeclarationAsString(false, false, false);
                String methodContent = extractLines(content, methodStart, methodEnd);

                chunks.add(CodeChunk.methodChunk(filePath.toString(),
                        className + "." + methodSignature,
                        methodContent, methodStart, methodEnd));
            });
        });

        // AST 解析失败, 使用长文本分块兜底
        if (chunks.isEmpty()) {
            return chunkLargeText(filePath.toString(), content);
        }
        return chunks;
    }

    private String extractLines(String content, int startLine, int endLine) {
        String[] lines = content.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (int i = startLine - 1; i < Math.min(endLine, lines.length); i++) {
            if (i >= 0) {
                sb.append(lines[i]).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
