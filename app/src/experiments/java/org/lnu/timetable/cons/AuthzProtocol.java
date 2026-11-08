package org.lnu.timetable.cons;

import org.lnu.timetable.security.PermissionEvaluator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Prototype of the synchronization protocol proposed in the revised Section 8.6 (experimental; not
 * part of the released application).
 *
 * <ul>
 *   <li>One pre-existing row {@code authz_domain(id = 1, epoch)} stands for the authorization
 *       domain (the whole university: grants on a faculty reach rows in many tables).</li>
 *   <li>Every protected unit of work runs in ONE database transaction (Read Committed) and first
 *       locks that row: {@code FOR UPDATE} if the unit changes authorization state (grants,
 *       memberships, permission-bearing foreign keys or join rows, deletion of a row with
 *       permission descendants), {@code FOR SHARE} otherwise. Share locks are compatible with each
 *       other, so ordinary protected writes do not serialize among themselves; any of them excludes
 *       an authorization change for its whole duration.</li>
 *   <li>The evaluator is created only after the lock is held, so every authorization read in the
 *       unit sees authorization state that cannot change until the unit commits; the check and the
 *       protected write commit together. An authorization change also increments the epoch.</li>
 *   <li>A retry re-runs the whole unit, including lock acquisition and a NEW evaluator; nothing
 *       computed in an aborted attempt survives it.</li>
 * </ul>
 * In the variant {@code allExclusive} every unit takes FOR UPDATE, i.e. the coarse protocol in which
 * all protected writes in the domain are serialized.
 */
public final class AuthzProtocol {

    public enum Mode { SHARED, EXCLUSIVE }

    private final ConsEnv env;
    private final boolean allExclusive;
    private final TransactionalOperator tx;
    public final AtomicInteger evaluatorsCreated = new AtomicInteger();

    public AuthzProtocol(ConsEnv env, boolean allExclusive) {
        this.env = env;
        this.allExclusive = allExclusive;
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx = TransactionalOperator.create(env.tm, def);
    }

    public <T> Mono<T> run(Mode mode, long caller, Function<PermissionEvaluator, Mono<T>> body) {
        Mode effective = allExclusive ? Mode.EXCLUSIVE : mode;
        String lock = "SELECT epoch FROM authz_domain WHERE id = 1 " + (effective == Mode.EXCLUSIVE ? "FOR UPDATE" : "FOR SHARE");
        Mono<T> unit = env.db.sql(lock).fetch().one()
            .then(Mono.defer(() -> {
                evaluatorsCreated.incrementAndGet();
                return body.apply(env.service.newEvaluator(caller));
            }))
            .flatMap(result -> mode == Mode.EXCLUSIVE
                ? env.db.sql("UPDATE authz_domain SET epoch = epoch + 1 WHERE id = 1").fetch().rowsUpdated().thenReturn(result)
                : Mono.just(result));
        return tx.transactional(Mono.defer(() -> unit));
    }

    /** A consistent advisory read: all statements of one evaluation in one REPEATABLE READ snapshot. */
    public static <T> Mono<T> snapshot(ConsEnv env, Mono<T> evaluation) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        def.setReadOnly(true);
        return TransactionalOperator.create(env.tm, def).transactional(Mono.defer(() -> evaluation));
    }
}
