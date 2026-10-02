package com.paicode.renderer.entity;

import com.paicode.renderer.constant.OpType;

/**
 * @Author beaker
 * @Date 2026/10/2 04:04
 * @Description 更改类型
 */
public record DiffOp(OpType type, String text, int beforeIndex, int afterIndex) {
}
