package com.gtalent.redis.dessert.security.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponseDTO {
    private String token;
    private String tokenType = "Bearer";
    private String username;
    private String role;

    public AuthResponseDTO(String token, String username, String role) {
        this.token = token;
        this.tokenType = "Bearer";
        this.username = username;
        this.role = role;
    }
}