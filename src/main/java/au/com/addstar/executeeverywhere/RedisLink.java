package au.com.addstar.executeeverywhere;

import redis.clients.jedis.Connection;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.providers.ConnectionProvider;
import redis.clients.jedis.providers.PooledConnectionProvider;
import redis.clients.jedis.providers.SentineledConnectionProvider;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the Redis connections: a long-lived subscription that reconnects on its own, a watchdog that
 * detects dead or stale subscriptions, and a publisher that rides out short outages.
 */
final class RedisLink {
    private static final long PUBLISH_MAX_DELAY_MS = 2000;

    private final Settings settings;
    private final Logger log;
    private final Consumer<String> handler;

    private final Object providerLock = new Object();
    // Written under providerLock, read without it so the main thread never waits on a slow connect.
    private volatile ConnectionProvider provider;

    private volatile boolean running;
    private Thread subscriberThread;
    private ScheduledExecutorService watchdog;
    private ExecutorService publisher;

    private volatile Subscription subscription;
    private volatile String lastError;
    private volatile Instant lastErrorAt;

    RedisLink(Settings settings, Logger log, Consumer<String> handler) {
        this.settings = settings;
        this.log = log;
        this.handler = handler;
    }

    void start() {
        running = true;
        // Commands are published one at a time, so they arrive in the order they were sent.
        publisher = Executors.newSingleThreadExecutor(daemon("ExecuteEverywhere-publisher"));
        watchdog = Executors.newSingleThreadScheduledExecutor(daemon("ExecuteEverywhere-watchdog"));
        long interval = settings.healthCheckInterval().toMillis();
        watchdog.scheduleWithFixedDelay(this::healthCheck, interval, interval, TimeUnit.MILLISECONDS);
        subscriberThread = Thread.ofPlatform().daemon().name("ExecuteEverywhere-subscriber").start(this::subscribeLoop);
    }

