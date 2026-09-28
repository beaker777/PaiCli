package com.paicode.browser.entity;

import com.paicode.browser.constant.BrowserMode;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @Author beaker
 * @Date 2026/9/27 22:33
 * @Description 浏览器会话状态
 */
public class BrowserSession {

    private BrowserMode mode = BrowserMode.ISOLATED;
    private String browserUrl;
    private String lastNavigatedUrl;

    // 记录 agent 开启的 page 避免误关闭
    private final Set<String> agentOpenedTabs = new LinkedHashSet<>();

    public synchronized BrowserMode mode() {
        return mode;
    }

    public synchronized String browserUrl() {
        return browserUrl;
    }

    public synchronized String lastNavigatedUrl() {
        return lastNavigatedUrl;
    }

    public synchronized void switchToIsolated() {
        mode = BrowserMode.ISOLATED;
        browserUrl = null;
        lastNavigatedUrl = null;
        agentOpenedTabs.clear();
    }

    public synchronized void switchToShared(String browserUrl) {
        mode = BrowserMode.SHARED;
        this.browserUrl = browserUrl;
        lastNavigatedUrl = null;
        agentOpenedTabs.clear();
    }

    public synchronized void rememberNavigation(String url) {
        if (url != null && !url.isBlank()) {
            lastNavigatedUrl = url;
        }
    }

    public synchronized void recordOpenedTab(String pageId) {
        if (pageId != null && !pageId.isBlank()) {
            agentOpenedTabs.add(pageId);
        }
    }

    public synchronized boolean isAgentOpenedTab(String pageId) {
        return pageId != null && agentOpenedTabs.contains(pageId);
    }

    public synchronized Set<String> agentOpenedTabs() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(agentOpenedTabs));
    }

    public synchronized void clearAgentOpenedTabs() {
        agentOpenedTabs.clear();
    }
}
