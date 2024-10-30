package com.example.pingservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.example.pingservice")
@EnableScheduling
public class PingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PingServiceApplication.class, args);
    }

}
