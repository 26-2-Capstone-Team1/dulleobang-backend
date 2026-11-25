package com.dulleobang.auth.domain;

public record SocialIdentity(String provider, String subject, String email, String displayName) {}
