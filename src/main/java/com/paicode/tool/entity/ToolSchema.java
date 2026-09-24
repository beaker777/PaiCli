package com.paicode.tool.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * @Author beaker
 * @Date 2026/9/8 22:26
 * @Description 工具参数构建
 */
public class ToolSchema {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());

            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }
}
