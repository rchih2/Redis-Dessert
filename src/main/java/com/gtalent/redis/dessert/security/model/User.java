package com.gtalent.redis.dessert.security.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 系統使用者帳號。
 * 注意：MySQL 的 "user" 是保留字，table 名稱用 "users" 避免建表失敗。
 */
@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "帳號不可為空白")
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** 儲存的是 BCrypt 雜湊值，絕不存明碼 */
    @NotBlank(message = "密碼不可為空白")
    @Column(nullable = false, length = 100)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** 帳號是否啟用（停用後無法登入，但不刪除資料，比照 Dessert/Order 軟刪除精神） */
    @Column(nullable = false, columnDefinition = "tinyint(1) default 1")
    private Boolean enabled = true;
}