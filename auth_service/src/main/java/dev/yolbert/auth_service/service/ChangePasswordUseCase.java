package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.domain.entity.Session;
import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.dto.ChangePasswordCommand;
import dev.yolbert.auth_service.repository.SessionRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import dev.yolbert.auth_service.utils.exceptions.SamePasswordException;
import dev.yolbert.auth_service.utils.exceptions.WrongCurrentPasswordException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ChangePasswordUseCase {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionRepository sessionRepository;

    public ChangePasswordUseCase(UserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 SessionRepository sessionRepository) {
        this.userRepository    = userRepository;
        this.passwordEncoder   = passwordEncoder;
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public void execute(UUID userId, UUID currentSessionId, ChangePasswordCommand command) {
        User user = userRepository.findById(userId)
                .orElseThrow(WrongCurrentPasswordException::new);

        if (!passwordEncoder.matches(command.getCurrentPassword(), user.getPasswordHash())) {
            throw new WrongCurrentPasswordException();
        }

        if (passwordEncoder.matches(command.getNewPassword(), user.getPasswordHash())) {
            throw new SamePasswordException();
        }

        user.setPasswordHash(passwordEncoder.encode(command.getNewPassword()));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        List<Session> activeSessions = sessionRepository.findAllByUserIdAndRevokedFalse(userId);
        OffsetDateTime now = OffsetDateTime.now();
        for (Session session : activeSessions) {
            if (currentSessionId == null || !session.getId().equals(currentSessionId)) {
                session.setRevoked(true);
                session.setRevokedAt(now);
                session.setUpdatedAt(now);
            }
        }
        sessionRepository.saveAll(activeSessions);
    }
}
