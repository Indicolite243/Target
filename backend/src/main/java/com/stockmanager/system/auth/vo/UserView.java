package com.stockmanager.system.auth.vo;

import java.util.List;

public record UserView(String userId, String username, String displayName,
                       List<String> roles, List<String> permissions, String status) {
}