    void stop() {
        running = false;
        Subscription current = subscription;
        if (current != null) {
            current.kill();
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
            try {
                subscriberThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        if (publisher != null) {
            publisher.shutdownNow();
        }
        synchronized (providerLock) {
            if (provider != null) {
                try {
                    provider.close();
                } catch (Exception e) {
                    log.log(Level.FINE, "Error closing Redis connections", e);
                }
                provider = null;
            }
        }
    }

    /**
     * Publishes a command. Completes with the number of subscribers that received it, or exceptionally
     * with a {@link PublishException}.
     */
    CompletableFuture<Long> publish(String command) {
        return CompletableFuture.supplyAsync(() -> doPublish(command), publisher);
    }

    Status status() {
        Subscription current = subscription;
        HostAndPort master = currentMaster();
        if (current == null || !current.confirmed) {
            return new Status(false, null, null, null, master, lastError, lastErrorAt);
        }
        Duration sincePong = Duration.ofNanos(System.nanoTime() - current.lastPongNanos);
        return new Status(true, current.node, current.since, sincePong, master, lastError, lastErrorAt);
    }

    record Status(boolean subscribed, HostAndPort node, Instant since, Duration sinceLastReply,
                  HostAndPort master, String lastError, Instant lastErrorAt) {
    }

    static final class PublishException extends RuntimeException {
        private final boolean maybeSent;

        PublishException(String message, Throwable cause, boolean maybeSent) {
            super(message, cause);
            this.maybeSent = maybeSent;
        }

        /** True if the command may have reached Redis before the failure, so it might have run. */
        boolean maybeSent() {
            return maybeSent;
        }
    }

    private void subscribeLoop() {
        long delay = settings.reconnectMinDelay().toMillis();
        while (running) {
            Subscription sub = null;
            try {
                HostAndPort node = currentNode(provider());
                Connection connection = verifiedConnection();
                sub = new Subscription(connection, node);
                subscription = sub;
                if (!running) {
                    // stop() ran before it could see this subscription.
                    sub.kill();
                }
                try (connection) {
                    sub.proceed(connection, settings.channel());
                }
            } catch (Exception e) {
                if (running) {
                    recordError(e);
                }
            } finally {
                subscription = null;
            }
            if (!running) {
                break;
            }
            if (sub != null && sub.confirmed) {
                // It was working, so try again straight away rather than continuing an old backoff.
                delay = settings.reconnectMinDelay().toMillis();
                log.warning("Lost subscription to Redis (" + lastError + "). Reconnecting.");
            } else {
                log.warning("Could not subscribe to Redis (" + lastError + "). Retrying in "
                        + Math.round(delay / 1000.0 * 10) / 10.0 + "s.");
            }
            if (!sleepUnlessMasterChanges(jitter(delay), currentMaster())) {
                break;
            }
            delay = Math.min(delay * 2, settings.reconnectMaxDelay().toMillis());
        }
    }

    private void healthCheck() {
        try {
            Subscription sub = subscription;
            if (sub == null) {
                return;
            }
            long silentNanos = System.nanoTime() - sub.lastPongNanos;
            if (silentNanos > settings.healthCheckTimeout().toNanos()) {
                log.warning("No reply from Redis at " + sub.node + " for "
                        + TimeUnit.NANOSECONDS.toSeconds(silentNanos) + "s. Reconnecting.");
                lastError = "no reply from " + sub.node;
                lastErrorAt = Instant.now();
                sub.kill();
                return;
            }
            if (!sub.confirmed) {
                return;
            }
            HostAndPort master = currentMaster();
            if (master != null && !master.equals(sub.node)) {
                // The old master may still be up as a replica, so the connection would not break by itself.
                log.info("Redis master moved from " + sub.node + " to " + master + ". Resubscribing.");
                sub.kill();
                return;
            }
            sub.ping();
        } catch (Exception e) {
            Subscription sub = subscription;
            if (sub != null) {
                sub.kill();
            }
            log.log(Level.FINE, "Health check failed", e);
        }
    }

    private long doPublish(String command) {
        long deadline = System.nanoTime() + settings.publishRetry().toNanos();
        long delay = settings.reconnectMinDelay().toMillis();
        while (true) {
            Connection connection;
            try {
                connection = verifiedConnection();
            } catch (Exception e) {
                recordError(e);
                // Nothing has been sent yet, so retrying cannot run the command twice.
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (!running || remaining <= 0
                        || !sleepUnlessMasterChanges(Math.min(jitter(delay), remaining), currentMaster())) {
                    throw new PublishException("Redis unavailable: " + lastError, e, false);
                }
                // Someone is waiting on this, so keep the retries frequent.
                delay = Math.min(delay * 2, Math.min(PUBLISH_MAX_DELAY_MS, settings.reconnectMaxDelay().toMillis()));
                continue;
            }
            try (connection) {
                connection.sendCommand(Protocol.Command.PUBLISH, settings.channel(), command);
                return connection.getIntegerReply();
            } catch (Exception e) {
                // The command may already be on its way to other servers, so it is never retried.
                recordError(e);
                throw new PublishException("Connection failed while sending: " + lastError, e, true);
            }
        }
    }

    /**
     * Borrows a connection and PINGs it. Pooled connections can be dead after a Redis restart, and this
     * makes them fail here, before anything is sent.
     */
    private Connection verifiedConnection() {
        Connection connection = provider().getConnection();
        try {
            connection.ping();
            return connection;
        } catch (RuntimeException e) {
            connection.setBroken();
            try {
                connection.close();
            } catch (RuntimeException closeError) {
                // The pool can fail to replace a broken connection while Redis is down. Keep the original error.
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    private ConnectionProvider provider() {
        synchronized (providerLock) {
            if (!running) {
                throw new IllegalStateException("shutting down");
            }
            if (provider == null) {
                // Created lazily and retried, so the plugin still starts when Redis or the sentinels are down.
                ConnectionPoolConfig pool = new ConnectionPoolConfig();
                pool.setMaxTotal(8);
                pool.setMaxIdle(4);
                pool.setMinIdle(0);
                pool.setMaxWait(Duration.ofMillis(settings.connectTimeoutMs()));
                provider = switch (settings.mode()) {
                    case SENTINEL -> new SentineledConnectionProvider(settings.masterName(),
                            settings.nodeClientConfig(), pool, settings.sentinels(), settings.sentinelClientConfig());
                    case STANDALONE -> new PooledConnectionProvider(settings.standalone(),
                            settings.nodeClientConfig(), pool);
                };
            }
            return provider;
        }
    }

    /** The master according to the sentinels, or null when not using Sentinel. */
    private HostAndPort currentMaster() {
        return provider instanceof SentineledConnectionProvider sentinel ? sentinel.getCurrentMaster() : null;
    }

    private HostAndPort currentNode(ConnectionProvider p) {
        return p instanceof SentineledConnectionProvider sentinel ? sentinel.getCurrentMaster() : settings.standalone();
    }

    private void recordError(Exception e) {
        String message = e.getMessage();
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().equals(message)) {
            message = message + ": " + cause.getMessage();
        }
        lastError = message == null ? e.getClass().getSimpleName() : message;
        lastErrorAt = Instant.now();
    }

    private static long jitter(long delay) {
        return (long) (delay * ThreadLocalRandom.current().nextDouble(0.8, 1.2));
    }

    /**
     * Sleeps for the backoff delay, but wakes early if the sentinels announce a new master, so the
     * subscription comes back as soon as a failover completes. Returns false if interrupted.
     */
    private boolean sleepUnlessMasterChanges(long millis, HostAndPort master) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            long left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
            if (left <= 0) {
                return true;
            }
            if (!sleep(Math.min(100, left))) {
                return false;
            }
            HostAndPort now = currentMaster();
            if (now != null && !now.equals(master)) {
                log.info("Redis master is now " + now + ". Reconnecting.");
                return true;
            }
        }
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ThreadFactory daemon(String name) {
        return Thread.ofPlatform().daemon().name(name).factory();
    }

    private final class Subscription extends JedisPubSub {
        final Connection connection;
        final HostAndPort node;
        final Instant since = Instant.now();
        volatile long lastPongNanos = System.nanoTime();
        volatile boolean confirmed;

        Subscription(Connection connection, HostAndPort node) {
            this.connection = connection;
            this.node = node;
        }

        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            confirmed = true;
            lastPongNanos = System.nanoTime();
            log.info("Subscribed to channel '" + channel + "' on Redis " + node + ".");
        }

        @Override
        public void onMessage(String channel, String message) {
            handler.accept(message);
        }

        @Override
        public void onPong(String pattern) {
            lastPongNanos = System.nanoTime();
        }

        /** Closes the socket so the blocked subscriber thread fails and reconnects. */
        void kill() {
            connection.setBroken();
            try {
                connection.forceDisconnect();
            } catch (IOException | JedisConnectionException ignored) {
                // Already closed.
            }
        }
    }
}
