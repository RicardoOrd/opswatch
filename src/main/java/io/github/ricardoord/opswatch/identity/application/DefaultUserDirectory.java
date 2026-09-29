package io.github.ricardoord.opswatch.identity.application;

import io.github.ricardoord.opswatch.identity.UserDirectory;
import io.github.ricardoord.opswatch.identity.UserSummary;
import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.identity.domain.UserRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultUserDirectory implements UserDirectory {

    private final UserRepository users;

    DefaultUserDirectory(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findById(UUID id) {
        return users.findById(id).map(DefaultUserDirectory::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findByEmail(String email) {
        return users.findByEmail(User.normalizeEmail(email)).map(DefaultUserDirectory::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> findAllById(Collection<UUID> ids) {
        return users.findAllById(ids).stream()
                .map(DefaultUserDirectory::summary)
                .collect(Collectors.toMap(UserSummary::id, Function.identity()));
    }

    private static UserSummary summary(User user) {
        return new UserSummary(user.id(), user.email(), user.displayName());
    }
}
