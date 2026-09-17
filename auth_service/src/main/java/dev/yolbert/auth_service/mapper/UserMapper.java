package dev.yolbert.auth_service.mapper;

import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.dto.UserResponseData;

public final class UserMapper {

    private UserMapper() {}

    public static UserResponseData toResponseData(User user) {
        return UserResponseData.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .friendCode(user.getFriendCode())
                .photoUrl(user.getPhotoUrl())
                .verified(user.getVerifiedAt() != null)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
