package com.github.yingli.service.impl;


import com.github.yingli.service.DocumentService;
import com.github.yingli.utils.CustomTextSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Author oliver
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.feature.rag-enabled", havingValue = "true")
public class DocumentServiceImpl implements DocumentService {

    private final RedisVectorStore redisVectorStore;

    @Override
    public List<Document> loadText(Resource resource, String fileName) {
        TextReader textReader = new TextReader(resource);
        textReader.getCustomMetadata().put("fileName", fileName);
        List<Document> documentList = textReader.get();
        //默认的文本切分器
//        TokenTextSplitter tokenTextSplitter = new TokenTextSplitter();
//        List<Document> list = tokenTextSplitter.apply(documentList);
        //自定义的文本切分器
        CustomTextSplitter customTextSplitter = new CustomTextSplitter();
        List<Document> list = customTextSplitter.apply(documentList);
        log.info("DocumentServiceImpl loadText list: {}", list);

        //向量存储
        redisVectorStore.add(list);
        return documentList;
    }

    @Override
    public List<Document> doSearch(String question) {
        return redisVectorStore.similaritySearch(question);
    }
}
