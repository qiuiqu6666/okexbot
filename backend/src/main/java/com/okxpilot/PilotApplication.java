package com.okxpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PilotApplication {
    public static void main(String[] args) { SpringApplication.run(PilotApplication.class, args); }
}
