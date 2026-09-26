package com.payrelay.api.config;

import com.payrelay.api.web.CorrelationIdFilter;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig implements WebMvcConfigurer {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** HTTP client for the external systems, with strict timeouts so a slow bank can't hang a worker. */
    @Bean
    public RestClient simulatorRestClient(PayRelayProperties props) {
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(props.simulator().connectTimeout())
                .withReadTimeout(props.simulator().readTimeout());
        return RestClient.builder()
                .baseUrl(props.simulator().baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService paymentWorkerPool(PayRelayProperties props) {
        return Executors.newFixedThreadPool(props.worker().threads(), Thread.ofPlatform().name("payment-worker-", 0).factory());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .exposedHeaders(CorrelationIdFilter.HEADER, "Idempotent-Replayed");
    }
}
