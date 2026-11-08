package org.lnu.timetable.bench;

import org.lnu.timetable.framework.metadata.EntityMetadata;
import org.lnu.timetable.framework.metadata.EntityMetadataRegistry;
import org.lnu.timetable.framework.metadata.PermissionJoinParentEdge;
import org.lnu.timetable.framework.metadata.PermissionParentEdge;
import org.lnu.timetable.security.AccessLevel;
import org.lnu.timetable.security.PermissionGraphRepository;
import org.lnu.timetable.security.PermissionRepository;
import org.lnu.timetable.security.ResourceRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-row baseline: the evaluation strategy the set-at-a-time evaluator replaced (the shape of
 * PermissionService.ancestryOf at commit 205a7d1), adapted only so that the comparison is fair:
 *
 *  - the caller's groups and grants are loaded ONCE per evaluation with the same two statements
 *    the evaluator uses (PermissionRepository.groupIdsForUser, effectiveGrants), not once per row;
 *  - every ancestor lookup goes through the same PermissionGraphRepository methods, i.e. the same
 *    SQL text, driver and pool, with a one-element identifier list;
 *  - the effective level is computed by the same rule (max over covering grants and GLOBAL).
 *
 * What it does NOT do is what makes it the baseline: each requested row climbs its own ancestry,
 * along every path, and nothing is remembered between rows or between paths. Per visited vertex it
 * issues one statement for all foreign-key parent columns of its table (if it has any) and one per
 * join-table parent, as the historical implementation did.
 */
public final class PerRowEvaluator {

    private static final int MAX_DEPTH = 32;   // same backstop as PermissionEvaluator

    private final EntityMetadataRegistry registry;
    private final PermissionGraphRepository graphRepo;
    private final PermissionRepository permissionRepo;

    public PerRowEvaluator(EntityMetadataRegistry registry, PermissionGraphRepository graphRepo,
                           PermissionRepository permissionRepo) {
        this.registry = registry;
        this.graphRepo = graphRepo;
        this.permissionRepo = permissionRepo;
    }

    record Grants(Map<ResourceRef, AccessLevel> byResource, AccessLevel global) {}

    Mono<Grants> grants(Long userId) {
        return permissionRepo.groupIdsForUser(userId).collectList()
            .flatMapMany(groupIds -> permissionRepo.effectiveGrants(userId, groupIds))
            .collectList()
            .map(rows -> {
                Map<ResourceRef, AccessLevel> byResource = new HashMap<>();
                AccessLevel global = null;
                for (PermissionRepository.PermissionRow row : rows) {
                    ResourceRef ref = ResourceRef.of(row.resourceType(), row.resourceId());
                    if (ref.isGlobal()) global = AccessLevel.max(global, row.level());
                    else byResource.merge(ref, row.level(), AccessLevel::max);
                }
                return new Grants(byResource, global);
            });
    }

    /** Same contract as PermissionEvaluator.levelsFor: only ids with some access are present. */
    public Mono<Map<Long, AccessLevel>> levelsFor(Long userId, Class<?> entityClass, Collection<Long> ids) {
        record Answer(Long id, AccessLevel level) {}
        return grants(userId).flatMap(g -> Flux.fromIterable(ids)
            .flatMap(id -> climb(entityClass, id, Set.of(), 0)
                .map(closure -> {
                    AccessLevel level = g.global();
                    for (ResourceRef ref : closure) level = AccessLevel.max(level, g.byResource().get(ref));
                    return new Answer(id, level);
                }))
            .collectList()
            .map(list -> {
                Map<Long, AccessLevel> out = new LinkedHashMap<>();
                for (Answer a : list) if (a.level() != null) out.put(a.id(), a.level());
                return out;
            }));
    }

    /** The closure of (entityClass,id): itself and every ancestor reached along every path. */
    Mono<Set<ResourceRef>> climb(Class<?> entityClass, Long id, Set<ResourceRef> onPath, int depth) {
        EntityMetadata md = registry.getMetadata(entityClass);
        ResourceRef self = new ResourceRef(md.resourceType(), id);
        if (depth >= MAX_DEPTH) return Mono.just(Set.of(self));
        Set<ResourceRef> path = new HashSet<>(onPath);
        path.add(self);

        Map<String, Class<?>> parentByColumn = new LinkedHashMap<>();
        for (PermissionParentEdge e : md.permissionParents()) parentByColumn.put(e.joinColumn(), e.parentEntity());
        List<String> columns = List.copyOf(parentByColumn.keySet());

        Flux<Set<ResourceRef>> viaFk = graphRepo.fetchForeignKeys(md.tableName(), md.keyColumn(), columns, List.of(id))
            .flatMap(fk -> step(parentByColumn.get(fk.column()), fk.parentId(), path, depth));
        Flux<Set<ResourceRef>> viaJoin = Flux.fromIterable(md.permissionJoinParents())
            .flatMap((PermissionJoinParentEdge e) -> graphRepo
                .fetchJoinParents(e.joinTable(), e.selfColumn(), e.parentColumn(), List.of(id))
                .flatMap(j -> step(e.parentEntity(), j.parentId(), path, depth)));

        return Flux.merge(viaFk, viaJoin)
            .collectList()
            .map(parts -> {
                Set<ResourceRef> closure = new HashSet<>();
                closure.add(self);
                for (Set<ResourceRef> p : parts) closure.addAll(p);
                return closure;
            });
    }

    private Mono<Set<ResourceRef>> step(Class<?> parentClass, Long parentId, Set<ResourceRef> path, int depth) {
        EntityMetadata pmd = registry.getMetadata(parentClass);
        if (pmd == null) return Mono.just(Set.of());
        ResourceRef ref = new ResourceRef(pmd.resourceType(), parentId);
        if (path.contains(ref)) return Mono.just(Set.of(ref));        // a cycle on this path: stop
        return climb(parentClass, parentId, path, depth + 1);
    }
}
