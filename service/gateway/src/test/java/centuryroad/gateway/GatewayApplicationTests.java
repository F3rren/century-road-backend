package centuryroad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"GATEWAY_PORT=0",
		"AUTH_SERVICE_URI=http://localhost:1",
		"HISTORY_SERVICE_URI=http://localhost:1",
		"FRONTEND_ORIGIN=http://localhost:5173"
})
class GatewayApplicationTests {

	@Test
	void contextLoads() {
	}

}
