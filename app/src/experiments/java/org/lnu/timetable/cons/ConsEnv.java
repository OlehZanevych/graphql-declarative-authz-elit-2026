package org.lnu.timetable.cons;

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
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One "application instance" for the consistency tests: its own pool, the application's own
 * metadata, repositories and PermissionService, a transaction manager over the same connection
 * factory, and a statement gate that can hold one chosen statement until a test releases it. The
 * gate is what makes the interleavings deterministic: a schedule is "run until statement X is about
 * to execute, do something else, then let X go", rather than a sleep that usually works.
 */
public final class ConsEnv implements AutoCloseable {

    /** Holds the first statement whose SQL contains {@code fragment} until {@link #release} completes. */
    public static final class Gate {
        final String fragment;
        final AtomicBoolean armed = new AtomicBoolean(true);
        public final CompletableFuture<Void> reached = new CompletableFuture<>();
        public final CompletableFuture<Void> release = new CompletableFuture<>();

        public Gate(String fragment) {
            this.fragment = fragment;
        }
    }

    public final AtomicLong statements = new AtomicLong();
    public volatile Gate gate;
    public final EntityMetadataRegistry registry;
    public final PermissionTypeGraph typeGraph;
    public final ConnectionPool pool;
    public final ConnectionFactory cf;
    public final DatabaseClient db;
    public final R2dbcTransactionManager tm;
    public final PermissionGraphRepository graphRepo;
    public final PermissionRepository permissionRepo;
    public final PermissionService service;

    public ConsEnv(String database, int max) {
        registry = new EntityMetadataRegistry();
        typeGraph = new PermissionTypeGraph(registry);
        PostgresqlConnectionFactory pg = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
            .host(System.getProperty("pg.host", "localhost"))
            .port(Integer.getInteger("pg.port", 5432))
            .database(database)
            .username(System.getProperty("pg.user", "postgres"))
            .password(System.getProperty("pg.password", "postgres")).build());
        pool = new ConnectionPool(ConnectionPoolConfiguration.builder(pg)
            .initialSize(Math.min(4, max)).maxSize(max).maxAcquireTime(Duration.ofSeconds(30)).build());
        cf = new Gated(pool);
        db = DatabaseClient.create(cf);
        tm = new R2dbcTransactionManager(cf);
        graphRepo = new PermissionGraphRepository(db);
        permissionRepo = new PermissionRepository(db);
        service = new PermissionService(registry, typeGraph, graphRepo, permissionRepo);
    }

    @Override
    public void close() {
        pool.dispose();
    }

    /** Runs the statements in one transaction on one connection (no protocol), and commits. */
    public void inConnectionTx(String... statements) {
        db.inConnection(c -> {
            Mono<?> chain = Mono.from(c.beginTransaction());
            for (String sql : statements) {
                chain = chain.then(Mono.from(c.createStatement(sql).execute()).flatMap(r -> Mono.from(r.getRowsUpdated())));
            }
            return chain.then(Mono.from(c.commitTransaction())).thenReturn(1);
        }).block(Duration.ofSeconds(20));
    }

    final class Gated implements ConnectionFactory {
        private final ConnectionFactory delegate;

        Gated(ConnectionFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Publisher<? extends Connection> create() {
            return Mono.from(delegate.create()).map(c -> (Connection) wrap(c, Connection.class, (proxy, m, args) -> {
                Object out = invoke(c, m, args);
                if (m.getName().equals("createStatement")) {
                    String sql = (String) args[0];
                    Statement s = (Statement) out;
                    return wrap(s, Statement.class, (p2, m2, a2) -> {
                        if (m2.getName().equals("execute")) {
                            statements.incrementAndGet();
                            Gate g = gate;
                            if (g != null && sql.contains(g.fragment) && g.armed.compareAndSet(true, false)) {
                                return Flux.defer(() -> {
                                    g.reached.complete(null);
                                    return Mono.fromFuture(g.release).thenMany(Flux.from(s.execute()));
                                });
                            }
                        }
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
        return Proxy.newProxyInstance(ConsEnv.class.getClassLoader(), new ArrayList<>(ifaces).toArray(new Class<?>[0]), h);
    }
}
