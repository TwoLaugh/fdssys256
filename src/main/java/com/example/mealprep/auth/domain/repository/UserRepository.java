package com.example.mealprep.auth.domain.repository;

import com.example.mealprep.auth.domain.entity.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link User}.
 *
 * <p>Package-private — cross-module access goes through {@code AuthQueryService} / {@code
 * AuthUpdateService}, never this interface directly. {@code findByUsernameNormalised
 * AndDeletedAtIsNull} is the canonical "is this user authenticatable?" lookup.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByUsernameNormalised(String usernameNormalised);

  Optional<User> findByUsernameNormalisedAndDeletedAtIsNull(String usernameNormalised);

  boolean existsByUsernameNormalised(String usernameNormalised);

  List<User> findByIdIn(Collection<UUID> ids);
}
