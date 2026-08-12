package com.gtalent.redis.dessert.security.service;

import com.gtalent.redis.dessert.security.model.Role;
import com.gtalent.redis.dessert.security.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

/**
 * 包一層 UserDetails，而不是讓 User entity 直接 implements UserDetails，
 * 讓 model 層維持跟 Dessert/Order 一樣單純的 @Data entity，職責分離。
 */
public class CustomUserPrincipal implements UserDetails {

    private final User user;

    public CustomUserPrincipal(User user) {
        this.user = user;
    }

    public Role getRole() {
        return user.getRole();
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        // Spring Security 的 hasRole("ADMIN") 底層比對的是 "ROLE_ADMIN" 這個 authority，
        // 這裡的前綴是必要的，忘記加會導致 hasRole 永遠判斷失敗
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return Boolean.TRUE.equals(user.getEnabled());
    }
}