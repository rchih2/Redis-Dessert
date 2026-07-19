package com.gtalent.redis.dessert.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 專門給「數據日誌中心」使用的非同步執行緒池設定。
 *
 * <p>ActionLog / SearchHistory 這類寫入屬於「附帶紀錄」性質，
 * 不應該拖慢主要業務流程（例如下單）的回應時間，因此透過獨立的
 * Executor 以 @Async 非阻塞方式寫入 MongoDB。</p>
 *
 * <p>刻意獨立一個 bean 名稱 {@code mongoLoggingExecutor}，
 * 而不是覆寫 Spring 預設的 {@code taskExecutor}，是為了避免影響專案中
 * 其他既有的 @Async 用途（例如 Email 發送、其他背景工作）。</p>
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean(name = "mongoLoggingExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mongo-log-");
        // 佇列滿了寧可讓呼叫端執行緒自己跑（CallerRunsPolicy），
        // 也不要直接丟棄日誌，確保數據日誌中心不會因為背壓而漏資料。
        executor.setRejectedExecutionHandler(callerRunsPolicy());
        executor.initialize();
        return executor;
    }

    private RejectedExecutionHandler callerRunsPolicy() {
        return new ThreadPoolExecutor.CallerRunsPolicy();
    }

    /**
     * void 回傳型別的 @Async 方法，例外預設會被「吞掉」而不會拋到呼叫端，
     * 這裡註冊一個處理器把例外印出來，避免日誌寫入失敗卻完全無聲無息、
     * 難以排查（例如 MongoDB 連線斷線時應該要能在 log 中看到）。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AsyncUncaughtExceptionHandler() {
            @Override
            public void handleUncaughtException(Throwable ex, Method method, Object... params) {
                log.error("非同步數據日誌寫入失敗，method={}, params={}", method.getName(), params, ex);
            }
        };
    }
}