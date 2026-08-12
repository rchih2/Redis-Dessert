package com.gtalent.redis.dessert.controller;

import org.springframework.security.core.Authentication;
import com.gtalent.redis.dessert.dto.OrderCreateDTO;
import com.gtalent.redis.dessert.dto.OrderResponseDTO;
import com.gtalent.redis.dessert.model.Dessert;
import com.gtalent.redis.dessert.repository.OrderRepository;
import com.gtalent.redis.dessert.service.DessertService;
import com.gtalent.redis.dessert.service.OrderService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.gtalent.redis.dessert.service.DessertCsvImportService;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;

import java.io.IOException;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DessertOrderController {

    private final DessertService dessertService;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final DessertCsvImportService dessertCsvImportService;

    // ------------------------------------------------------------------
    // 1. 新增甜點
    // ------------------------------------------------------------------
    @PostMapping("/desserts")
    public ResponseEntity<Dessert> createDessert(@Valid @RequestBody Dessert dessert) {
        Dessert created = dessertService.create(dessert);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ------------------------------------------------------------------
    // 2. 查詢全部甜點清單（直接查 MySQL，不經過 Redis 單筆快取）
    // ------------------------------------------------------------------
    @GetMapping("/desserts")
    public ResponseEntity<List<Dessert>> getAllDesserts() {
        List<Dessert> desserts = dessertService.findAll();
        return ResponseEntity.ok(desserts);
    }

    // ------------------------------------------------------------------
    // 3. 查詢單一甜點（走 Redis 快取）
    // ------------------------------------------------------------------
    @GetMapping("/desserts/{id}")
    public ResponseEntity<Dessert> getDessert(@PathVariable Long id) {
        Dessert dessert = dessertService.getById(id);
        return ResponseEntity.ok(dessert);
    }

    // ------------------------------------------------------------------
    // 4. 修改甜點資訊（會清除快取）
    // ------------------------------------------------------------------
    @PutMapping("/desserts/{id}")
    public ResponseEntity<Dessert> updateDessert(@PathVariable Long id, @Valid @RequestBody Dessert dessert) {
        Dessert updated = dessertService.update(id, dessert);
        return ResponseEntity.ok(updated);
    }

    // ------------------------------------------------------------------
    // 5. 刪除單一甜點（會清除對應快取）
    // ------------------------------------------------------------------
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")   // ← 改成這個，STAFF 也能刪單一甜點
    @DeleteMapping("/desserts/{id}")
    public ResponseEntity<Void> deleteDessert(@PathVariable Long id) {
        dessertService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // 6. 刪除全部甜點（會清除全部相關快取）
    // ------------------------------------------------------------------
    // ------------------------------------------------------------------
    // 6. 刪除全部甜點（會清除全部相關快取）
    // ------------------------------------------------------------------
    @PreAuthorize("hasRole('ADMIN')")   // ← 這個維持不變，破壞性較大只給 ADMIN
    @DeleteMapping("/desserts")
    public ResponseEntity<Void> deleteAllDesserts() {
        dessertService.deleteAll();
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // 7. 查詢全部訂單(含品項明細)
    // ------------------------------------------------------------------
    @GetMapping("/orders")
    public ResponseEntity<List<OrderResponseDTO>> getAllOrders() {
        List<OrderResponseDTO> orders = orderService.findAll();
        return ResponseEntity.ok(orders);
    }
    // ------------------------------------------------------------------
    // 7a. 查詢「我自己」的訂單清單（依登入者 username 過濾，不需要 ADMIN/STAFF 權限）
    //    對應 SecurityConfig 的保底規則 anyRequest().authenticated()，任一已登入角色皆可呼叫，
    //    但只會看到自己下單時登入帳號建立的訂單，不會查到別人的資料。
    // ------------------------------------------------------------------
    @GetMapping("/orders/my")
    public ResponseEntity<List<OrderResponseDTO>> getMyOrders(Authentication authentication) {
        List<OrderResponseDTO> orders = orderService.findMyOrders(authentication.getName());
        return ResponseEntity.ok(orders);
    }
    // ------------------------------------------------------------------
    // 8. 查詢單一訂單(含品項明細)
    // ------------------------------------------------------------------
    @GetMapping("/orders/{id}")
    public ResponseEntity<OrderResponseDTO> getOrder(@PathVariable Long id) {
        OrderResponseDTO order = orderService.getById(id);
        return ResponseEntity.ok(order);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------
    // 9. 刪除單一訂單（改為軟刪除...）
    // ------------------------------------------------------------------
    @PreAuthorize("hasRole('ADMIN')")   // ← 補上這一行，STAFF 不能刪訂單
    @DeleteMapping("/orders/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        orderService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // 10. 刪除全部訂單（改為軟刪除：整批標記 deleted = true，
    //    資料實際上仍留在資料庫，因此不再需要、也不應該重置自增計數器）
    // ------------------------------------------------------------------
    @PreAuthorize("hasRole('ADMIN')")   // ← 補上這一行
    @DeleteMapping("/orders")
    public ResponseEntity<Void> deleteAllOrders() {
        orderService.softDeleteAll();
        return ResponseEntity.noContent().build();
    }
    // ------------------------------------------------------------------
    // 10a. 【管理用】真正刪除單一甜點（實體刪除，繞過軟刪除機制）
    //
    // 用途：軟刪除只是把 deleted 標記為 true，資料仍留在 MySQL；
    // 這支才是真的把資料列從 dessert 表刪掉，用於清理測試資料或徹底移除錯誤資料。
    //
    // ⚠️ 正式環境務必加上管理員權限檢查（例如 @PreAuthorize("hasRole('ADMIN')")），
    // 否則任何人都能繞過軟刪除機制、真的把甜點資料刪光，且不可復原。
    //
    // ⚠️ OrderItem.dessertId 只是快照存的 Long，沒有 JPA 外鍵約束，
    // 因此刪除甜點不會連動刪除歷史訂單明細，但反查甜點原始資訊（分類、標籤等）的能力會遺失。
    //
    // ⚠️ 已修正（技術文件第 9 節「建議後續工作」第 6 項）：原本這裡直接呼叫
    // dessertRepository.deleteById()，不會經過 DessertService 內部清除 Redis 快取
    // 與 Elasticsearch 索引的邏輯，導致實體刪除後短時間內 GET /api/desserts/{id}
    // 仍可能查到已被刪除的舊資料（直到 Redis TTL 10 分鐘到期）。
    // 現在改成呼叫 DessertService.purge(id)，由 Service 層統一處理
    // 「刪資料列 + 清 Redis 快取 + 移除 Elasticsearch 索引」這三件事。
    // ------------------------------------------------------------------
    @PreAuthorize("hasRole('ADMIN')")   // ← 補上這一行
    @DeleteMapping("/admin/desserts/{id}/purge")
    public ResponseEntity<Void> purgeDessert(@PathVariable Long id) {
        dessertService.purge(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // 10b. 【管理用】真正刪除單一訂單（實體刪除，繞過軟刪除機制）
    //
    // 用途同上。因為 Order.items 設定 cascade = ALL、orphanRemoval = true，
    // deleteById() 會自動一併刪除對應的 OrderItem，不需要另外處理明細。
    //
    // ⚠️ 正式環境務必加上管理員權限檢查，且刪除後歷史訂單將無法復原、無法對單。
    // ------------------------------------------------------------------
    @PreAuthorize("hasRole('ADMIN')")   // ← 補上這一行
    @DeleteMapping("/admin/orders/{id}/purge")
    public ResponseEntity<Void> purgeOrder(@PathVariable Long id) {
        if (!orderRepository.existsById(id)) {
            throw new EntityNotFoundException("找不到 id=" + id + " 的訂單");
        }
        orderRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
    // ------------------------------------------------------------------
    // 10c. 【管理用】批次實體刪除甜點（寬鬆模式：找不到的 id 會被跳過並記錄失敗原因，
    //    其餘照常刪除，不會因為單一筆對不上就整批拒絕）
    //
    // 可選參數 resetSequence（預設 false）：true 時會在刪除完成後把 dessert 表的
    // AUTO_INCREMENT 重置為 1，讓下一筆新增甜點的 id 從 1 開始。
    // ⚠️ 僅建議在確定沒有任何歷史訂單引用這批 id 時使用（見 DessertService#purgeAll javadoc）。
    // ------------------------------------------------------------------
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/admin/desserts/purge")
    public ResponseEntity<Map<String, Object>> purgeAllDesserts(
            @RequestBody List<Long> ids,
            @RequestParam(defaultValue = "false") boolean resetSequence) {
        Map<String, Object> result = dessertService.purgeAll(ids, resetSequence);
        return ResponseEntity.ok(result);
    }

    // ------------------------------------------------------------------
    // 11. 建立訂單：第一層(@Valid) + 第二層(金額覆核)驗證 + 第三層(扣庫存)
    //    實際邏輯（金額計算、扣庫存、建立訂單、事件發布、業務指標）都在 OrderService，
    //    Controller 只負責接收/驗證 request、呼叫 Service、組裝 response。
    // ------------------------------------------------------------------
    @PostMapping("/orders")
    public ResponseEntity<OrderResponseDTO> createOrder(@Valid @RequestBody OrderCreateDTO orderCreateDTO) {
        OrderResponseDTO responseBody = orderService.createOrder(orderCreateDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(responseBody);
    }

    // ------------------------------------------------------------------
    // 12～14. 例外處理（InsufficientStockException／DuplicateNameException／
    //    EntityNotFoundException／ReadOnlyFieldException／驗證失敗等）
    //    已統一搬到全域的 com.gtalent.redis.dessert.exception.GlobalExceptionHandler
    //    （@RestControllerAdvice），不再各別寫在這個 Controller 裡，
    //    對應技術文件第 9 節「建議後續工作」第 1 項。
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 15. 管理用：上傳 CSV 一次匯入整份甜點菜單
    // CSV 欄位：name,price,stock,enabled（enabled 可省略，預設 true）
    // 某一列失敗不影響其他列，回應會列出成功筆數與失敗列表
    // ------------------------------------------------------------------
    @PostMapping("/admin/desserts/csv")
    public ResponseEntity<Map<String, Object>> importDessertsCsv(@RequestParam("file") MultipartFile file) throws IOException {
        Map<String, Object> result = dessertCsvImportService.importCsv(file.getInputStream());
        return ResponseEntity.ok(result);
    }


}