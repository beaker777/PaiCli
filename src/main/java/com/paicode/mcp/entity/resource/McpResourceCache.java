package com.paicode.mcp.entity.resource;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author beaker
 * @Date 2026/9/26 18:25
 * @Description MCP 资源缓存
 */
public class McpResourceCache {

    private final Map<String, List<McpResourceDescription>> byServer = new ConcurrentHashMap<>();
    private final Set<String> staleServers = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> staleUrisByServer = new ConcurrentHashMap<>();

    /**
     * 添加缓存, 移除过期标志
     */
    public void put(String serverName, List<McpResourceDescription> resources) {
        if (serverName == null || serverName.isBlank()) {
            return;
        }

        byServer.put(serverName, resources == null ? List.of() : List.copyOf(resources));
        staleServers.remove(serverName);
        staleUrisByServer.remove(serverName);
    }

    /**
     * 获取 server 的 resources
     */
    public List<McpResourceDescription> get(String serverName) {
        if (serverName == null || isServerStale(serverName)) {
            return List.of();
        }

        return byServer.getOrDefault(serverName, List.of());
    }

    /**
     * 获取所有有效 server 的 resource
     */
    public List<McpResourceDescription> all() {
        List<McpResourceDescription> resources = new ArrayList<>();
        byServer.keySet().stream()
                .filter(server -> !isServerStale(server))
                .sorted()
                .forEach(server -> resources.addAll(byServer.getOrDefault(server, List.of())));
        resources.sort(Comparator
                .comparing(McpResourceDescription::serverName)
                .thenComparing(McpResourceDescription::uri));
        return resources;
    }

    /**
     * 将整个 server 标记为过期
     */
    public void invalidateServer(String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            staleServers.add(serverName);
        }
    }

    /**
     * 将某个具体的 resource 设置为过期
     */
    public void invalidateResource(String serverName, String uri) {
        if (serverName == null || serverName.isBlank() || uri == null || uri.isBlank()) {
            return;
        }
        staleUrisByServer
                .computeIfAbsent(serverName, ignored -> ConcurrentHashMap.newKeySet())
                .add(uri);
    }

    /**
     * 是否过期
     */
    public boolean isServerStale(String serverName) {
        return serverName != null && staleServers.contains(serverName);
    }

    public boolean isResourceStale(String serverName, String uri) {
        if (serverName == null || uri == null) {
            return false;
        }
        return staleUrisByServer.getOrDefault(serverName, Set.of()).contains(uri);
    }

    public void markResourceFresh(String serverName, String uri) {
        Set<String> staleUris = staleUrisByServer.get(serverName);
        if (staleUris != null) {
            staleUris.remove(uri);
        }
    }
}
