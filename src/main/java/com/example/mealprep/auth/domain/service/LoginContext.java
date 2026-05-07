package com.example.mealprep.auth.domain.service;

/**
 * Per-request context the auth service needs but the {@code LoginRequest} body cannot carry.
 * Currently sourced from servlet headers in the controller.
 */
public record LoginContext(String sourceIp, String userAgent) {}
