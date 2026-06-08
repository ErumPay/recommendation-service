package com.erumpay.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:recommendation_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class RecommendationServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
