package com.gtalent.redis.dessert.service.impl;

import com.gtalent.redis.dessert.dto.OrderCreateDTO;
import com.gtalent.redis.dessert.dto.OrderItemCreateDTO;
import com.gtalent.redis.dessert.dto.OrderItemResponseDTO;
import com.gtalent.redis.dessert.dto.OrderResponseDTO;
import com.gtalent.redis.dessert.event.EventPublisherService;
import com.gtalent.redis.dessert.event.OrderEvent;
import com.gtalent.redis.dessert.event.OrderEventType;
import com.gtalent.redis.dessert.metrics.BusinessMetrics;
import com.gtalent.redis.dessert.model.Dessert;
import com.gtalent.redis.dessert.model.Order;
import com.gtalent.redis.dessert.model.OrderItem;
import com.gtalent.redis.dessert.repository.OrderRepository;
import com.gtalent.redis.dessert.service.DessertService;
import com.gtalent.redis.dessert.service.OrderService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /** 滿此金額（不含運費）即免運 */
    private static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("2000");
    /** 未滿門檻時的運費 */
    private static final BigDecimal SHIPPING_FEE = new BigDecimal("60");

    private final DessertService dessertService;
    private final OrderRepository orderRepository;
    private final EventPublisherService eventPublisherService;
    private final BusinessMetrics businessMetrics;

    // ------------------------------------------------------------------
    // 建立訂單：第一層(@Valid，由 Controller 負責) + 第二層(金額覆核)驗證 + 第三層(扣庫存)
    //
    // 修改重點：
    // 1) 加上 @Transactional：讓整個下單流程（扣庫存 + 建立訂單）在同一個交易內。
    //    只要其中之一庫存不足拋出例外，前面已經扣成功品項也會一併回滾，
    //    不會發生「這張訂單只扣一半庫存」的情況。
    // 2) 在迴圈中呼叫 dessertService.deductStock(...)：
    //    改用資料庫層的原子 UPDATE（WHERE stock >= quantity）真的扣掉庫存，
    //    而不是只讀取單價、卻從來沒有扣減庫存數字。
    // ------------------------------------------------------------------
    @Override
    @Transactional
    public OrderResponseDTO createOrder(OrderCreateDTO orderCreateDTO) {

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
        order.setUsername(resolveCurrentUsername());
        order.setTotalAmount(calculatedTotal);
        order.setOrderTime(LocalDateTime.now());

        for (OrderItem orderItem : orderItems) {
            order.addItem(orderItem);
        }

        Order savedOrder = orderRepository.save(order);

        Map<String, Object> orderPayload = new LinkedHashMap<>();
        orderPayload.put("customerName", savedOrder.getCustomerName());
        orderPayload.put("phone", savedOrder.getPhone());
        orderPayload.put("totalAmount", savedOrder.getTotalAmount());
        orderPayload.put("itemCount", orderItems.size());

        // ------------------------------------------------------------------
        // 訂單建立成功後發布 order-events 事件到 Kafka，並累加業務指標。
        //
        // 注意這裡不是立即送出/計數，而是註冊在 transaction afterCommit。
        // 這樣可以避免「MySQL 交易最後回滾，但 Kafka 事件已送出、業務指標已經
        // 被算進去」的幻影資料問題——指標應該只反映真正落地的訂單。
        // ------------------------------------------------------------------
        OrderEvent orderEvent = new OrderEvent(
                savedOrder.getId(),
                OrderEventType.ORDER_CREATED,
                orderPayload,
                LocalDateTime.now()
        );

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisherService.publishOrderEvent(orderEvent);
                    recordOrderMetrics(savedOrder.getTotalAmount(), orderItems);
                }
            });
        } else {
            // 理論上 @Transactional 會保證這裡有 transaction；保底邏輯保留，
            // 方便未來若下單流程重構成非交易呼叫時仍能維持事件輸出與指標。
            eventPublisherService.publishOrderEvent(orderEvent);
            recordOrderMetrics(savedOrder.getTotalAmount(), orderItems);
        }

        // ------------------------------------------------------------------
        // 統一組出 OrderResponseDTO，讓 POST / GET 回傳同一種訂單格式，
        // 前端不用再依端點分別解析兩種不同的 JSON 結構。
        // ------------------------------------------------------------------
        List<OrderItemResponseDTO> responseItems = orderItems.stream()
                .map(oi -> new OrderItemResponseDTO(
                        oi.getDessertId(),
                        oi.getDessertName(),
                        oi.getUnitPrice(),
                        oi.getQuantity(),
                        oi.getLineTotal()))
                .toList();

        return new OrderResponseDTO(
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
    }

    // ------------------------------------------------------------------
    // 私有輔助方法：交易確定 commit 後才呼叫，累加「訂單總數／成功訂單」
    // 與依品項數量累加的「商品銷售數」，避免交易回滾時指標被誤算進去。
    // ------------------------------------------------------------------
    private void recordOrderMetrics(BigDecimal totalAmount, List<OrderItem> orderItems) {
        businessMetrics.recordOrderCreated(totalAmount);
        for (OrderItem orderItem : orderItems) {
            businessMetrics.recordProductSold(
                    orderItem.getDessertId(),
                    orderItem.getDessertName(),
                    orderItem.getQuantity());
        }
    }
    // ------------------------------------------------------------------
    // 查詢全部訂單(含品項明細)
    // ------------------------------------------------------------------
    @Override
    @Transactional(readOnly = true)
    public List<OrderResponseDTO> findAll() {
        // 改用 findByDeletedFalse()：已軟刪除的訂單不應出現在查詢結果中
        return orderRepository.findByDeletedFalse().stream()
                .map(this::toResponseDTO)
                .toList();
    }
    /**
     * 依登入使用者查詢自己的訂單清單（未軟刪除），依下單時間倒序排列。
     *
     * <p>{@code username} 一律由 Controller 從 {@link org.springframework.security.core.Authentication}
     * 取得（即 JWT token 解出來的登入者身分），不接受前端以參數形式傳入 username 來查詢，
     * 避免使用者竄改參數查到別人的訂單資料——這點與 {@link #findAll()}／{@link #getById(Long)}
     * （後台 ADMIN／STAFF 視角，可查看全部訂單）刻意區分開來，{@code findMyOrders} 是
     * 顧客視角，只能看到「自己下單時，當下登入帳號」建立的訂單。</p>
     *
     * <p>⚠️ 已知限制：{@code username} 只有在下單當下（{@link #createOrder}）由
     * {@code resolveCurrentUsername()} 寫入 {@code Order.username} 欄位才會存在；
     * 在此欄位新增之前建立的歷史訂單，{@code username} 會是 {@code null}，
     * 不會出現在任何使用者的查詢結果中（因為 {@code null != 任何登入者的 username}）。</p>
     *
     * @param username 目前登入者的帳號（來自 JWT，非前端傳入）
     * @return 該使用者名下、尚未軟刪除的訂單清單，依下單時間新到舊排序
     */
    @Override
    @Transactional(readOnly = true)
    public List<OrderResponseDTO> findMyOrders(String username) {
        return orderRepository.findByUsernameAndDeletedFalseOrderByOrderTimeDesc(username).stream()
                .map(this::toResponseDTO)
                .toList();
    }
    // ------------------------------------------------------------------
    // 查詢單一訂單(含品項明細)
    // ------------------------------------------------------------------
    @Override
    @Transactional(readOnly = true)
    public OrderResponseDTO getById(Long id) {
        // 改用 findByIdAndDeletedFalse()：id 對應到已軟刪除的訂單時，視同「找不到」
        Order order = orderRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到 id=" + id + " 的訂單"));
        return toResponseDTO(order);
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
    // 刪除單一訂單（改為軟刪除：只標記 deleted = true，資料與訂單明細仍留在資料庫，
    //    方便未來客戶對單、財務對帳或糾紛時可追溯歷史紀錄）
    // ------------------------------------------------------------------
    @Override
    @Transactional
    public void softDelete(Long id) {
        int updated = orderRepository.softDeleteById(id);
        if (updated == 0) {
            throw new EntityNotFoundException("找不到 id=" + id + " 的訂單");
        }
        // ------------------------------------------------------------------
        // 比照 createOrder()：軟刪除成功後發布 ORDER_DELETED 事件到 Kafka，
        // 一樣註冊在 transaction afterCommit 才送出，避免交易回滾（理論上這裡
        // 不太會發生，但保持跟 createOrder 一致的防呆模式）時誤發「已取消」事件。
        //
        // 這裡的 id 就是真實訂單 id，跟 createOrder 一樣可以直接當 orderId，
        // EventLogConsumer 消費時組出的 eventKey（id + ":ORDER_DELETED"）
        // 天生就不會重複——因為 softDeleteById() 的 WHERE 條件擋住了「對同一筆
        // 訂單重複軟刪除」的情況，updated 一定是先前沒被刪過的那一筆。
        // ------------------------------------------------------------------
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", id);

        OrderEvent orderEvent = new OrderEvent(
                id,
                OrderEventType.ORDER_DELETED,
                payload,
                LocalDateTime.now()
        );

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisherService.publishOrderEvent(orderEvent);
                }
            });
        } else {
            eventPublisherService.publishOrderEvent(orderEvent);
        }
    }

    // ------------------------------------------------------------------
    // 刪除全部訂單（改為軟刪除：整批標記 deleted = true，
    //    資料實際上仍留在資料庫，因此不再需要、也不應該重置自增計數器）
    // ------------------------------------------------------------------
    @Override
    @Transactional
    public int softDeleteAll() {
        int updated = orderRepository.softDeleteAll();

        // ------------------------------------------------------------------
        // ORDER_DELETED 事件（批次彙總版）——誠實記錄這裡的限制：
        //
        // orderRepository.softDeleteAll() 是一條 bulk UPDATE（見 OrderRepository），
        // 只回傳被更新的筆數，沒有取得個別被刪除訂單的 id，所以沒辦法像 softDelete(id)
        // 那樣逐筆發送「orderId 對應一則事件」。這裡刻意不為了湊出單筆事件，
        // 額外多查一次「刪除前的訂單清單」——那筆查詢在批次刪除的情境下是可以省略的
        // 成本，加回去只是為了讓事件格式好看，不划算。
        //
        // 改成只發一則「批次刪除筆數」的彙總事件，但 orderId 這個欄位不能留 null：
        // EventPublisherService.publishOrderEvent() 只要看到 orderId == null 就會直接
        // 略過、完全不送出（見該類別實作）。也不能沿用某個固定的 sentinel（例如 0），
        // 因為 EventLogConsumer 的去重鍵固定是 orderId + ":" + eventType，同一個
        // sentinel 在下一次呼叫「刪除全部訂單」時會產生一模一樣的 eventKey，
        // 被既有的 existsByEventKey() 誤判成「Kafka 重複投遞」而略過寫入，
        // 導致真正發生過的批次取消事件反而在 ActionLog 裡「消失」，比完全不發事件更糟。
        //
        // 因此改用「負的目前時間戳記（毫秒）」當這次批次操作的合成 id：
        // 負數保證不會跟任何真實訂單 id（自增主鍵、必為正數）相撞；時間戳記則讓
        // 每次呼叫幾乎都拿到不同的 eventKey，不會被去重機制誤殺（同一毫秒內重複呼叫
        // 這種極端情況才會撞號，機率在本專案的使用情境下可以忽略）。這個 id 不代表
        // 任何真實訂單，純粹是為了讓這則彙總事件能沿用現有的 OrderEvent /
        // EventLogConsumer pipeline 而做的合成值。如果未來要更嚴謹地表達「這是一則
        // 批次事件」，建議另外設計專屬的 eventKey 格式（例如帶 batchId 欄位），
        // 而不是繼續借用「orderId 欄位」硬湊。
        // ------------------------------------------------------------------
        if (updated > 0) {
            long syntheticBatchId = -System.currentTimeMillis();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("batch", true);
            payload.put("deletedCount", updated);

            OrderEvent orderEvent = new OrderEvent(
                    syntheticBatchId,
                    OrderEventType.ORDER_DELETED,
                    payload,
                    LocalDateTime.now()
            );

            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        eventPublisherService.publishOrderEvent(orderEvent);
                    }
                });
            } else {
                eventPublisherService.publishOrderEvent(orderEvent);
            }
        }
        return updated;
    }
    /**
     * 從 SecurityContext 取得目前登入者的 username，寫入 Order.username 欄位，
     * 用於之後「查詢我自己的訂單」功能。理論上 POST /api/orders 一定要求已登入
     * （見 SecurityConfig 規則），這裡的 null 檢查是額外的防呆，避免匿名情境下拋例外。
     */
    private String resolveCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if ("anonymousUser".equals(principal)) {
            return null;
        }
        return authentication.getName();
    }
}