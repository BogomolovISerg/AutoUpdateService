package git.autoupdateservice.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final MustChangePasswordFilter mustChangePasswordFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/error", "/app.css").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/gitlab/webhook").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/test-objects/connection").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/test-objects").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", false)
                        .permitAll()
                )
                .logout(Customizer.withDefaults());

        // CSRF: отключаем только для внешних API. Доступ к ним дополнительно защищается токеном.
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/gitlab/webhook", "/api/test-objects"));

        // принудительная смена пароля после логина
        http.addFilterAfter(mustChangePasswordFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

