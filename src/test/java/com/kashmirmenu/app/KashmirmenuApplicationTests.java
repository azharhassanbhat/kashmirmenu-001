package com.kashmirmenu.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles(resolver = TestProfiles.class)
class KashmirmenuApplicationTests {

	@Test
	void contextLoads() {
	}

}
