package dev.yolbert.auth_service.repository;

import dev.yolbert.auth_service.domain.entity.Otp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OtpRepository extends JpaRepository<Otp, UUID> {
}
