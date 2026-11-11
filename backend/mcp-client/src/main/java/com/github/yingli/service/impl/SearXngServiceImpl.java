package com.github.yingli.service.impl;


import cn.hutool.json.JSONUtil;
import com.github.yingli.entity.SearXngResponse;
import com.github.yingli.entity.SearchResult;
import com.github.yingli.service.SearXngService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

/**
 * @Author oliver
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.feature.websearch-enabled", havingValue = "true")
public class SearXngServiceImpl implements SearXngService {

    @Value("${internet.websearch.searxng.url}")
    private String SEARXNG_URL;

    @Value("${internet.websearch.searxng.counts}")
    private Integer COUNTS;

    private final OkHttpClient okHttpClient;

    @Override
    public List<SearchResult> search(String query) {
        HttpUrl url = HttpUrl.get(SEARXNG_URL).newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .build();
        log.info("搜索的url地址为:{}", url.url());
        // 构建request
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "application/json")
                .build();

        // 发送请求
        try (Response response = okHttpClient.newCall(request).execute()) {
            // 判断请求是否成功
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            if (response.body() != null) {
                String responseBody = response.body().string();
                log.info("SearXNG 返回的原始JSON数据: {}", responseBody);
                SearXngResponse searXngResponse = JSONUtil.toBean(responseBody, SearXngResponse.class);
                // 使用JSON工具将响应体转化为对应的类型
                return dealResults(searXngResponse.getResults());
            }
            log.error("搜索失败:{}", response.message());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return List.of();
    }

    /**
     * 处理结果集, 截取限制的个数
     *
     * @param results
     * @return
     */
    public List<SearchResult> dealResults(List<SearchResult> results) {
        return results.subList(0, Math.min(results.size(), COUNTS))
                .parallelStream()
                .sorted(Comparator.comparingDouble(SearchResult::getScore).reversed())
                .limit(COUNTS)
                .toList();
    }
}
