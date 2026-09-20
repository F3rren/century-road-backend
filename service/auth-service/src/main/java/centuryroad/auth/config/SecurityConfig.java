package centuryroad.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The security chain: stateless (a JWT carries everything a request needs, so there is
 * no session to keep), CSRF disabled (CSRF matters for cookie-based sessions a browser
 * attaches automatically; a bearer token in an Authorization header is never attached
 * that way, so there is nothing for CSRF protection to defend here), and every route
 * authenticated except the ones that hand out or renew a token in the first place.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiAuthenticationEntryPoint authenticationEntryPoint;
    private final ApiAccessDeniedHandler accessDeniedHandler;
    private final boolean apiDocsEnabled;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ApiAuthenticationEntryPoint authenticationEntryPoint,
                           ApiAccessDeniedHandler accessDeniedHandler,
                           @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.apiDocsEnabled = apiDocsEnabled;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/auth/**").permitAll();
                    auth.requestMatchers("/actuator/health", "/actuator/prometheus").permitAll();
                    if (apiDocsEnabled) {
                        // The gateway's Swagger UI reads this without a token, the way a browser
                        // loads any page. Only while documentation is on: otherwise the route is
                        // refused like every other, and nothing tells a caller it exists.
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
