package centuryroad.history;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

// @EnableAsync backs ViewStatsService's @Async methods: view counting runs off the request
// thread, so a database hiccup there can never slow down or fail an on-this-day answer.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class HistoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HistoryServiceApplication.class, args);
    }
}
