package com.paicode.image.service;

import com.paicode.image.entity.GrabResult;
import com.paicode.image.entity.OsascriptOutcome;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * @Author beaker
 * @Date 2026/10/4 07:33
 * @Description 剪贴板图片处理
 * Windows 系统使用 Java Awt, MacOS 使用 Osascript, 保存到 cache 目录中
 */
public class ClipboardImage {

    private static final Path DEFAULT_CACHE_DIR = Path.of(
            System.getProperty("user.home"), ".paicode", "cache");

    private static final String MAC_CLIPBOARD_PNG_SCRIPT = """
            on run argv
                set outputPath to item 1 of argv
                try
                    set pngData to (the clipboard as «class PNGf»)
                on error errMsg
                    error "剪贴板里没有 PNG 数据"
                end try
                set fh to open for access (POSIX file outputPath as string) with write permission
                try
                    set eof of fh to 0
                    write pngData to fh
                    close access fh
                on error errMsg
                    try
                        close access fh
                    end try
                    error errMsg
                end try
            end run
            """;

    private static final String MAC_CLIPBOARD_TIFF_SCRIPT = """
            on run argv
                set outputPath to item 1 of argv
                try
                    set tiffData to (the clipboard as «class TIFF»)
                on error errMsg
                    error "剪贴板里没有 TIFF 数据"
                end try
                set fh to open for access (POSIX file outputPath as string) with write permission
                try
                    set eof of fh to 0
                    write tiffData to fh
                    close access fh
                on error errMsg
                    try
                        close access fh
                    end try
                    error errMsg
                end try
            end run
            """;

    public static GrabResult grab() {
        return grab(DEFAULT_CACHE_DIR);
    }

    private static GrabResult grab(Path cacheDir) {
        if (isMac()) {
            return grabWithMacPasteboard(cacheDir);
        }
        if (GraphicsEnvironment.isHeadless()) {
            return GrabResult.error("当前环境无 GUI（headless），无法读取系统剪贴板");
        }
        try {
            Clipboard clip = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!clip.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                return GrabResult.error("剪贴板里没有图片，请先截图后再触发（macOS: Cmd+Shift+Ctrl+4 / Windows: Snipping Tool）");
            }
            Object data = clip.getData(DataFlavor.imageFlavor);
            if (!(data instanceof Image img)) {
                return GrabResult.error("剪贴板返回的数据不是图片对象: " + (data == null ? "null" : data.getClass().getName()));
            }
            BufferedImage buffered = toBufferedImage(img);
            Files.createDirectories(cacheDir);
            Path file = cacheDir.resolve("clip-" + System.currentTimeMillis() + ".png");
            ImageIO.write(buffered, "png", file.toFile());
            return GrabResult.ok(file);
        } catch (HeadlessException e) {
            return GrabResult.error("当前环境无 GUI（headless），无法读取系统剪贴板");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return GrabResult.error("读取剪贴板图片失败: " + msg);
        }
    }


    public static String describe(Path imagePath) {
        if (imagePath == null) {
            return "";
        }
        try {
            long bytes = Files.size(imagePath);
            BufferedImage image = ImageIO.read(imagePath.toFile());
            String size = humanBytes(bytes);
            if (image == null) {
                return imagePath.getFileName() + " (" + size + ")";
            }
            return imagePath.getFileName() + " (" + image.getWidth() + "x" + image.getHeight() + ", " + size + ")";
        } catch (Exception e) {
            return imagePath.getFileName().toString();
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static GrabResult grabWithMacPasteboard(Path cacheDir) {
        Path file = null;
        Path tiff = null;
        try {
            Files.createDirectories(cacheDir);
            long stamp = System.currentTimeMillis();
            file = cacheDir.resolve("clip-" + stamp + ".png");

            OsascriptOutcome pngOutcome = runOsascript(MAC_CLIPBOARD_PNG_SCRIPT, file.toAbsolutePath().toString());
            if (pngOutcome.timeOut()) {
                Files.deleteIfExists(file);
                return GrabResult.error("读取剪贴板图片超时");
            }
            if (pngOutcome.exitCode() == 0 && Files.isRegularFile(file) && Files.size(file) > 0) {
                return GrabResult.ok(file);
            }
            Files.deleteIfExists(file);

            tiff = cacheDir.resolve("clip-" + stamp + ".tiff");
            OsascriptOutcome tiffOutcome = runOsascript(MAC_CLIPBOARD_TIFF_SCRIPT, tiff.toAbsolutePath().toString());
            if (tiffOutcome.timeOut()) {
                return GrabResult.error("读取剪贴板图片超时");
            }
            if (tiffOutcome.exitCode() == 0 && Files.isRegularFile(tiff) && Files.size(tiff) > 0) {
                if (convertTiffToPng(tiff, file)) {
                    return GrabResult.ok(file);
                }
            }

            String stderr = pngOutcome.stderr().isBlank() ? tiffOutcome.stderr() : pngOutcome.stderr();
            if (stderr.isBlank()) {
                stderr = "剪贴板里没有图片，请先截图后再触发";
            }
            return GrabResult.error(stderr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GrabResult.error("读取剪贴板图片被中断");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return GrabResult.error("macOS 原生剪贴板读取失败: " + msg);
        } finally {
            if (tiff != null) {
                try {
                    Files.deleteIfExists(tiff);
                } catch (Exception ignore) {
                    // 残留 .tiff 不影响功能，不传播
                }
            }
        }
    }

    private static OsascriptOutcome runOsascript(String script, String outputPath) throws IOException, InterruptedException {
        // osascript 接受 `-` 表示从 stdin 读脚本，可以避免落地临时文件。
        Process process = new ProcessBuilder("/usr/bin/osascript", "-", outputPath).start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        boolean completed = process.waitFor(8, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            return new OsascriptOutcome(-1, "", true);
        }
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return new OsascriptOutcome(process.exitValue(), stderr, false);
    }

    private static boolean convertTiffToPng(Path tiff, Path png) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                "/usr/bin/sips", "-s", "format", "png", tiff.toString(), "--out", png.toString())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(8, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0 && Files.isRegularFile(png) && Files.size(png) > 0;
    }

    private static BufferedImage toBufferedImage(Image img) {
        if (img instanceof BufferedImage bi) {
            return bi;
        }
        int width = Math.max(img.getWidth(null), 1);
        int height = Math.max(img.getHeight(null), 1);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        double kb = bytes / 1024.0d;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.0fKB", kb);
        }
        return String.format(Locale.ROOT, "%.1fMB", kb / 1024.0d);
    }
}
