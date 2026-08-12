package com.gtalent.redis.dessert;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 驗證完整 Spring Context 能否在外部服務就緒後正常啟動。
 */
@Tag("integration")
@SpringBootTest
class RedisDessertApplicationTests {

    @Test
    void contextLoads() {
    }
}
