package com.whatshapp.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.location.LocationClient;

import java.util.concurrent.Executor;

@Configuration
public class HospitalConfig {

    @Bean
    public LocationClient locationClient(
            @Value("${aws.region}") String region,
            @Value("${aws.accessKeyId}") String key,
            @Value("${aws.secretAccessKey}") String secret) {
        return LocationClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(key, secret)))
                .build();
    }

    @Bean("geocodeExecutor")
    public Executor geocodeExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(10);
        ex.setMaxPoolSize(20);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("geocode-");
        ex.initialize();
        return ex;
    }
}
