package com.whatshapp.demo.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;
import java.util.ArrayList;
import java.util.List;

@Data
@RedisHash("session")
public class UserSession {

    @Id
    private String phone;
    private String city;
    private String language = "en";
    private State state = State.MENU;

    private List<ChatMessage> chatHistory = new ArrayList<>();

    @TimeToLive
    private long ttl = 86400L;

    public UserSession(String phone) {
        this.phone = phone;
    }

    public UserSession() {}
}
