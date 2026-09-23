package com.paicode.rag.entity;

/**
 * @Author beaker
 * @Date 2026/9/16 21:44
 * @Description 代码关系
 */
public record CodeRelation(String fromFile, String fromName, String toFile, String toName, String relationType) {
}
