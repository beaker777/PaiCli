package com.paicode.image.entity;

import java.nio.file.Path;

/**
 * @Author beaker
 * @Date 2026/10/4 07:37
 * @Description 抓取结果
 */
public record GrabResult(boolean ok, Path path, String error) {

    public static GrabResult ok(Path path) {
        return new GrabResult(true, path, null);
    }

    public static GrabResult error(String error) {
        return new GrabResult(false, null, error);
    }
}
