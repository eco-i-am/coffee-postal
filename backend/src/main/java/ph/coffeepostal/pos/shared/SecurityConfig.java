package ph.coffeepostal.pos.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The backend is a JSON API. The React app (frontend/) signs in over POST /api/auth/login
 * (form fields username + password), then keeps a session cookie.
 * CSRF: the XSRF-TOKEN cookie is readable by the app, which echoes it in the X-XSRF-TOKEN header.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain chain(HttpSecurity http, org.springframework.jdbc.core.JdbcTemplate jdbc, SessionGuard.AuthVersion authVersion) throws Exception {
        http
          .addFilterAfter(new SessionGuard(jdbc, authVersion), BasicAuthenticationFilter.class)
          .addFilterAfter(new PracticeFilter(), SessionGuard.class)
          .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                      .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
          .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
          .authorizeHttpRequests(a -> a
              .requestMatchers("/actuator/health/**").permitAll()
              .requestMatchers("/api/public/**").permitAll()
              .requestMatchers("/api/admin/**").hasAuthority("SCOPE_ADMIN")
              .requestMatchers("/api/pos/**", "/api/branch/**").hasAnyAuthority("SCOPE_BRANCH", "SCOPE_ADMIN")
              .anyRequest().authenticated())
          .formLogin(f -> f.loginProcessingUrl("/api/auth/login")
              .successHandler((req, res, auth) -> {
                authVersion.markCurrent(req.getSession(false), auth.getName());
                res.setStatus(HttpServletResponse.SC_NO_CONTENT);
              })
              .failureHandler((req, res, ex) -> res.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
          .logout(l -> l.logoutUrl("/api/auth/logout")
              .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
          .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
          .headers(h -> h.frameOptions(o -> o.deny()));
        return http.build();
    }

    @Bean PasswordEncoder passwordEncoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }

    /** Spring only creates the CSRF token when something asks for it; this makes sure the cookie is always sent. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            chain.doFilter(req, res);
        }
    }
}
