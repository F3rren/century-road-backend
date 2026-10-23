package centuryroad.auth;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.zaxxer.hikari.HikariDataSource;

/**
 * A request that cannot get a database connection must fail in seconds, not sit through
 * HikariCP's 30-second default while the gateway's connection behind it hangs too.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DataSourceTimeoutTest {

	@Autowired
	private DataSource dataSource;

	@Test
	void waitingForAConnectionIsBoundedToFiveSeconds() {
		assertThat(dataSource).isInstanceOf(HikariDataSource.class);
		assertThat(((HikariDataSource) dataSource).getConnectionTimeout()).isEqualTo(5000);
	}

}
