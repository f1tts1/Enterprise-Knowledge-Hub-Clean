package com.example.ekb.security;

import java.util.Optional;

public interface AuthUserCache {

    Optional<CachedAuthUser> get(Long userId);

    void put(CachedAuthUser user);

    void evict(Long userId);
}
