package com.example.mealprep.preference.domain.repository;

import com.example.mealprep.preference.domain.entity.HardConstraints;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link HardConstraints}. Package-private at the package level —
 * cross-module callers go through {@code PreferenceQueryService}/{@code PreferenceUpdateService}.
 *
 * <p>The two child-aware methods previously used {@code @EntityGraph} to fetch all three
 * {@code @OneToMany List<…>} collections in one JOIN; Hibernate 6 rejects this with {@code
 * MultipleBagFetchException} (you can fetch only one bag at a time). We accept lazy-loading inside
 * the {@code @Transactional} boundary instead — the hot-path filter service lives in 01b, where it
 * can fetch children in a single batched query if profiling demands.
 */
public interface HardConstraintsRepository extends JpaRepository<HardConstraints, UUID> {

  Optional<HardConstraints> findByUserId(UUID userId);

  Optional<HardConstraints> findWithChildrenByUserId(UUID userId);

  List<HardConstraints> findWithChildrenByUserIdIn(List<UUID> userIds);
}
