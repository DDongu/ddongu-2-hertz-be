package com.hertz.hertz_be;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HertzBeApplication {

	public static void main(String[] args) {
		SpringApplication.run(HertzBeApplication.class, args);
	}

}
