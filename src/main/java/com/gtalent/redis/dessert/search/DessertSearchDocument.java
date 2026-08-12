package com.gtalent.redis.dessert.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.math.BigDecimal;

/**
 * 甜點在 Elasticsearch 裡的搜尋文件，對應 index：dessert。
 *
 * <p>這份索引只服務「站內甜點全文/模糊搜尋」這個場景，跟既有的
 * MongoDB Atlas VectorStore（AI / RAG 語意檢索，見技術文件 5.8 節）是兩條
 * 完全獨立的管線，互不取代：VectorStore 處理「AI 顧問要不要推薦這個」，
 * Elasticsearch 處理「使用者在搜尋框打字找甜點」。</p>
 *
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(indexName = "dessert")
@Setting(settingPath = "elasticsearch/dessert-settings.json")
public class DessertSearchDocument {

    @Id
    private Long id;

    /** 品項名稱，改用自訂 dessert_name_analyzer（standard tokenizer + cjk_bigram，
     *  output_unigrams=true），同時保留單字詞與雙字詞，支援單一中文字也能搜到 */
    @Field(type = FieldType.Text, analyzer = "dessert_name_analyzer")
    private String name;

    @Field(type = FieldType.Double)
    private BigDecimal price;

    @Field(type = FieldType.Integer)
    private Integer stock;

    @Field(type = FieldType.Boolean)
    private Boolean enabled;
}