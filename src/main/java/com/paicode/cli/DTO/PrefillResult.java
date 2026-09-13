package com.paicode.cli.DTO;

/**
 * @Author beaker
 * @Date 2026/9/13 16:44
 * @Description 预填充结果
 */
public record PrefillResult(String seedBuffer, boolean canceled, boolean submitted) {

    public static PrefillResult canceledInput() {
        return new PrefillResult("", true, false);
    }

    public static PrefillResult submittedInput() {
        return new PrefillResult("", false, true);
    }

    public static PrefillResult seed(String seedBuffer) {
        return new PrefillResult(seedBuffer, false, false);
    }
}
