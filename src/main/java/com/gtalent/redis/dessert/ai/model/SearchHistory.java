package com.gtalent.redis.dessert.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * 使用者搜尋紀錄。
 *
 * <p>用來累積使用者的搜尋關鍵字，除了可作為熱門關鍵字統計、搜尋建議的資料來源，
 * 也可以餵給 RAG 問答助手作為「使用者近期關注甜點品項」的上下文參考。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "search_history")
@CompoundIndexes({
        @CompoundIndex(name = "user_time_idx", def = "{'userId': 1, 'timestamp': -1}")
})
public class SearchHistory {

    @Id
    private String id;

    /** 搜尋者的使用者 ID（訪客可用匿名/裝置代碼）。 */
    @Indexed
    @Field("user_id")
    private String userId;

    /** 搜尋關鍵字，建議加上索引以利熱門關鍵字彙總查詢。 */
    @Indexed
    @Field("keyword")
    private String keyword;

    /** 本次搜尋回傳的結果筆數，可用於判斷是否為「零結果搜尋」（潛在商機/缺貨線索）。 */
    @Field("result_count")
    private Integer resultCount;

    /** 搜尋發生時間。 */
    @Indexed
    @Field("timestamp")
    private LocalDateTime timestamp;
}