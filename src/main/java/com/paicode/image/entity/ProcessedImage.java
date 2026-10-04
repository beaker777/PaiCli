package com.paicode.image.entity;

import java.nio.file.Path;

/**
 * @Author beaker
 * @Date 2026/10/4 19:27
 * @Description 加工后的图片
 */
public record ProcessedImage(String base64, String mimeType,
                             long originalBytes, long outputBytes,
                             Dimensions dimensions, Path sourcePath, boolean reencoded) {
}
