package com.gtalent.redis.dessert.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import com.gtalent.redis.dessert.service.DuplicateNameException;
import com.gtalent.redis.dessert.service.InsufficientStockException;
import com.gtalent.redis.dessert.service.ReadOnlyFieldException; // 新增：name 欄位在建立後唯讀，PUT 更改時拋出
import com.gtalent.redis.dessert.model.Dessert;
import com.gtalent.redis.dessert.repository.DessertRepository;
import com.gtalent.redis.dessert.service.DessertService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DessertServiceImpl implements DessertService {

    private static final String CACHE_KEY_PREFIX = "dessert:item:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final DessertRepository dessertRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper objectMapper;

    @Override
    public Dessert create(Dessert dessert) {
        // 新增時 id 一律由資料庫自增產生，避免前端誤傳 id 造成覆蓋既有資料
        dessert.setId(null);

        // 軟刪除新增：新增的資料一律強制設為「未刪除」，
        // 防止前端傳入 deleted=true 的異常資料，或未來欄位被誤用
        dessert.setDeleted(false);

        // 軟刪除修改：原本用 existsByName()，改成只檢查「未被刪除」的資料，
        // 避免舊的、已軟刪除的同名品項擋住新品項的新增
        if (dessertRepository.existsByNameAndDeletedFalse(dessert.getName())) {
            throw new DuplicateNameException("「" + dessert.getName() + "」已存在，不可新增重複名稱的品項");
        }

        return dessertRepository.save(dessert);
    }

    @Override
    public List<Dessert> findAll() {
        // 軟刪除修改：改用 findByDeletedFalse()，
        // 讓已經被「刪除」的甜點不會出現在清單 API 的回傳結果裡
        return dessertRepository.findByDeletedFalse();
    }

    @Override
    public Dessert getById(Long id) {
        String cacheKey = buildCacheKey(id);

        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            Dessert cached = deserialize(cachedJson);
            if (cached != null) {
                log.debug("Cache hit: {}", cacheKey);
                return cached;
            }
        }

        log.debug("Cache miss: {}, querying MySQL", cacheKey);

        // 軟刪除修改：改用 findByIdAndDeletedFalse()，
        // 就算 Redis 沒快取到、直接查資料庫，遇到已刪除的品項一樣視為「找不到」
        Dessert dessert = dessertRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到 id=" + id + " 的甜點品項"));

        cacheDessert(cacheKey, dessert);

        return dessert;
    }

    @Override
    public Dessert update(Long id, Dessert dessert) {
        // 軟刪除修改：改用 findByIdAndDeletedFalse()，
        // 已經被軟刪除的品項不允許再被修改（視同不存在）
        Dessert existing = dessertRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到 id=" + id + " 的甜點品項"));

        // name 欄位設計為「建立後唯讀」：只要 PUT 傳入的 name 跟資料庫現有值不同，
        // 就直接擋下這次更新，避免品項名稱被隨意更改造成資料混亂
        //（例如歷史訂單快照的名稱跟現在的甜點對不上）。
        // 注意：這裡刻意放在「補貨判斷」之前，確保就算是補貨情境也一樣受限。
        if (dessert.getName() != null && !dessert.getName().equals(existing.getName())) {
            throw new ReadOnlyFieldException(
                    "甜點名稱建立後不可修改，目前名稱為「" + existing.getName() + "」，不可改為「" + dessert.getName() + "」");
        }

        // 判斷這次更新是不是「補貨」：原本庫存是 0（賣完/沒庫存），
        // 這次更新後的庫存變成正數，就視為補貨動作
        boolean wasSoldOut = existing.getStock() != null && existing.getStock() <= 0;
        boolean isRestocking = wasSoldOut && dessert.getStock() != null && dessert.getStock() > 0;

        existing.setPrice(dessert.getPrice());
        existing.setStock(dessert.getStock());

        if (isRestocking) {
            // 補貨自動恢復上架：不論前端這次傳進來的 enabled 是 true 還是 false，
            // 只要是從「庫存 0」補回「庫存 > 0」，一律強制改成 true，
            // 跟 deductStock 裡「庫存歸零自動下架」的規則對稱
            existing.setEnabled(true);
        } else {
            // 非補貨情境（例如純粹改名字、改價格、或庫存本來就 > 0），
            // enabled 還是照前端傳的值走，讓使用者可以手動上下架
            existing.setEnabled(dessert.getEnabled());
        }

        Dessert updated = dessertRepository.save(existing);

        // 更新後，刪除舊快取，讓下次查詢重新回寫最新資料
        evictCache(id);

        return updated;
    }

    @Override
    public void delete(Long id) {
        // 軟刪除修改：原本是 dessertRepository.deleteById(id)（實體刪除），
        // 因為 order_item 有外鍵參照 dessert.id，實體刪除會導致
        // 「Cannot delete or update a parent row: a foreign key constraint fails」錯誤。
        //
        // 改成：先查出這筆資料（順便確認存不存在、有沒有被刪過），
        // 把 deleted 設成 true 後存回去，資料實際上還在資料庫裡，
        // 只是之後所有查詢（findByDeletedFalse / findByIdAndDeletedFalse）都會跳過它。
        Dessert dessert = dessertRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到 id=" + id + " 的甜點品項"));

        dessert.setDeleted(true);
        dessertRepository.save(dessert);

        evictCache(id);
    }

    @Override
    public void deleteAll() {
        // 軟刪除修改：原本是 dessertRepository.deleteAll() + resetAutoIncrement()（實體刪除全部）。
        //
        // 改成：撈出「目前還沒被刪除」的資料，逐筆把 deleted 設為 true 後批次存回去。
        // 這樣一來：
        // 1. 不會再觸發 order_item 的外鍵約束錯誤
        // 2. 舊訂單仍然可以正常查到當初訂購的甜點名稱、價格（因為資料實際還在）
        List<Dessert> desserts = dessertRepository.findByDeletedFalse();
        desserts.forEach(d -> d.setDeleted(true));
        dessertRepository.saveAll(desserts);

        // 軟刪除修改：不再呼叫 resetAutoIncrement()。
        // 因為資料列並沒有真的從表裡消失，continue 用原本的自增序號即可，
        // 重置自增反而可能導致「新資料的 id」跟「舊的、已軟刪除資料的 id」衝突或混淆。
        // dessertRepository.resetAutoIncrement();

        Set<String> keys = stringRedisTemplate.keys(CACHE_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.debug("刪除全部甜點快取，共 {} 筆", keys.size());
        }
    }

    @Override
    public void deductStock(Long id, Integer quantity) {
        // 用一條原子 UPDATE 同時完成「檢查庫存 + 扣減」，
        // 交給資料庫的 row lock 保證併發安全，不會有「兩人同時讀到有庫存、一起扣成負數」的問題
        //
        // 註：dessertRepository.deductStock() 內部的 SQL 已經加上 d.deleted = false 條件，
        // 所以已軟刪除的品項不會被扣到庫存，這裡的方法本體不需要額外修改
        int updatedRows = dessertRepository.deductStock(id, quantity);

        if (updatedRows == 0) {
            // 沒扣到，先分清楚是「品項根本不存在／已被刪除」還是「庫存不夠」，訊息才會準確
            Dessert dessert = dessertRepository.findByIdAndDeletedFalse(id)
                    .orElseThrow(() -> new EntityNotFoundException("找不到 id=" + id + " 的甜點品項"));

            throw new InsufficientStockException(
                    "「" + dessert.getName() + "」庫存不足，目前剩餘 " + dessert.getStock() + " 份，已無法下單");
        }

        // 扣庫存成功後，Redis 裡的舊庫存數字就過期了，清掉快取，下次查詢會回寫最新值
        evictCache(id);
        log.debug("扣庫存成功: dessertId={}, quantity={}", id, quantity);
    }

    private String buildCacheKey(Long id) {
        return CACHE_KEY_PREFIX + id;
    }

    private void cacheDessert(String cacheKey, Dessert dessert) {
        try {
            String json = objectMapper.writeValueAsString(dessert);
            stringRedisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
        } catch (JacksonException e) {
            log.warn("寫入 Redis 快取失敗，key={}", cacheKey, e);
        }
    }

    private Dessert deserialize(String json) {
        try {
            return objectMapper.readValue(json, Dessert.class);
        } catch (JacksonException e) {
            log.warn("Redis 快取內容反序列化失敗，將重新查詢資料庫", e);
            return null;
        }
    }

    private void evictCache(Long id) {
        String cacheKey = buildCacheKey(id);
        Boolean deleted = stringRedisTemplate.delete(cacheKey);
        log.debug("刪除快取 key={}, 結果={}", cacheKey, deleted);
    }

}