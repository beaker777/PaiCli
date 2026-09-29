package com.paicode.browser.service.connect;

/**
 * @Author beaker
 * @Date 2026/9/29 21:08
 * @Description 浏览器连接接口
 */
public interface BrowserConnector {

    String status();

    String connectDefault();

    String disconnect();
}
