package com.paicode.tool.service.tools.code;

import com.paicode.tool.entity.CodeSearchRequest;
import com.paicode.tool.entity.CodeSearchResult;

/**
 * @Author beaker
 * @Date 2026/10/7 08:57
 * @Description 代码搜索引擎
 */
public interface CodeSearchEngine {

    CodeSearchResult search(CodeSearchRequest request);
}
