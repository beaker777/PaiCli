package com.paicode.snapshot.entity;

import com.paicode.snapshot.constant.SnapshotPhase;

import java.time.Instant;

/**
 * @Author beaker
 * @Date 2026/10/3 19:57
 * @Description
 */
public record TurnSnapshot(String commitId, SnapshotPhase phase,
                           String turnId, Instant createdAt, String summary) {

    public String shortCommitId() {
        return commitId == null || commitId.length() <= 10 ? commitId : commitId.substring(0, 10);
    }
}
