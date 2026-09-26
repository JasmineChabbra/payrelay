package com.payrelay.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Stands in for the external systems PayRelay integrates with: a KYC registry, a risk engine and
 * four fictional banks. Every one of them can be made slow or unreliable on demand.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BankSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankSimulatorApplication.class, args);
    }

    @Bean
    WebMvcConfigurer cors() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns("http://localhost:*")
                        .allowedMethods("GET", "POST", "PUT", "OPTIONS");
            }
        };
    }
}
