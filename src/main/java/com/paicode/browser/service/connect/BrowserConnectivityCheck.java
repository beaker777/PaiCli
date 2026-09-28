package com.paicode.browser.service.connect;

import com.paicode.browser.entity.BrowserCheckResult;
import com.paicode.browser.entity.ProbeResult;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.time.Duration;

/**
 * @Author beaker
 * @Date 2026/9/28 20:40
 * @Description 浏览器连接检查
 */
public class BrowserConnectivityCheck {

    private final OkHttpClient client;

    public BrowserConnectivityCheck() {
        this(new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .callTimeout(Duration.ofSeconds(2))
                .build());
    }

    public BrowserConnectivityCheck(OkHttpClient client) {
        this.client = client;
    }

    public ProbeResult probe(int port) {
        if (port < 1024 || port > 65535) {
            return ProbeResult.failed("端口必须在 1024-65535 之间");
        }

        String url = "http://127.0.0.1:" + port + "/json/version";
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return ProbeResult.failed("HTTP " + response.code());
            }

            return ProbeResult.ok("http://127.0.0.1:" + port);
        } catch (Exception e) {
            return ProbeResult.failed(e.getMessage());
        }
    }

}
