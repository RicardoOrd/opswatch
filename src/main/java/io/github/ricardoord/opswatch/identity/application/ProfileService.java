package io.github.ricardoord.opswatch.identity.application;

import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.identity.domain.UserRepository;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The account of the signed-in user. Editing it arrives with OW-045. */
@Service
public class ProfileService {

    private final UserRepository users;

    public ProfileService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public User get(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("user", userId));
    }
}
