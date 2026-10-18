package centuryroad.history;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request that cannot get a database connection must fail in seconds, not sit through
 * HikariCP's 30-second default while the gateway's connection behind it hangs too.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DataSourceTimeoutTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void waitingForAConnectionIsBoundedToFiveSeconds() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getConnectionTimeout()).isEqualTo(5000);
    }
}
