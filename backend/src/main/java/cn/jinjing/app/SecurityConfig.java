package cn.jinjing.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;

@Configuration
public class SecurityConfig {
    @Bean
    UserDetailsService users(@Value("${app.admin-user}") String username,
                             @Value("${app.admin-password}") String password,
                             @Value("${app.auth-enabled}") boolean authEnabled) {
        if (!authEnabled) {
            return new InMemoryUserDetailsManager();
        }
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("APP_ADMIN_PASSWORD must have at least 12 characters");
        }
        var user = User.withUsername(username)
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password))
                .roles("ADMIN").build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    @Value("${app.auth-enabled}") boolean authEnabled) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (!authEnabled) {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .httpBasic(basic -> basic.disable())
                    .build();
        }
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/admin.html", "/app.css", "/app.js",
                                "/portal.css", "/portal.js", "/icon/**", "/favicon.ico", "/actuator/health", "/error",
                                "/api/v1/site/**", "/api/v1/cameras", "/api/v1/cameras/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
