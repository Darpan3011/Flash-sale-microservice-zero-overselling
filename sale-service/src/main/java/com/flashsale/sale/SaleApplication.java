package com.flashsale.sale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SaleApplication {
    public static void main(String[] args) {
        SpringApplication.run(SaleApplication.class, args);
    }
}
