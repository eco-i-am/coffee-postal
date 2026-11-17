package ph.coffeepostal.pos.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http
          .authorizeHttpRequests(a -> a
              .requestMatchers("/actuator/health/**", "/login", "/css/**", "/js/**", "/img/**", "/manifest.webmanifest", "/sw.js").permitAll()
              .requestMatchers("/admin/**").hasAuthority("SCOPE_ADMIN")
              .requestMatchers("/branch/**", "/pos/**", "/api/pos/**").hasAnyAuthority("SCOPE_BRANCH", "SCOPE_ADMIN")
              .anyRequest().authenticated())
          .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true))
          .logout(l -> l.logoutSuccessUrl("/login?out"))
          .headers(h -> h.frameOptions(o -> o.deny()));
        return http.build();
    }

    @Bean PasswordEncoder passwordEncoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
}
