package com.techie.springai.rag.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 启动时自动导入示例文档的服务（当前默认关闭）。
 *
 * <p>用途：
 * <ul>
 *   <li>本地演示/测试时快速初始化向量库</li>
 *   <li>避免每次手动上传样例文档</li>
 * </ul>
 *
 * <p>启用方式：取消 @Service 注释。
 */
//@Service
public class DocumentIngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    @Value("classpath:/pdf/spring-boot-reference.pdf")
    private Resource resource;
    private final VectorStore vectorStore;

    public DocumentIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        TextSplitter textSplitter = new TokenTextSplitter();
        log.info("Ingesting PDF file");
        vectorStore.accept(textSplitter.split(reader.read()));
        log.info("Completed Ingesting PDF file");
    }
}
