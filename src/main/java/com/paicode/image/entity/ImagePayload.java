package com.paicode.image.entity;

import com.paicode.image.service.ImageProcessor;

import java.nio.file.Path;

/**
 * @Author beaker
 * @Date 2026/10/4 20:49
 * @Description 图片载体
 */
public record ImagePayload(boolean ok, ProcessedImage image, String error) {

    public Path path() {
        return image == null ? null : image.sourcePath();
    }

    public static ImagePayload ok(ProcessedImage image) {
        return new ImagePayload(true, image, null);
    }

    public static ImagePayload error(String error) {
        return new ImagePayload(false, null, error);
    }
}
