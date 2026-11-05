package cn.jinjing.app;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.util.Set;

@Configuration
public class SecurityConfig {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

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
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (!authEnabled) {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .httpBasic(basic -> basic.disable())
                    .build();
        }
        http.csrf(csrf -> csrf
                .csrfTokenRepository(new CookieCsrfTokenRepository())
                .requireCsrfProtectionMatcher(request ->
                        !SAFE_METHODS.contains(request.getMethod())
                                && request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/admin/")));
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/portal.css", "/portal.js", "/icon/**", "/brand/**", "/favicon.ico", "/actuator/health", "/error",
                                "/api/v1/site/**", "/api/v1/cameras", "/api/v1/cameras/**", "/api/v1/routes/**").permitAll()
                        .requestMatchers(request -> request.getDispatcherType() == DispatcherType.FORWARD
                                && "/".equals(request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI))
                                && (request.getContextPath() + "/index.html").equals(request.getRequestURI())).permitAll()
                        .requestMatchers("/index.html", "/admin.html", "/app.css", "/app.js", "/admin-security.js",
                                "/sync.html", "/sync.css", "/sync.js",
                                "/review.html", "/review.css", "/review.js").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
