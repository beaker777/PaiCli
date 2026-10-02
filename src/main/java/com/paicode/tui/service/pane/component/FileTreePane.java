package com.paicode.tui.service.pane.component;

import com.googlecode.lanterna.gui2.CheckBoxList;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.paicode.config.PaiCodeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * @Author beaker
 * @Date 2026/10/1 20:49
 * @Description 左侧的文件树面板
 */
public class FileTreePane extends Panel {

    private static final List<String> DEFAULT_IGNORE = List.of(
            ".git", "node_modules", "target", "dist", ".idea", "*.class", "*.jar"
    );

    private final List<Path> projectRoots;
    private final List<String> ignorePatterns;
    private final CheckBoxList<String> fileList;

    /**
     * 创建文件树面板
     */
    public FileTreePane(PaiCodeConfig config) {
        super();
        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        // 初始化忽略策略
        this.ignorePatterns = loadIgnorePatterns(config);

        // 初始化项目根
        Path projectRoot = Path.of("").toAbsolutePath();
        this.projectRoots = List.of(projectRoot);

        // 创建文件列表
        this.fileList = new CheckBoxList<>();
        loadFiles(projectRoot);

        addComponent(fileList.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));
    }

    /**
     * 加载忽略规则（用户级 + 内置默认）。
     */
    private static List<String> loadIgnorePatterns(PaiCodeConfig config) {
        List<String> patterns = new ArrayList<>(DEFAULT_IGNORE);

        // 读取用户级 filetree-ignore.txt
        Path userIgnore = Path.of(System.getProperty("user.home"), ".paicode", "filetree-ignore.txt");
        if (Files.exists(userIgnore)) {
            try {
                Files.readAllLines(userIgnore).stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(patterns::add);
            } catch (IOException e) {
                System.err.println("⚠️ 读取 filetree-ignore.txt 失败: " + e.getMessage());
            }
        }

        return patterns;
    }

    /**
     * 加载项目文件列表。
     */
    private void loadFiles(Path root) {
        fileList.clearItems();

        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(path -> !matchesIgnore(path.getFileName().toString(), ignorePatterns))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> fileList.addItem(path.getFileName().toString()));
        } catch (IOException e) {
            System.err.println("⚠️ 列出目录失败: " + root + " - " + e.getMessage());
        }
    }

    /**
     * 检查路径是否匹配忽略规则（简单 glob 匹配）。
     */
    private static boolean matchesIgnore(String name, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.contains("*")) {
                String regex = pattern.replace(".", "\\.").replace("*", ".*");
                if (name.matches(regex)) {
                    return true;
                }
            } else {
                if (name.equals(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void refresh() {
        if (!projectRoots.isEmpty()) {
            loadFiles(projectRoots.get(0));
        }
    }
}
