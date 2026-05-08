package com.example.mealprep.ai.domain.repository;

import com.example.mealprep.ai.domain.entity.PromptTemplate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link PromptTemplate}. Cross-module callers go through {@code
 * PromptTemplateService}.
 */
public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {

  Optional<PromptTemplate> findByNameAndVersion(String name, int version);

  Optional<PromptTemplate> findFirstByNameOrderByVersionDesc(String name);
}
