package com.example.mealprep.feedback.testdata;

import org.springframework.lang.NonNull;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A {@link TransactionTemplate} that runs the callback inline with no real transaction manager —
 * for unit tests of the feedback bridges (which wrap idempotency writes in a REQUIRES_NEW
 * template). The repository is mocked in those tests, so no actual transaction is needed; this just
 * executes the lambda synchronously and returns its result.
 */
public final class InlineTransactionTemplate extends TransactionTemplate {

  @Override
  public <T> T execute(@NonNull TransactionCallback<T> action) {
    return action.doInTransaction(new SimpleTransactionStatus());
  }
}
