package io.github.ricardoord.opswatch.identity.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Expects the normalized email ({@link User#normalizeEmail}). */
    boolean existsByEmail(String email);

    /** Expects the normalized email ({@link User#normalizeEmail}). */
    Optional<User> findByEmail(String email);
}
