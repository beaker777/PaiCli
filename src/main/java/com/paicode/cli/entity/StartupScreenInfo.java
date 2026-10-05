package com.paicode.cli.entity;

/**
 * @Author beaker
 * @Date 2026/10/5 22:00
 * @Description
 */
public record StartupScreenInfo(String model, String provider,
                                long mcpReady, int mcpTotal, int mcpTools,
                                int skillsEnabled, int skillsTotal, String note) {
}
