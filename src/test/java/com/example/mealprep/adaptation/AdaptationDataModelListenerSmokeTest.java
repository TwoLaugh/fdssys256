package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.domain.service.internal.AdaptationDataModelListener;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationImportListener;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Round-7 propagation rule check: every {@code @TransactionalEventListener} method on the
 * data-model / import listeners must also carry {@code @Transactional(REQUIRES_NEW)}.
 *
 * <p>Per {@code decisions/0010 §round-7}, a violation here would fail-fast at Spring context-load
 * with {@code "@TransactionalEventListener method must not be annotated with @Transactional unless
 * when declared as REQUIRES_NEW or NOT_SUPPORTED"} — blocking every IT in the project. This
 * lightweight reflection check surfaces drift earlier than that.
 */
class AdaptationDataModelListenerSmokeTest {

  @Test
  void every_listener_method_uses_requires_new_propagation() {
    assertRule(AdaptationDataModelListener.class);
    assertRule(AdaptationImportListener.class);
  }

  private static void assertRule(Class<?> clazz) {
    for (Method m : clazz.getDeclaredMethods()) {
      TransactionalEventListener tel = m.getAnnotation(TransactionalEventListener.class);
      if (tel == null) {
        continue;
      }
      Transactional tx = m.getAnnotation(Transactional.class);
      assertThat(tx)
          .as("method %s.%s must be @Transactional", clazz.getSimpleName(), m.getName())
          .isNotNull();
      assertThat(tx.propagation())
          .as(
              "method %s.%s @Transactional propagation must be REQUIRES_NEW or NOT_SUPPORTED"
                  + " per round-7",
              clazz.getSimpleName(), m.getName())
          .isIn(Propagation.REQUIRES_NEW, Propagation.NOT_SUPPORTED);
    }
  }
}
