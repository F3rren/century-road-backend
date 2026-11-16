package centuryroad.auth.config;

import centuryroad.auth.security.JwtKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns on the typed settings of AuthProperties, and builds the one bean that may not read them itself:
 * JwtKey lives in the security package, which is meant to be copied into other services as it is and
 * therefore depends on nothing of this one (ArchitectureTest), so it takes the secret as a plain string.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        AuthProperties.Jwt.class,
        AuthProperties.RateLimit.class,
        AuthProperties.BootstrapAdmin.class,
        AuthProperties.PasswordHashing.class })
public class AuthConfiguration {

    @Bean
    public JwtKey jwtKey(AuthProperties.Jwt jwt) {
        return new JwtKey(jwt.secret());
    }
}
