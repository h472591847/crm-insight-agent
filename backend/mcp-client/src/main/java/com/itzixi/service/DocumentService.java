package com.itzixi.service;


import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * @Author oliver
 */
public interface DocumentService {
    /**
     * 加载文档, 读取数据保存到知识库
     *
     * @param resource
     * @param fileName
     */
    List<Document> loadText(Resource resource, String fileName);

    /**
     * 根据提问从知识库中查询相应的资料(相似)
     *
     * @param question
     * @return
     */
    List<Document> doSearch(String question);

}
