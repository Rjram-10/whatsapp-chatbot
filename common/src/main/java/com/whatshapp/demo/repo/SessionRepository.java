package com.whatshapp.demo.repo;

import com.whatshapp.demo.model.UserSession;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SessionRepository extends CrudRepository<UserSession, String> {}
