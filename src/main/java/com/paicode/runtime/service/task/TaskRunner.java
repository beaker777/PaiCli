package com.paicode.runtime.service.task;

/**
 * @Author beaker
 * @Date 2026/10/4 04:35
 * @Description
 */
@FunctionalInterface
public interface TaskRunner {

    String run(String prompt) throws Exception;
}
