package com.github.yingli;

/**
 * 主入口
 *
 * @author oliver.li
 */

import com.github.yingli.config.SseProperties;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SseProperties.class)
public class Application {
    public static void main(String[] args) {
        // 加载.env文件
        Dotenv dotEnv = Dotenv.configure().ignoreIfMissing().load();
        // 将.env文件内的环境变量加载到系统属性中
        dotEnv.entries().forEach(entry ->
                System.setProperty(entry.getKey(), entry.getValue()));
        SpringApplication.run(Application.class, args);
    }
}
