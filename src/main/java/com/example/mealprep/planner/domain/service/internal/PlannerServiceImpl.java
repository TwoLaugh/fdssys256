package com.example.mealprep.planner.domain.service.internal;

import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.mapper.PlanMapper;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 01a impl of {@link PlanQueryService}. Lives in {@code domain.service.internal} per the style
 * guide — package-private would normally apply, but {@code @Service} requires a class that Spring
 * can instantiate via component scan, and {@code public} is needed for the proxy generation to
 * inspect the class from outside the package. {@link PlanQueryService} is the cross-module surface;
 * this impl is the only injectable bean.
 *
 * <p>Future tickets (01b/01j) will add {@code PlannerService} (write surface) to this same class so
 * the planner module has one impl backing both interfaces, per the style guide.
 *
 * <p>The read path uses the lazy-touch-inside-{@code @Transactional} pattern to avoid Hibernate's
 * {@code MultipleBagFetchException} on three chained {@code @OneToMany List<>} collections (see
 * {@code PlanRepository} javadoc). The mapper runs inside the same transaction so all child
 * collections materialise while the session is open — no {@code LazyInitializationException} on the
 * controller's response-serialise path.
 */
@Service
public class PlannerServiceImpl implements PlanQueryService {

  private final PlanRepository planRepository;
  private final PlanMapper planMapper;

  public PlannerServiceImpl(PlanRepository planRepository, PlanMapper planMapper) {
    this.planRepository = planRepository;
    this.planMapper = planMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PlanDto> getPlanById(UUID planId) {
    return planRepository
        .findById(planId)
        .map(
            plan -> {
              // Force lazy-load of children while the session is open. The chained iteration is the
              // explicit alternative to @EntityGraph (which would trigger MultipleBagFetchException
              // on the three @OneToMany List<> collections).
              plan.getDays()
                  .forEach(
                      day ->
                          day.getSlots()
                              .forEach(
                                  slot -> {
                                    // Touch the optional @OneToOne to force lazy load.
                                    slot.getScheduledRecipe();
                                  }));
              return planMapper.toDto(plan);
            });
  }
}
