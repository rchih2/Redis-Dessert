package com.gtalent.redis.dessert.ai.message.ingest;

import com.gtalent.redis.dessert.ai.dto.DessertKnowledgeItem;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 直接解析 CSV 檔案，不需要任何外部轉換工具（不需要 Python / Node.js）。
 *
 * <p>用 Apache Commons CSV（{@code commons-csv}）而不是自己手刻字串切割，
 * 是因為 CSV 欄位裡如果包含逗號、換行（例如 answer 欄位常常會有長句子），
 * 用簡單的 {@code split(",")} 會直接切壞，commons-csv 能正確處理這些情況。</p>
 *
 * <p>異動說明：原本這裡還有 {@code parseKeywordRules(InputStream)}，
 * 現在已搬進 {@code message.chat.KeywordChatService#reload(InputStream)}——
 * 關鍵字範本的 CSV 解析、記憶體儲存、比對邏輯全部整合在同一個服務裡，
 * 這個類別只保留「寫進向量資料庫」用的兩種知識格式解析。</p>
 */
@Component
public class CsvKnowledgeParser {

    /**
     * 解析結構化甜點知識 CSV。
     * 預期欄位（標頭）：dessertId,name,category,content,tags
     * tags 欄位以 {@code |} 分隔多個標籤，例如：巧克力|濃郁|療癒
     */
    public List<DessertKnowledgeItem> parseDesserts(InputStream inputStream) throws IOException {
        List<DessertKnowledgeItem> items = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreSurroundingSpaces(true)
                .build()
                .parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            for (CSVRecord record : parser) {
                String tagsRaw = record.isMapped("tags") ? record.get("tags") : "";
                List<String> tags = tagsRaw == null || tagsRaw.isBlank()
                        ? List.of()
                        : Arrays.stream(tagsRaw.split("\\|"))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();

                items.add(DessertKnowledgeItem.builder()
                        .dessertId(Long.valueOf(record.get("dessertId").trim()))
                        .name(record.get("name"))
                        .category(record.get("category"))
                        .content(record.get("content"))
                        .tags(tags)
                        .build());
            }
        }
        return items;
    }

    /**
     * 解析自由文字 FAQ CSV。
     * 預期欄位（標頭）：category,question,answer,source
     * 會自動組成「問：{question}\n答：{answer}」格式的 content。
     */
    public List<TextKnowledge> parseFaq(InputStream inputStream) throws IOException {
        List<TextKnowledge> items = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreSurroundingSpaces(true)
                .build()
                .parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            for (CSVRecord record : parser) {
                String question = record.isMapped("question") ? record.get("question") : "";
                String answer = record.get("answer");
                String content = (question == null || question.isBlank())
                        ? answer
                        : "問：" + question + "\n答：" + answer;

                String category = record.isMapped("category") ? record.get("category") : "";
                String sourceRaw = record.isMapped("source") ? record.get("source") : "";
                String source = (sourceRaw == null || sourceRaw.isBlank()) ? "admin" : sourceRaw;

                items.add(new TextKnowledge(content, Map.of("category", category, "source", source)));
            }
        }
        return items;
    }

    /** content + metadata 的簡單配對，供 Controller 逐筆呼叫 ingestText(...) 用。 */
    public record TextKnowledge(String content, Map<String, Object> metadata) {}
}