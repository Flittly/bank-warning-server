package com.yangtze.bankwarning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BankWarningServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankWarningServerApplication.class, args);
    }
}
