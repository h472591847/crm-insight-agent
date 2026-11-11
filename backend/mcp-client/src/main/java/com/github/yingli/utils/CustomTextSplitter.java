package com.github.yingli.utils;


import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.List;

/**
 * @Author oliver
 */
public class CustomTextSplitter extends TextSplitter {

    @Override
    protected List<String> splitText(String text) {
        return List.of(split(text));
    }

    private String[] split(String text) {
        return text.split("\\r\\n\\r\\n");
    }
}
