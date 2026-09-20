package com.paicode.llm.DTO;

import lombok.Getter;
import lombok.Setter;

/**
 * @Author beaker
 * @Date 2026/9/19 20:52
 * @Description 工具调用累加器
 */
@Getter
@Setter
public class ToolCallAccumulator {

    private String id;
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();
}
