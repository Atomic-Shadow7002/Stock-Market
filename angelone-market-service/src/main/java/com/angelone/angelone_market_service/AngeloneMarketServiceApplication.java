package com.angelone.angelone_market_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication
@EnableScheduling
public class AngeloneMarketServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AngeloneMarketServiceApplication.class, args);
	}

}
