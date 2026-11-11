package com.github.yingli.config;


import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * @Author oliver
 */
@Configuration
public class OkHttpConfig  implements WebMvcConfigurer {

    @Value("${ok.http.read-timeout}")
    private Integer readTimeout;

    @Value("${ok.http.write-timeout}")
    private Integer writeTimeout;

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .writeTimeout(writeTimeout, TimeUnit.SECONDS)
                .build();
    }
}
