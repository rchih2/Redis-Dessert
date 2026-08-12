package com.gtalent.redis.dessert.security.controller;

import com.gtalent.redis.dessert.security.dto.AuthResponseDTO;
import com.gtalent.redis.dessert.security.dto.LoginRequestDTO;
import com.gtalent.redis.dessert.security.dto.RegisterRequestDTO;
import com.gtalent.redis.dessert.security.model.Role;
import com.gtalent.redis.dessert.security.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 一般顧客註冊，一律建立 USER 角色帳號 */
    @PostMapping("/register")
    public ResponseEntity<AuthResponseDTO> register(@Valid @RequestBody RegisterRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDTO> login(@Valid @RequestBody LoginRequestDTO request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * 建立 STAFF 帳號，只有 ADMIN 能呼叫。
     * 雙重保護：這裡的 @PreAuthorize 是方法層級的第二道防線，
     * SecurityConfig 裡 /api/auth/** 是 permitAll，如果沒有這個 @PreAuthorize，
     * 任何人都能打這支 API 幫自己建 STAFF 帳號，等於權限提升漏洞。
     */
    @PostMapping("/admin/create-staff")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuthResponseDTO> createStaff(@Valid @RequestBody RegisterRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.createStaffOrAdmin(request, Role.STAFF));
    }
}