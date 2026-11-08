package org.lnu.timetable.bench;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Statement;
import org.lnu.timetable.framework.metadata.EntityMetadataRegistry;
import org.lnu.timetable.framework.metadata.PermissionTypeGraph;
import org.lnu.timetable.security.PermissionGraphRepository;
import org.lnu.timetable.security.PermissionRepository;
import org.lnu.timetable.security.PermissionService;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wiring for the benchmark and the consistency tests, outside Spring: the application's own
 * metadata registry, type graph, repositories and PermissionService over one R2DBC pool configured
 * as application.properties configures it (initial 10, max 512). A thin proxy counts every
 * Statement.execute() that reaches the pool, so both strategies are counted by the same
 * instrumentation at the same boundary.
 */
public final class BenchEnv implements AutoCloseable {

    public final AtomicLong statements = new AtomicLong();
    public final EntityMetadataRegistry registry;
    public final PermissionTypeGraph typeGraph;
    public final ConnectionPool pool;
    public final DatabaseClient db;
    public final PermissionGraphRepository graphRepo;
    public final PermissionRepository permissionRepo;
    public final PermissionService service;

    public BenchEnv(String database) {
        this(database, 10, 512);
    }

    public BenchEnv(String database, int initial, int max) {
        registry = new EntityMetadataRegistry();
        typeGraph = new PermissionTypeGraph(registry);
        PostgresqlConnectionFactory pg = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
            .host(System.getProperty("pg.host", "localhost"))
            .port(Integer.getInteger("pg.port", 5432))
            .database(database)
            .username(System.getProperty("pg.user", "postgres"))
            .password(System.getProperty("pg.password", "postgres")).build());
        pool = new ConnectionPool(ConnectionPoolConfiguration.builder(pg)
            .initialSize(initial).maxSize(max).maxAcquireTime(Duration.ofSeconds(60)).build());
        db = DatabaseClient.create(new Counting(pool, statements));
        graphRepo = new PermissionGraphRepository(db);
        permissionRepo = new PermissionRepository(db);
        service = new PermissionService(registry, typeGraph, graphRepo, permissionRepo);
    }

    public List<Long> ids(Class<?> entityClass, int limit) {
        var md = registry.getMetadata(entityClass);
        String sql = "SELECT " + md.keyColumn() + " AS id FROM " + md.tableName() + " ORDER BY " + md.keyColumn()
            + (limit > 0 ? " LIMIT " + limit : "");
        return db.sql(sql).map(r -> ((Number) r.get("id")).longValue()).all().collectList().block();
    }

    @Override
    public void close() {
        pool.dispose();
    }

    /** Counts executions; everything else is passed through untouched. */
    static final class Counting implements ConnectionFactory {
        private final ConnectionFactory delegate;
        private final AtomicLong counter;

        Counting(ConnectionFactory delegate, AtomicLong counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override
        public Publisher<? extends Connection> create() {
            return Mono.from(delegate.create()).map(c -> (Connection) wrap(c, Connection.class, (proxy, m, args) -> {
                Object out = invoke(c, m, args);
                if (m.getName().equals("createStatement")) {
                    Statement s = (Statement) out;
                    return wrap(s, Statement.class, (p2, m2, a2) -> {
                        if (m2.getName().equals("execute")) counter.incrementAndGet();
                        Object r = invoke(s, m2, a2);
                        return r == s ? p2 : r;
                    });
                }
                return out;
            }));
        }

        @Override
        public ConnectionFactoryMetadata getMetadata() {
            return delegate.getMetadata();
        }

        private static Object invoke(Object target, java.lang.reflect.Method m, Object[] args) throws Throwable {
            try {
                return m.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private static Object wrap(Object target, Class<?> main, InvocationHandler h) {
            Set<Class<?>> ifaces = new LinkedHashSet<>();
            ifaces.add(main);
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                for (Class<?> i : c.getInterfaces()) if (java.lang.reflect.Modifier.isPublic(i.getModifiers())) ifaces.add(i);
            }
            return Proxy.newProxyInstance(BenchEnv.class.getClassLoader(), new ArrayList<>(ifaces).toArray(new Class<?>[0]), h);
        }
    }
}
