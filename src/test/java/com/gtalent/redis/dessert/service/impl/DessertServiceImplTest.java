package com.gtalent.redis.dessert.service.impl;

import com.gtalent.redis.dessert.model.Dessert;
import com.gtalent.redis.dessert.repository.DessertRepository;
import com.gtalent.redis.dessert.metrics.BusinessMetrics;
import com.gtalent.redis.dessert.search.DessertSearchIndexService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DessertServiceImpl} 的單元測試，聚焦於 Redis cache-aside 讀取／回寫／清除的行為。
 *
 * <p>全部使用 Mockito mock 掉 {@link StringRedisTemplate}、{@link JsonMapper}
 * 與 {@link DessertRepository}，不需要真的連線 Redis 或 MySQL，
 * 確保能在 CI 環境無外部依賴的情況下執行。</p>
 */
@ExtendWith(MockitoExtension.class)
class DessertServiceImplTest {

    private static final String CACHE_KEY_PREFIX = "dessert:item:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    @Mock
    private DessertRepository dessertRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private JsonMapper objectMapper;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private DessertSearchIndexService dessertSearchIndexService;

    @Mock
    private BusinessMetrics businessMetrics;

    @InjectMocks
    private DessertServiceImpl dessertService;

    /**
     * cache hit：Redis 裡已經有快取的 JSON，應直接反序列化回傳，
     * 完全不需要查詢 MySQL（不會呼叫 dessertRepository.findByIdAndDeletedFalse）。
     */
    @Test
    @DisplayName("查詢單一甜點時，命中 Redis 快取應直接回傳，不查詢 MySQL")
    void getById_shouldReturnFromCache_whenCacheHit() {
        // given
        Long id = 1L;
        String cacheKey = CACHE_KEY_PREFIX + id;
        String cachedJson = "{\"id\":1,\"name\":\"布丁\",\"price\":50}";
        Dessert cachedDessert = buildDessert(id, "布丁", "50");

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(cacheKey)).thenReturn(cachedJson);
        when(objectMapper.readValue(cachedJson, Dessert.class)).thenReturn(cachedDessert);

        // when
        Dessert result = dessertService.getById(id);

        // then
        assertThat(result).isEqualTo(cachedDessert);
        verify(dessertRepository, never()).findByIdAndDeletedFalse(any());
    }

    /**
     * cache miss：Redis 沒有快取，應查詢 MySQL，並把查到的結果依 TTL 回寫進 Redis。
     */
    @Test
    @DisplayName("查詢單一甜點時，未命中 Redis 快取應查詢 MySQL 並回寫快取")
    void getById_shouldQueryMySqlAndWriteCache_whenCacheMiss() {
        // given
        Long id = 1L;
        String cacheKey = CACHE_KEY_PREFIX + id;
        Dessert dessert = buildDessert(id, "布丁", "50");
        String serializedJson = "{\"id\":1,\"name\":\"布丁\",\"price\":50}";

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(cacheKey)).thenReturn(null);
        when(dessertRepository.findByIdAndDeletedFalse(id)).thenReturn(Optional.of(dessert));
        when(objectMapper.writeValueAsString(dessert)).thenReturn(serializedJson);

        // when
        Dessert result = dessertService.getById(id);

        // then
        assertThat(result).isEqualTo(dessert);
        verify(dessertRepository, times(1)).findByIdAndDeletedFalse(id);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations, times(1)).set(eq(cacheKey), eq(serializedJson), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(CACHE_TTL);
    }

    /**
     * update() 成功後應清除該品項的 Redis 快取，確保下次查詢不會讀到舊資料。
     */
    @Test
    @DisplayName("更新甜點成功後應清除對應的 Redis 快取")
    void update_shouldEvictCache_whenSuccess() {
        // given
        Long id = 1L;
        Dessert existing = buildDessert(id, "布丁", "50");
        existing.setStock(5);

        Dessert updateRequest = buildDessert(id, "布丁", "60");
        updateRequest.setStock(8);
        updateRequest.setEnabled(true);

        when(dessertRepository.findByIdAndDeletedFalse(id)).thenReturn(Optional.of(existing));
        when(dessertRepository.save(any(Dessert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        dessertService.update(id, updateRequest);

        // then
        verify(stringRedisTemplate, times(1)).delete(CACHE_KEY_PREFIX + id);
    }

    /**
     * delete()（軟刪除）成功後應清除該品項的 Redis 快取。
     */
    @Test
    @DisplayName("刪除甜點成功後應清除對應的 Redis 快取")
    void delete_shouldEvictCache_whenSuccess() {
        // given
        Long id = 1L;
        Dessert existing = buildDessert(id, "布丁", "50");

        when(dessertRepository.findByIdAndDeletedFalse(id)).thenReturn(Optional.of(existing));
        when(dessertRepository.save(any(Dessert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        dessertService.delete(id);

        // then
        assertThat(existing.getDeleted()).isTrue();
        verify(stringRedisTemplate, times(1)).delete(CACHE_KEY_PREFIX + id);
    }

    /**
     * deductStock() 扣庫存成功後應清除該品項的 Redis 快取，
     * 因為 Redis 裡快取的舊庫存數字在扣減後已經過期。
     */
    @Test
    @DisplayName("扣庫存成功後應清除對應的 Redis 快取")
    void deductStock_shouldEvictCache_whenSuccess() {
        // given
        Long id = 1L;
        Integer quantity = 2;

        when(dessertRepository.deductStock(id, quantity)).thenReturn(1);

        // when
        dessertService.deductStock(id, quantity);

        // then
        verify(stringRedisTemplate, times(1)).delete(CACHE_KEY_PREFIX + id);
    }

    /**
     * purge()（管理用實體刪除）成功後，除了呼叫 Repository 刪除資料列，
     * 也應該同步清除 Redis 快取並從 Elasticsearch 索引移除，
     * 對應技術文件第 9 節「建議後續工作」第 6 項的修正。
     */
    @Test
    @DisplayName("實體清除成功後應同步清除 Redis 快取與 Elasticsearch 索引")
    void purge_shouldDeleteEvictCacheAndRemoveFromSearchIndex_whenSuccess() {
        // given
        Long id = 1L;
        when(dessertRepository.existsById(id)).thenReturn(true);

        // when
        dessertService.purge(id);

        // then
        verify(dessertRepository, times(1)).deleteById(id);
        verify(stringRedisTemplate, times(1)).delete(CACHE_KEY_PREFIX + id);
        verify(dessertSearchIndexService, times(1)).remove(id);
    }

    /**
     * purge() 找不到對應 id 的資料時，應直接拋出 EntityNotFoundException，
     * 且不應該呼叫 deleteById()、清快取或動到搜尋索引。
     */
    @Test
    @DisplayName("實體清除時找不到資料應拋出例外且不做任何刪除動作")
    void purge_shouldThrowEntityNotFoundException_whenDessertNotExists() {
        // given
        Long id = 999L;
        when(dessertRepository.existsById(id)).thenReturn(false);

        // when / then
        org.junit.jupiter.api.Assertions.assertThrows(
                jakarta.persistence.EntityNotFoundException.class,
                () -> dessertService.purge(id));

        verify(dessertRepository, never()).deleteById(any());
        verify(dessertSearchIndexService, never()).remove(any());
    }

    private Dessert buildDessert(Long id, String name, String price) {
        Dessert dessert = new Dessert();
        dessert.setId(id);
        dessert.setName(name);
        dessert.setPrice(new BigDecimal(price));
        dessert.setStock(10);
        dessert.setEnabled(true);
        dessert.setDeleted(false);
        return dessert;
    }
}