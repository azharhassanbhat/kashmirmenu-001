package com.kashmirmenu.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;


@SpringBootApplication
@EnableJpaAuditing
public class KashmirmenuApplication {

    public static void main(String[] args) {
        SpringApplication.run(KashmirmenuApplication.class, args);
    }

}
