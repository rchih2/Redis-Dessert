package com.gtalent.redis.dessert.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequestDTO {

    @NotBlank(message = "帳號不可為空白")
    @Size(min = 3, max = 50, message = "帳號長度需介於 3~50 字元")
    private String username;

    @NotBlank(message = "密碼不可為空白")
    @Size(min = 6, message = "密碼長度至少 6 字元")
    private String password;
}