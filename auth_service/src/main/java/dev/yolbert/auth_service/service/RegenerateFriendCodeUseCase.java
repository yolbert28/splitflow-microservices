package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.dto.FriendCodeResponseData;
import dev.yolbert.auth_service.repository.UserRepository;
import dev.yolbert.auth_service.utils.FriendCodeGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class RegenerateFriendCodeUseCase {

    private final UserRepository userRepository;

    public RegenerateFriendCodeUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public FriendCodeResponseData execute(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado."));

        String newFriendCode = generateUniqueFriendCode();
        user.setFriendCode(newFriendCode);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        return FriendCodeResponseData.builder()
                .friendCode(newFriendCode)
                .build();
    }

    private String generateUniqueFriendCode() {
        for (int i = 0; i < 10; i++) {
            String code = FriendCodeGenerator.generate();
            if (!userRepository.existsByFriendCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("No se pudo generar un código de amigo único.");
    }
}
