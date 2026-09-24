package com.paicode.web.service.provider;

import com.paicode.web.entity.SearchResult;

import java.io.IOException;
import java.util.List;

/**
 * @Author beaker
 * @Date 2026/9/24 17:32
 * @Description 搜索引擎抽象
 */
public interface SearchProvider {

    /**
     * @return provider 名称
     */
    String name();

    /**
     * @return 是否可用
     */
    boolean isReady();

    /**
     * @return 当搜索引擎不可用时给用户的提示
     */
    String unavailableHint();

    /**
     * 执行搜索
     */
    List<SearchResult> search(String query, int topK) throws IOException;
}
