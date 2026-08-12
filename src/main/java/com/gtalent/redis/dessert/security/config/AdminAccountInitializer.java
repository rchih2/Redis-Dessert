package com.gtalent.redis.dessert.config;

import com.gtalent.redis.dessert.security.model.Role;
import com.gtalent.redis.dessert.security.model.User;
import com.gtalent.redis.dessert.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 專案第一次啟動、users 表還沒有任何 ADMIN 帳號時，自動建立一組預設管理員，
 * 避免「先有雞還是先有蛋」問題（沒有 ADMIN 就沒辦法呼叫 create-staff，但建立 ADMIN 又需要 ADMIN 權限）。
 *
 * ⚠️ 這組預設密碼只適合本機開發/展示用，正式環境請務必立刻登入後改密碼，
 * 或透過環境變數覆寫初始密碼（下面已經支援 ADMIN_INIT_PASSWORD 覆寫）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin-init.username:admin}")
    private String initUsername;

    @Value("${app.admin-init.password:admin123}")
    private String initPassword;

    @Override
    public void run(String... args) {
        if (userRepository.existsByUsername(initUsername)) {
            return;
        }

        User admin = new User();
        admin.setUsername(initUsername);
        admin.setPassword(passwordEncoder.encode(initPassword));
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);
        userRepository.save(admin);

        log.warn("[AdminAccountInitializer] 已建立預設管理員帳號 username={}，" +
                "這組密碼只適合開發環境，正式上線前請務必修改！", initUsername);
    }
}