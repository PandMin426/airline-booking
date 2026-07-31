package com.airlinebooking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
//@EnableScheduling	// kết hợp với @Schedule để quét định kì
public class AirlineBookingBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AirlineBookingBackendApplication.class, args);
	}

}
