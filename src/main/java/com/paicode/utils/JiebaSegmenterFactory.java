package com.paicode.utils;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * @Author beaker
 * @Date 2026/9/19 19:57
 * @Description jieba-analysis 在首次加载词典时会直接向 stdout 输出初始化信息, 这里静默输出, 避免污染用户界面
 */
public class JiebaSegmenterFactory {

    public static JiebaSegmenter createSilently() {
        synchronized (JiebaSegmenterFactory.class) {
            PrintStream originalOut = System.out;
            try {
                System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
                return new JiebaSegmenter();
            } finally {
                System.setOut(originalOut);
            }
        }
    }
}
