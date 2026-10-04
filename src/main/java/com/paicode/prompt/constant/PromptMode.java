package com.paicode.prompt.constant;

/**
 * @Author beaker
 * @Date 2026/10/4 07:01
 * @Description Prompt 模式
 */
public enum PromptMode {

    AGENT("modes/agent.md"),
    PLAN("modes/plan.md"),
    PLANNER("modes/planner.md"),
    TEAM_PLANNER("modes/team-planner.md"),
    TEAM_WORKER("modes/team-worker.md"),
    TEAM_REVIEWER("modes/team-reviewer.md");

    private final String resourcePath;

    PromptMode(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    public String resourcePath() {
        return resourcePath;
    }
}
