package com.gtalent.redis.dessert.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;

/**
 * RAG 向量資料庫配置。
 *
 * <p><b>兩種模式，透過 {@code app.rag.vector-store.mode} 切換：</b>
 * <ul>
 *     <li>{@code mongodb-atlas}（預設 / 正式環境）：使用
 *     {@link org.springframework.ai.vectorstore.mongodb.atlas.MongoDBAtlasVectorStore}，
 *     透過 {@code spring.data.mongodb.uri} 連到 MongoDB Atlas 或 Atlas 相容的部署。</li>
 *     <li>{@code simple}（快速測試）：使用 {@link SimpleVectorStore} 記憶體向量庫，
 *     支援從本地 JSON 還原 / 落盤。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class RagVectorStoreConfig {

    @Value("${app.rag.vector-store.simple-store-file:./data/dessert-vector-store.json}")
    private String simpleStoreFilePath;

    private SimpleVectorStore simpleVectorStore;

    @Bean
    @ConditionalOnProperty(prefix = "app.rag.vector-store", name = "mode", havingValue = "simple")
    public VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        this.simpleVectorStore = SimpleVectorStore.builder(embeddingModel).build();

        File storeFile = new File(simpleStoreFilePath);
        if (storeFile.exists()) {
            log.info("從本地檔案還原 SimpleVectorStore：{}", simpleStoreFilePath);
            simpleVectorStore.load(storeFile);
        }
        return simpleVectorStore;
    }

    /**
     * 應用程式關閉前，把記憶體向量庫的內容寫回本地檔案，
     * 避免每次重啟都要對所有甜點知識重新呼叫一次 Embedding API（浪費費用與時間）。
     * 僅在 simple 模式下有意義；mongodb-atlas 模式下資料本來就落地在 Mongo，不需要這個機制。
     */
    @PreDestroy
    public void persistSimpleVectorStoreOnShutdown() {
        if (simpleVectorStore != null) {
            File storeFile = new File(simpleStoreFilePath);
            storeFile.getParentFile().mkdirs();
            simpleVectorStore.save(storeFile);
            log.info("SimpleVectorStore 已寫回本地檔案：{}", simpleStoreFilePath);
        }
    }
}
