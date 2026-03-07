package com.techie.springai.rag;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动入口。
 *
 * <p>职责：
 * <ul>
 *   <li>加载 Spring Boot 自动配置</li>
 *   <li>扫描并注册项目中的 Controller / Service / 配置类</li>
 *   <li>启动内嵌 Web 容器（默认端口由 application.properties 决定）</li>
 * </ul>
 */
@SpringBootApplication
public class SpringAiRagTutorialApplication {

    /**
     * Java 主函数：启动整个 RAG 后端服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(SpringAiRagTutorialApplication.class, args);
    }
}
