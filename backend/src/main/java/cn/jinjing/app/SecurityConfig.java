package cn.jinjing.app;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        http.addFilterBefore(new ApiRateLimitFilter(), UsernamePasswordAuthenticationFilter.class);
        if (!authEnabled) {
            http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(fixation -> fixation.changeSessionId()));
        http.csrf(csrf -> csrf
                .csrfTokenRepository(new CookieCsrfTokenRepository())
                .requireCsrfProtectionMatcher(request ->
                        !SAFE_METHODS.contains(request.getMethod())
                                && (request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/admin/")
                                    || request.getRequestURI().equals(request.getContextPath() + "/login")
                                    || request.getRequestURI().equals(request.getContextPath() + "/logout"))));
        var loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login.html");
        http.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> {
            if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
                response.sendError(HttpStatus.UNAUTHORIZED.value());
            } else {
                loginEntryPoint.commence(request, response, failure);
            }
        }));
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/traffic-rules.html", "/login.html", "/login.css", "/login.js", "/api/v1/auth/csrf",
                                "/portal.css", "/portal.js", "/icon/**", "/brand/**", "/favicon.ico", "/actuator/health", "/error",
                                "/api/v1/site/**", "/api/v1/review/arrivals", "/api/v1/cameras", "/api/v1/cameras/**",
                                "/api/v1/camera-reports", "/api/v1/routes/**", "/api/v1/members/**",
                                "/api/v1/android/users/**", "/api/v1/contact/wechat-qr").permitAll()
                        .requestMatchers(request -> request.getDispatcherType() == DispatcherType.FORWARD
                                && "/".equals(request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI))
                                && (request.getContextPath() + "/index.html").equals(request.getRequestURI())).permitAll()
                        .requestMatchers("/index.html", "/manage.html", "/manage.css", "/manage.js", "/admin-embed.css",
                                "/admin.html", "/app.css", "/app.js", "/admin-security.js",
                                "/sync.html", "/sync.css", "/sync.js",
                                "/review.html", "/review.css", "/review.js",
                                "/reports.html", "/reports.css", "/reports.js", "/ring-location.js", "/ring-boundaries.json", "/road-search.js",
                                "/daily-comments.html", "/daily-comments.css", "/daily-comments.js",
                                "/members.html", "/members.js", "/member-history.html", "/member-history.js", "/member-admin.css",
                                "/android-users.html", "/android-users.js", "/android-user-history.html", "/android-user-history.js",
                                "/miniprogram-mode.html", "/miniprogram-mode.js").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .formLogin(form -> form.loginPage("/login.html").loginProcessingUrl("/login")
                        .defaultSuccessUrl("/manage.html", false).failureUrl("/login.html?error").permitAll())
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login.html?logout")
                        .invalidateHttpSession(true).deleteCookies("JSESSIONID", "XSRF-TOKEN").permitAll())
                .build();
    }
}
