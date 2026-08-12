package com.gtalent.redis.dessert.service.impl;

import com.gtalent.redis.dessert.dto.OrderCreateDTO;
import com.gtalent.redis.dessert.dto.OrderItemCreateDTO;
import com.gtalent.redis.dessert.dto.OrderResponseDTO;
import com.gtalent.redis.dessert.event.EventPublisherService;
import com.gtalent.redis.dessert.event.OrderEvent;
import com.gtalent.redis.dessert.event.OrderEventType;
import com.gtalent.redis.dessert.metrics.BusinessMetrics;
import com.gtalent.redis.dessert.model.Dessert;
import com.gtalent.redis.dessert.model.Order;
import com.gtalent.redis.dessert.repository.OrderRepository;
import com.gtalent.redis.dessert.service.DessertService;
import com.gtalent.redis.dessert.service.InsufficientStockException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderServiceImpl} 的單元測試。
 *
 * <p>本測試層級只用 Mockito 模擬 repository / event / metrics 相依，
 * 不會真的連線 MySQL、Kafka，也不會真的開啟 Spring 交易，
 * 因此 {@code @Transactional} 的「回滾」行為本身不在這裡驗證範圍內
 * （見各測試方法上的個別說明），需要真正驗證回滾請另外用
 * {@code @SpringBootTest} 或 {@code @DataJpaTest} 補齊。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private DessertService dessertService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private EventPublisherService eventPublisherService;

    @Mock
    private BusinessMetrics businessMetrics;

    @InjectMocks
    private OrderServiceImpl orderService;

    /**
     * 建立一筆兩品項的下單請求，第一項下單成功、第二項庫存不足。
     *
     * <p>驗證重點：{@code createOrder} 應該在第二個品項扣庫存時提前拋出
     * {@link InsufficientStockException}，並且整個方法不應該執行到
     * {@code orderRepository.save(...)}。
     *
     * <p>注意：這裡驗證的是「程式碼會提前中斷、不會走到 save」這件事本身，
     * 而不是「第一項已經扣掉的庫存真的在資料庫層被回滾」——
     * 後者是 {@code @Transactional} 交給 Spring/資料庫保證的行為，
     * 單元測試層級沒有真正的交易可以驗證，需要 {@code @SpringBootTest}
     * 搭配真的資料庫才能覆蓋到。</p>
     */
    @Test
    @DisplayName("下單時第二項庫存不足，應拋出例外且不會建立訂單")
    void createOrder_shouldThrowAndNotSave_whenSecondItemStockInsufficient() {
        // given
        OrderCreateDTO dto = buildOrderCreateDTO();

        Dessert dessert1 = buildDessert(1L, "布丁", "50");
        Dessert dessert2 = buildDessert(2L, "泡芙", "80");

        when(dessertService.getById(1L)).thenReturn(dessert1);
        when(dessertService.getById(2L)).thenReturn(dessert2);

        // 第一項扣庫存成功。這裡明確 stub 出來（而不是靠「void 方法預設 doNothing」），
        // 是因為 Mockito strict stubbing 在同一個方法被不同參數 stub 過（見下面的 2L）時，
        // 若呼叫到「完全沒被 stub 到」的參數組合（1L），會誤判成
        // PotentialStubbingProblem（誤以為是測試打錯參數），而不是單純回傳預設值。
        doNothing().when(dessertService).deductStock(eq(1L), anyInt());

        doThrow(new InsufficientStockException("「泡芙」庫存不足，目前剩餘 0 份，已無法下單"))
                .when(dessertService).deductStock(eq(2L), anyInt());

        // when / then
        assertThatThrownBy(() -> orderService.createOrder(dto))
                .isInstanceOf(InsufficientStockException.class);

        verify(orderRepository, never()).save(any(Order.class));
        verify(eventPublisherService, never()).publishOrderEvent(any());
    }

    /**
     * 驗證下單成功時，事件內容（eventType = ORDER_CREATED）與業務指標會被正確觸發。
     *
     * <p>單元測試環境中沒有真正的 Spring 交易，
     * {@code TransactionSynchronizationManager.isSynchronizationActive()} 恆為 false，
     * 因此程式碼一定會走「保底邏輯」的 else 分支，直接呼叫
     * {@code eventPublisherService.publishOrderEvent(...)}，這裡驗證的正是這個分支
     * 送出的事件內容是否正確。
     *
     * <p>若要驗證「afterCommit 真的延後到交易 commit 之後才觸發」這個時序行為，
     * 需要 {@code @SpringBootTest} 搭配真實的資料庫交易，
     * 單元測試層級只能覆蓋「事件內容正確性」，時序行為留給整合測試驗證。</p>
     */
    @Test
    @DisplayName("下單成功時應發布 ORDER_CREATED 事件並記錄業務指標")
    void createOrder_shouldPublishOrderCreatedEventAndRecordMetrics_whenSuccess() {
        // given
        OrderCreateDTO dto = buildOrderCreateDTO();

        Dessert dessert1 = buildDessert(1L, "布丁", "50");
        Dessert dessert2 = buildDessert(2L, "泡芙", "80");

        when(dessertService.getById(1L)).thenReturn(dessert1);
        when(dessertService.getById(2L)).thenReturn(dessert2);
        // deductStock 兩項都成功（void 方法，預設 doNothing）

        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(100L);
            return order;
        });

        // when
        OrderResponseDTO response = orderService.createOrder(dto);

        // then
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.success()).isTrue();

        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(eventPublisherService, times(1)).publishOrderEvent(eventCaptor.capture());

        OrderEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.eventType()).isEqualTo(OrderEventType.ORDER_CREATED);
        assertThat(publishedEvent.orderId()).isEqualTo(100L);

        verify(businessMetrics, times(1)).recordOrderCreated(any());
        verify(businessMetrics, times(1)).recordProductSold(eq(1L), eq("布丁"), anyInt());
        verify(businessMetrics, times(1)).recordProductSold(eq(2L), eq("泡芙"), anyInt());
    }

    /**
     * 驗證 softDelete 對「訂單不存在或已被軟刪除過」（softDeleteById 回傳 0）的處理：
     * 應拋出 {@link EntityNotFoundException}，且不應該送出 Kafka 事件或記錄指標。
     */
    @Test
    @DisplayName("軟刪除不存在或已刪除過的訂單時應拋出 EntityNotFoundException")
    void softDelete_shouldThrowEntityNotFoundException_whenOrderNotFoundOrAlreadyDeleted() {
        // given
        when(orderRepository.softDeleteById(999L)).thenReturn(0);

        // when / then
        assertThatThrownBy(() -> orderService.softDelete(999L))
                .isInstanceOf(EntityNotFoundException.class);

        verify(eventPublisherService, never()).publishOrderEvent(any());
    }

    private OrderCreateDTO buildOrderCreateDTO() {
        OrderItemCreateDTO item1 = new OrderItemCreateDTO();
        item1.setDessertId(1L);
        item1.setQuantity(2);

        OrderItemCreateDTO item2 = new OrderItemCreateDTO();
        item2.setDessertId(2L);
        item2.setQuantity(1);

        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setCustomerName("王小明");
        dto.setPhone("0912345678");
        dto.setLineId("wang123");
        dto.setItems(List.of(item1, item2));
        return dto;
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