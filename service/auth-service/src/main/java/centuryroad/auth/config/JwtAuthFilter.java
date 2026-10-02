package centuryroad.auth.config;

import centuryroad.auth.security.AppPrincipal;
import centuryroad.auth.security.JwtVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the bearer token, verifies it, and populates the SecurityContext -
 * offline,
 * without ever calling another service. A missing or invalid token simply
 * leaves the
 * context empty rather than rejecting the request here: SecurityConfig's
 * .anyRequest().authenticated() is what turns "nobody authenticated" into a
 * 401, via
 * ApiAuthenticationEntryPoint, on whichever routes actually require it.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtVerifier jwtVerifier;

    public JwtAuthFilter(JwtVerifier jwtVerifier) {
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            AppPrincipal principal = jwtVerifier.verify(header.substring(PREFIX.length()));
            if (principal != null) {
                List<GrantedAuthority> authorities = List
                        .of(new SimpleGrantedAuthority(principal.role().toAuthority()));
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }
}
