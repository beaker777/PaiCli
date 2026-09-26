package com.paicode.policy.entity;

import com.paicode.policy.service.audit.AuditLog;

import java.time.Instant;

/**
 * @Author beaker
 * @Date 2026/9/25 01:20
 * @Description 审计条目
 */
public record AuditEntry(String timestamp, String tool, String args, String outcome, String reason, String approver, long durationMs) {

    public static final String APPROVER_HITL = "hitl";
    public static final String APPROVER_POLICY = "policy";
    public static final String APPROVER_NONE = "none";
    public static final String APPROVER_MENTION = "mention";


    public static final String OUTCOME_ALLOW = "allow";
    public static final String OUTCOME_DENY = "deny";
    public static final String OUTCOME_ERROR = "error";

    public static AuditEntry allow(String tool, String args, long durationMs) {
        return new AuditEntry(Instant.now().toString(), tool, AuditLog.truncate(args),
                OUTCOME_ALLOW, null, APPROVER_NONE, durationMs);
    }

    public static AuditEntry allowByMention(String tool, String args, long durationMs) {
        return new AuditEntry(Instant.now().toString(), tool, AuditLog.truncate(args),
                OUTCOME_ALLOW, null, APPROVER_MENTION, durationMs);
    }

    public static AuditEntry denyByHitl(String tool, String args, String reason, long durationMs) {
        return new AuditEntry(Instant.now().toString(), tool, AuditLog.truncate(args),
                OUTCOME_DENY, reason, APPROVER_HITL, durationMs);
    }

    public static AuditEntry denyByPolicy(String tool, String args, String reason, long durationMs) {
        return new AuditEntry(Instant.now().toString(), tool, AuditLog.truncate(args),
                OUTCOME_DENY, reason, APPROVER_POLICY, durationMs);
    }

    public static AuditEntry error(String tool, String args, String reason, long durationMs) {
        return new AuditEntry(Instant.now().toString(), tool, AuditLog.truncate(args),
                OUTCOME_ERROR, reason, APPROVER_NONE, durationMs);
    }
}
