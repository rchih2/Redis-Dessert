package com.gtalent.redis.dessert.security.service;

import com.gtalent.redis.dessert.security.dto.AuthResponseDTO;
import com.gtalent.redis.dessert.security.dto.LoginRequestDTO;
import com.gtalent.redis.dessert.security.dto.RegisterRequestDTO;
import com.gtalent.redis.dessert.security.exception.DuplicateUsernameException;
import com.gtalent.redis.dessert.security.jwt.JwtTokenProvider;
import com.gtalent.redis.dessert.security.model.Role;
import com.gtalent.redis.dessert.security.model.User;
import com.gtalent.redis.dessert.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 公開註冊：一律只建立 USER 角色帳號。
     * 刻意不讓前端傳入想要的角色，避免任何人自己註冊成 ADMIN/STAFF 造成權限提升漏洞。
     * ADMIN/STAFF 帳號只能透過下面的 createStaffOrAdmin()（需要 ADMIN 權限才能呼叫）建立。
     */
    @Transactional
    public AuthResponseDTO register(RegisterRequestDTO request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateUsernameException("帳號 " + request.getUsername() + " 已被使用");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.USER);
        user.setEnabled(true);
        userRepository.save(user);

        CustomUserPrincipal principal = new CustomUserPrincipal(user);
        String token = jwtTokenProvider.generateToken(principal);
        return new AuthResponseDTO(token, user.getUsername(), user.getRole().name());
    }

    public AuthResponseDTO login(LoginRequestDTO request) {
        // 交給 AuthenticationManager（底層會用 CustomUserDetailsService + PasswordEncoder 驗證），
        // 帳密錯誤會拋出 BadCredentialsException，統一由 GlobalExceptionHandler 處理成 401
        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        CustomUserPrincipal principal = (CustomUserPrincipal) authentication.getPrincipal();
        String token = jwtTokenProvider.generateToken(principal);
        return new AuthResponseDTO(token, principal.getUsername(), principal.getRole().name());
    }

    /** 只有 ADMIN 能呼叫（權限檢查寫在 AuthController 的 @PreAuthorize），用來建立 STAFF 或 ADMIN 帳號 */
    @Transactional
    public AuthResponseDTO createStaffOrAdmin(RegisterRequestDTO request, Role role) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateUsernameException("帳號 " + request.getUsername() + " 已被使用");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(role);
        user.setEnabled(true);
        userRepository.save(user);

        return new AuthResponseDTO(null, user.getUsername(), user.getRole().name());
    }
}