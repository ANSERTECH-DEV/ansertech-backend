package com.ansertech;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AnsertechApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnsertechApplication.class, args);
    }
}
