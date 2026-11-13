package centuryroad.history.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The one clock the application reads the time from. Anything that needs today's date or the current
 * instant takes a Clock instead of asking the system, so a test can hand it a clock that only moves
 * when it says so. It is a configuration of its own, not part of the Wikipedia client's: the time
 * is nobody's detail, and a test of one slice of the application can import it without the HTTP stack.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
