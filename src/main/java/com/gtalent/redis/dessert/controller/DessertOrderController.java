package com.gtalent.redis.dessert.controller;

import com.gtalent.redis.dessert.dto.OrderCreateDTO;
import com.gtalent.redis.dessert.dto.OrderItemCreateDTO;
import com.gtalent.redis.dessert.dto.OrderItemResponseDTO;
import com.gtalent.redis.dessert.dto.OrderResponseDTO;
import com.gtalent.redis.dessert.model.Dessert;
import com.gtalent.redis.dessert.model.Order;
import com.gtalent.redis.dessert.model.OrderItem;
import com.gtalent.redis.dessert.repository.DessertRepository;
import com.gtalent.redis.dessert.repository.OrderRepository;
import com.gtalent.redis.dessert.service.DessertService;
import com.gtalent.redis.dessert.service.DuplicateNameException;
import com.gtalent.redis.dessert.service.InsufficientStockException; // 新增：庫存不足例外
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional; // 新增：保證下單過程的交易一致性
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DessertOrderController {

    /** 滿此金額（不含運費）即免運 */
    private static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("2000");
    /** 未滿門檻時的運費 */
    private static final BigDecimal SHIPPING_FEE = new BigDecimal("60");

    private final DessertService dessertService;
    private final DessertRepository dessertRepository;
    private final OrderRepository orderRepository;

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
    @DeleteMapping("/desserts/{id}")
    public ResponseEntity<Void> deleteDessert(@PathVariable Long id) {
        dessertService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // 6. 刪除全部甜點（會清除全部相關快取）
    // ------------------------------------------------------------------
    @DeleteMapping("/desserts")
    public ResponseEntity<Void> deleteAllDesserts() {
        dessertService.deleteAll();
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
// 7. 查詢全部訂單(含品項明細)
// ------------------------------------------------------------------
    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public ResponseEntity<List<OrderResponseDTO>> getAllOrders() {
        // 改用 findByDeletedFalse()：已軟刪除的訂單不應出現在查詢結果中
        List<OrderResponseDTO> orders = orderRepository.findByDeletedFalse().stream()
                .map(this::toResponseDTO)
                .toList();
        return ResponseEntity.ok(orders);
    }

    // ------------------------------------------------------------------
// 8. 查詢單一訂單(含品項明細)
// ------------------------------------------------------------------
    @GetMapping("/orders/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<OrderResponseDTO> getOrder(@PathVariable Long id) {
        // 改用 findByIdAndDeletedFalse()：id 對應到已軟刪除的訂單時，視同「找不到」
        Order order = orderRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到 id=" + id + " 的訂單"));
        return ResponseEntity.ok(toResponseDTO(order));
    }

    // ------------------------------------------------------------------
// 私有轉換方法：Order Entity → OrderResponseDTO
// 補上原因：避免直接序列化 JPA Entity(依賴 open-in-view、格式跟 createOrder 不一致)，
// 改成在明確的交易邊界內把 LAZY 的 items 轉成 DTO 再回傳。
// ------------------------------------------------------------------
    private OrderResponseDTO toResponseDTO(Order order) {
        List<OrderItemResponseDTO> items = order.getItems().stream()
                .map(i -> new OrderItemResponseDTO(
                        i.getDessertId(),
                        i.getDessertName(),
                        i.getUnitPrice(),
                        i.getQuantity(),
                        i.getLineTotal()))
                .toList();

        return new OrderResponseDTO(
                order.getId(),
                order.getCustomerName(),
                order.getPhone(),
                order.getLineId(),
                order.getTotalAmount(),
                order.getOrderTime(),
                items);
    }
    // ------------------------------------------------------------------
    // 9. 刪除單一訂單（改為軟刪除：只標記 deleted = true，資料與訂單明細仍留在資料庫，
    //    方便未來客戶對單、財務對帳或糾紛時可追溯歷史紀錄）
    // ------------------------------------------------------------------
    @DeleteMapping("/orders/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        int updated = orderRepository.softDeleteById(id);
        if (updated == 0) {
            throw new EntityNotFoundException("找不到 id=" + id + " 的訂單");
        }
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // 10. 刪除全部訂單（改為軟刪除：整批標記 deleted = true，
    //    資料實際上仍留在資料庫，因此不再需要、也不應該重置自增計數器）
    // ------------------------------------------------------------------
    @DeleteMapping("/orders")
    public ResponseEntity<Void> deleteAllOrders() {
        orderRepository.softDeleteAll();
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
    // ⚠️ 這裡直接呼叫 Repository，不會經過 DessertService.delete() 內部清除 Redis 快取的邏輯，
    // 若該甜點的 Redis 快取（key: dessert:item:{id}）還沒過期，GET /api/desserts/{id}
    // 短時間內可能還會查到「已經被刪除」的舊資料，直到 TTL（10 分鐘）到期。
    // 若要避免這個情況，建議之後把清快取的邏輯也搬進來，或改成呼叫 DessertService 提供的方法。
    // ------------------------------------------------------------------
    @DeleteMapping("/admin/desserts/{id}/purge")
    public ResponseEntity<Void> purgeDessert(@PathVariable Long id) {
        if (!dessertRepository.existsById(id)) {
            throw new EntityNotFoundException("找不到 id=" + id + " 的甜點");
        }
        dessertRepository.deleteById(id);
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
    @DeleteMapping("/admin/orders/{id}/purge")
    public ResponseEntity<Void> purgeOrder(@PathVariable Long id) {
        if (!orderRepository.existsById(id)) {
            throw new EntityNotFoundException("找不到 id=" + id + " 的訂單");
        }
        orderRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // 11. 建立訂單：第一層(@Valid) + 第二層(金額覆核)驗證 + 第三層(扣庫存)
    //
    // 修改重點：
    // 1) 加上 @Transactional：讓整個下單流程（扣庫存 + 建立訂單）在同一個交易內。
    //    只要其中之一庫存不足拋出例外，前面已經扣成功品項也會一併回滾，
    //    不會發生「這張訂單只扣一半庫存」的情況。
    // 2) 在迴圈中呼叫 dessertService.deductStock(...)：
    //    改用資料庫層的原子 UPDATE（WHERE stock >= quantity）真的扣掉庫存，
    //    而不是只讀取單價、卻從來沒有扣減庫存數字。
    // ------------------------------------------------------------------
    @Transactional
    @PostMapping("/orders")
    public ResponseEntity<OrderResponseDTO> createOrder(@Valid @RequestBody OrderCreateDTO orderCreateDTO) {

        // ---- 第二層驗證：逐一撈出品項單價，重新計算金額明細，同時組出要存進資料庫的 OrderItem ----
        BigDecimal subtotal = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();

        for (OrderItemCreateDTO item : orderCreateDTO.getItems()) {
            Dessert dessert = dessertService.getById(item.getDessertId());

            // ---- 第三層：扣庫存 ----
            // 這一行是這次補上的關鍵：之前完全沒有扣庫存的邏輯，
            // 導致下單無限次都不會影響庫存、賣完也擋不住。
            // deductStock 內部用「UPDATE ... WHERE stock >= quantity」的原子操作，
            // 如果庫存不夠，會直接拋出 InsufficientStockException，中斷這次下單。
            dessertService.deductStock(item.getDessertId(), item.getQuantity());

            BigDecimal lineTotal = dessert.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(lineTotal);

            // 把名稱、單價「快照」下來，保留下單當下的資料，不受之後甜點改價/改名影響
            OrderItem orderItem = new OrderItem();
            orderItem.setDessertId(dessert.getId());
            orderItem.setDessertName(dessert.getName());
            orderItem.setUnitPrice(dessert.getPrice());
            orderItem.setQuantity(item.getQuantity());
            orderItem.setLineTotal(lineTotal);
            orderItems.add(orderItem);
        }

        // ---- 運費規則：未滿 2000 元加收 60 元運費，滿 2000 元免運 ----
        BigDecimal shippingFee = subtotal.compareTo(FREE_SHIPPING_THRESHOLD) >= 0
                ? BigDecimal.ZERO
                : SHIPPING_FEE;

        BigDecimal calculatedTotal = subtotal.add(shippingFee);

        // ---- 金額完全由後端計算，不再跟前端比對，寫入訂單（cascade = ALL 會自動把 OrderItem 一併存進去）----
        Order order = new Order();
        order.setCustomerName(orderCreateDTO.getCustomerName());
        order.setPhone(orderCreateDTO.getPhone());
        order.setLineId(orderCreateDTO.getLineId());
        order.setTotalAmount(calculatedTotal);
        order.setOrderTime(LocalDateTime.now());

        for (OrderItem orderItem : orderItems) {
            order.addItem(orderItem);
        }

        Order savedOrder = orderRepository.save(order);

        // ------------------------------------------------------------------
        // 改動說明：原本這裡是手動組裝 Map<String, Object> 回應，跟 GET /api/orders*
        // 用的 OrderResponseDTO 格式不一致（例如 key 是 "orderId" 而不是 "id"，
        // 品項的 key 是 "name" 而不是 "dessertName"）。
        // 現在改成統一組出 OrderResponseDTO，讓 POST / GET 回傳同一種訂單格式，
        // 前端不用再依端點分別解析兩種不同的 JSON 結構。
        //
        // 注意：這是有意的 breaking change——欄位名稱從 orderId → id、
        // items 內的 name → dessertName，呼叫端如果原本依賴舊的 key 名稱需要同步調整。
        // ------------------------------------------------------------------
        List<OrderItemResponseDTO> responseItems = orderItems.stream()
                .map(oi -> new OrderItemResponseDTO(
                        oi.getDessertId(),
                        oi.getDessertName(),
                        oi.getUnitPrice(),
                        oi.getQuantity(),
                        oi.getLineTotal()))
                .toList();

        OrderResponseDTO responseBody = new OrderResponseDTO(
                true,
                "下單成功",
                savedOrder.getId(),
                savedOrder.getCustomerName(),
                savedOrder.getPhone(),
                savedOrder.getLineId(),
                subtotal,
                shippingFee,
                savedOrder.getTotalAmount(),
                savedOrder.getOrderTime(),
                responseItems);

        return ResponseEntity.status(HttpStatus.CREATED).body(responseBody);
    }

    // ------------------------------------------------------------------
    // 12. 例外處理：下單時庫存不足（已售完）
    //
    // 新增原因：deductStock 一旦發現庫存不夠會拋出 InsufficientStockException，
    // 這裡攔截它，轉換成 409 Conflict + 清楚的錯誤訊息回傳給前端，
    // 而不是讓它變成一個沒有處理過的 500 錯誤。
    // ------------------------------------------------------------------
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientStock(InsufficientStockException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
    // ------------------------------------------------------------------
    // 13. 例外處理：新增甜點時名稱重複
    //
    // 補上原因：DessertServiceImpl.create() 會拋出 DuplicateNameException，
    // 但目前沒有任何 @ExceptionHandler 攔截它，導致名稱重複時會變成
    // 沒被處理的 500 Internal Server Error，而不是預期的 409 Conflict。
    // ------------------------------------------------------------------
    @ExceptionHandler(DuplicateNameException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateName(DuplicateNameException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
    // ------------------------------------------------------------------
// 14. 例外處理：查詢/刪除的資料不存在
//
// 補上原因：getById()、getOrder()、deleteOrder() 都會拋出
// EntityNotFoundException，但目前沒有 @ExceptionHandler 攔截，
// 導致找不到資料時是未處理的 500，而不是預期的 404 Not Found。
// ------------------------------------------------------------------
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleEntityNotFound(EntityNotFoundException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

}