package com.gtalent.redis.dessert.security.config;

import com.gtalent.redis.dessert.security.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 三層角色的 URL 層級授權規則。
 *
 * 設計原則：
 * 1. 這裡的規則是「第一道防線」，涵蓋大部分路徑；DessertOrderController 裡對
 *    admin/XX/purge 這類特別敏感的端點另外疊加 @PreAuthorize 做第二道防線（defense in depth）。
        * 2. authorizeHttpRequests 是「由上往下、第一個符合的規則生效」，所以越明確的路徑要寫在越前面，
        *    anyRequest().authenticated() 放最後當保底（涵蓋 AI 對話、搜尋等本次沒有明確討論到的端點，
        *    預設「只要登入就能用」，若這不是你要的行為，請再自行調整）。
        * 3. Spring Boot 4 / Security 7 對 REST API 預設會啟用 CSRF 檢查，這裡的 JWT 是無狀態（不用 Session/Cookie），
        *    不需要 CSRF 防護，所以明確關閉，否則所有 POST/PUT/DELETE 都會被擋成 403。
        **/

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // 登入/註冊本身當然不能要求先登入
                        .requestMatchers("/api/auth/**").permitAll()
                        // 系統監控端點維持公開（沿用專案原本的 actuator 設定）
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()

                        // 甜點瀏覽：不需要登入，一般消費者逛菜單很正常
                        .requestMatchers(HttpMethod.GET, "/api/desserts", "/api/desserts/*").permitAll()

                        // CSV 匯入菜單：STAFF 可做（管理甜點的一部分），ADMIN 當然也可以
                        .requestMatchers(HttpMethod.POST, "/api/admin/desserts/csv").hasAnyRole("ADMIN", "STAFF")
                        // 其餘 /api/admin/** （目前是 purge 兩支：真的刪除甜點/訂單，不可逆）只給 ADMIN
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // 甜點管理（新增/修改/刪除單筆）：STAFF 的核心工作
                        .requestMatchers(HttpMethod.POST, "/api/desserts").hasAnyRole("ADMIN", "STAFF")
                        .requestMatchers(HttpMethod.PUT, "/api/desserts/*").hasAnyRole("ADMIN", "STAFF")
                        .requestMatchers(HttpMethod.DELETE, "/api/desserts/*").hasAnyRole("ADMIN", "STAFF")
                        // 整批刪除全部甜點是破壞性更大的操作，收斂給 ADMIN
                        .requestMatchers(HttpMethod.DELETE, "/api/desserts").hasRole("ADMIN")

                        // 訂單查詢：屬於後台營運資訊，開放給 ADMIN / STAFF（顧客目前沒有「查詢自己訂單」的端點）
                        .requestMatchers(HttpMethod.GET, "/api/orders", "/api/orders/*").hasAnyRole("ADMIN", "STAFF")
                        // 建立訂單：任何登入的使用者都可以下單（USER/STAFF/ADMIN 皆可）
                        .requestMatchers(HttpMethod.POST, "/api/orders").authenticated()
                        // 刪除訂單（含軟刪除）：明確要求 STAFF 不能刪訂單，只給 ADMIN
                        .requestMatchers(HttpMethod.DELETE, "/api/orders/*", "/api/orders").hasRole("ADMIN")

                        // 其餘所有路徑（AI 對話、搜尋等本次未逐一討論的端點）預設只要登入即可存取
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}