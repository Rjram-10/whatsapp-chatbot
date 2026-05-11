package com.whatshapp.demo.service;

import com.whatshapp.demo.model.UserSession;
import com.whatshapp.demo.repo.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SessionService {
    private final SessionRepository repo;

    public UserSession getOrCreate(String phone) {
        return repo.findById(phone).orElse(new UserSession(phone));
    }

    public void save(UserSession session) {
        repo.save(session);
    }
}
