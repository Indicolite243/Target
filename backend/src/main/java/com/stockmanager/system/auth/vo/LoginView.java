package com.stockmanager.system.auth.vo;

public record LoginView(String accessToken, String tokenType, long expiresIn, UserView user) {
}
