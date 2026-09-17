package dev.yolbert.auth_service.repository;

import dev.yolbert.auth_service.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByEmail(String email);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    boolean existsByFriendCode(String friendCode);

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailAndDeletedAtIsNull(String email);
}
