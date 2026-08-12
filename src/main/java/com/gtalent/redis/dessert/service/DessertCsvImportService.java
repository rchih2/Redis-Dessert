package com.gtalent.redis.dessert.service;

import com.gtalent.redis.dessert.model.Dessert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 甜點菜單 CSV 匯入服務，供 {@code POST /api/admin/desserts/csv} 使用。
 *
 * <p>跟 AI 知識庫的 CSV 匯入（{@code CsvKnowledgeUploadController}）目的完全不同：
 * 這裡匯入的是「甜點主檔資料」本身（寫進 MySQL dessert 表），
 * 那邊匯入的是給 AI 顧問參考用的知識文字（寫進向量資料庫）。</p>
 *
 * <p>逐列呼叫既有的 {@link DessertService#create(Dessert)}，沿用單筆新增甜點的
 * 全部商業邏輯（id 清空、deleted 強制 false、重複名稱檢查、Redis 快取、
 * Elasticsearch 索引同步），不另外寫一套繞過既有驗證的批次寫入邏輯。</p>
 *
 * <p>CSV 欄位（標頭）：{@code name,price,stock,enabled}，{@code enabled} 可省略，
 * 省略時預設為 {@code true}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DessertCsvImportService {

    private static final List<String> REQUIRED_HEADERS = List.of("name", "price", "stock");

    private final DessertService dessertService;

    public Map<String, Object> importCsv(InputStream csvInputStream) throws IOException {
        int importedCount = 0;
        List<Map<String, Object>> errors = new ArrayList<>();

        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreSurroundingSpaces(true)
                .build()
                .parse(new InputStreamReader(csvInputStream, StandardCharsets.UTF_8))) {

            // 先確認必要欄位都存在，避免每一列都重複拋出同一個「欄位不存在」的錯誤
            for (String required : REQUIRED_HEADERS) {
                if (!parser.getHeaderNames().contains(required)) {
                    throw new IllegalArgumentException(
                            "CSV 缺少必要欄位「" + required + "」，標頭必須包含 name,price,stock（enabled 可省略）");
                }
            }

            for (CSVRecord record : parser) {
                // CSV 資料列編號（不含標頭），方便對照使用者手上的 CSV 檔案是第幾列
                long rowNumber = record.getRecordNumber();
                String name = record.get("name");

                try {
                    Dessert dessert = new Dessert();
                    dessert.setName(name);
                    dessert.setPrice(new BigDecimal(record.get("price")));
                    dessert.setStock(Integer.parseInt(record.get("stock")));

                    boolean hasEnabledValue = record.isMapped("enabled")
                            && record.get("enabled") != null
                            && !record.get("enabled").isBlank();
                    dessert.setEnabled(hasEnabledValue ? Boolean.parseBoolean(record.get("enabled")) : true);

                    dessertService.create(dessert);
                    importedCount++;
                } catch (Exception e) {
                    // 單列失敗（例如名稱重複、price/stock 格式錯誤）不影響其他列，
                    // 記錄下來讓使用者知道哪一列、為什麼失敗
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("row", rowNumber);
                    error.put("name", name);
                    error.put("reason", e.getMessage());
                    errors.add(error);
                    log.warn("[DessertCsvImportService] 第 {} 列匯入失敗，name={}", rowNumber, name, e);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", errors.isEmpty());
        result.put("importedCount", importedCount);
        result.put("failedCount", errors.size());
        result.put("errors", errors);
        return result;
    }
}