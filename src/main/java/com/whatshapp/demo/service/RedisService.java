package com.whatshapp.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisService {

    private final StringRedisTemplate redisTemplate;

    private static final String PREFIX = "user:lang:";

    public void saveUserLanguage(String user, String lang) {
        redisTemplate.opsForValue().set(PREFIX + user, lang);
    }

    public String getUserLanguage(String user) {
        return redisTemplate.opsForValue().get(PREFIX + user);
    }
}
