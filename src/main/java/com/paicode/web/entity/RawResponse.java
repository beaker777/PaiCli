package com.paicode.web.entity;

/**
 * @Author beaker
 * @Date 2026/9/24 21:59
 * @Description 未加工的抓取结果
 */
public record RawResponse(String url, String body, String contentType, String charSet, boolean truncated) {

}
