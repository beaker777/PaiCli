package com.paicode.llm.entity;

/**
 * @Author beaker
 * @Date 2026/10/4 20:04
 * @Description
 */
public record ContentPart(String type, String text, String imageBase64, String imageUrl, String mimeType) {

    public static ContentPart text(String text) {
        return new ContentPart("text", text, null, null, null);
    }

    public static ContentPart imageBase64(String imageBase64, String mimeType) {
        return new ContentPart("image_base64", null, imageBase64, null,
                mimeType == null || mimeType.isBlank() ? "image/png" : mimeType);
    }

    public static ContentPart imageUrl(String imageUrl) {
        return new ContentPart("image_url", null, null, imageUrl, null);
    }

    public boolean isText() {
        return "text".equals(type);
    }

    public boolean isImage() {
        return "image_base64".equals(type) || "image_url".equals(type);
    }
}
