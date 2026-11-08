package org.lnu.timetable.cons;

import org.lnu.timetable.security.PermissionEvaluator;
import org.lnu.timetable.security.PermissionService;

/**
 * Prototype of conservative request-level invalidation (experimental; not part of the released
 * application). The deployed interceptor puts ONE PermissionEvaluator in the GraphQL context for the
 * whole request. Here the context holds the current evaluator together with a generation number,
 * and any authorization-relevant write performed by the request discards it WHOLESALE: grants,
 * group-derived grants, the effective-level memo and anything still in flight in the old object all
 * become unreachable, and the next question builds a fresh evaluator. Nothing is invalidated
 * selectively, so a change to an ancestor cannot leave a descendant's memo entry behind, and an old
 * computation that completes late writes only into the discarded object.
 */
public final class RequestAuthzContext {

    private final PermissionService service;
    private final long caller;
    private PermissionEvaluator current;
    private int generation;

    public RequestAuthzContext(PermissionService service, long caller) {
        this.service = service;
        this.caller = caller;
    }

    public synchronized PermissionEvaluator evaluator() {
        if (current == null) current = service.newEvaluator(caller);
        return current;
    }

    public synchronized int generation() {
        return generation;
    }

    /** Called after every authorization-relevant write in this request, and after a rollback. */
    public synchronized void authorizationStateChanged() {
        current = null;
        generation++;
    }
}
