package au.com.addstar.executeeverywhere;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisProtocol;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Parsed and validated config.yml.
 */
record Settings(
        String channel,
        Mode mode,
        String masterName,
        Set<HostAndPort> sentinels,
        String sentinelUsername,
        String sentinelPassword,
        HostAndPort standalone,
        String username,
        String password,
        int connectTimeoutMs,
        int socketTimeoutMs,
        Duration healthCheckInterval,
        Duration healthCheckTimeout,
        Duration reconnectMinDelay,
        Duration reconnectMaxDelay,
        Duration publishRetry
) {
    enum Mode { SENTINEL, STANDALONE }

    private static final String CLIENT_NAME = "ExecuteEverywhere";

    static Settings load(FileConfiguration config, Logger log) {
        String channel = requireText(config.getString("channel"), "channel");

        // Pre-2.0 configs only had ip/port/password at the top level.
        if (config.isSet("ip") && !config.isSet("redis")) {
            log.warning("config.yml uses the old pre-2.0 format (ip/port/password). Connecting in standalone mode"
                    + " with no failover support. Replace it with the new format to use Redis Sentinel.");
            return new Settings(channel, Mode.STANDALONE, null, Set.of(), null, null,
                    new HostAndPort(requireText(config.getString("ip"), "ip"), config.getInt("port", 6379)),
                    null, blankToNull(config.getString("password")),
                    2000, 2000, Duration.ofSeconds(5), Duration.ofSeconds(15),
                    Duration.ofMillis(500), Duration.ofSeconds(15), Duration.ofSeconds(30));
        }

        ConfigurationSection redis = section(config, "redis");
        Mode mode = switch (requireText(redis.getString("mode"), "redis.mode").toLowerCase(Locale.ROOT)) {
            case "sentinel" -> Mode.SENTINEL;
            case "standalone" -> Mode.STANDALONE;
            default -> throw new IllegalArgumentException("redis.mode must be 'sentinel' or 'standalone'");
        };

        String masterName = null;
        Set<HostAndPort> sentinels = new LinkedHashSet<>();
        String sentinelUsername = null;
        String sentinelPassword = null;
        HostAndPort standalone = null;
        if (mode == Mode.SENTINEL) {
            ConfigurationSection sentinel = section(redis, "sentinel");
            masterName = requireText(sentinel.getString("master-name"), "redis.sentinel.master-name");
            for (String node : sentinel.getStringList("nodes")) {
                sentinels.add(parseHostAndPort(node, "redis.sentinel.nodes"));
            }
            if (sentinels.isEmpty()) {
                throw new IllegalArgumentException("redis.sentinel.nodes must list at least one sentinel");
            }
            sentinelUsername = blankToNull(sentinel.getString("username"));
            sentinelPassword = blankToNull(sentinel.getString("password"));
        } else {
            ConfigurationSection node = section(redis, "standalone");
            standalone = new HostAndPort(requireText(node.getString("host"), "redis.standalone.host"),
                    node.getInt("port", 6379));
        }

        ConfigurationSection resilience = section(config, "resilience");
        Duration healthInterval = Duration.ofSeconds(positive(resilience, "health-check-interval-seconds"));
        Duration healthTimeout = Duration.ofSeconds(positive(resilience, "health-check-timeout-seconds"));
        if (healthTimeout.compareTo(healthInterval) <= 0) {
            throw new IllegalArgumentException(
                    "resilience.health-check-timeout-seconds must be greater than health-check-interval-seconds");
        }
        Duration reconnectMin = Duration.ofMillis(positive(resilience, "reconnect-min-delay-ms"));
        Duration reconnectMax = Duration.ofMillis(positive(resilience, "reconnect-max-delay-ms"));
        if (reconnectMax.compareTo(reconnectMin) < 0) {
            throw new IllegalArgumentException(
                    "resilience.reconnect-max-delay-ms must not be less than reconnect-min-delay-ms");
        }

        return new Settings(channel, mode, masterName, Set.copyOf(sentinels), sentinelUsername, sentinelPassword,
                standalone,
                blankToNull(redis.getString("username")), blankToNull(redis.getString("password")),
                positive(redis, "connect-timeout-ms"), positive(redis, "socket-timeout-ms"),
                healthInterval, healthTimeout, reconnectMin, reconnectMax,
                Duration.ofSeconds(Math.max(0, resilience.getInt("publish-retry-seconds"))));
    }

    /** Client config for the Redis data nodes (the master). */
    JedisClientConfig nodeClientConfig() {
        return DefaultJedisClientConfig.builder()
                .user(username)
                .password(password)
                .connectionTimeoutMillis(connectTimeoutMs)
                .socketTimeoutMillis(socketTimeoutMs)
                .clientName(CLIENT_NAME)
                .protocol(RedisProtocol.RESP2)
                .build();
    }

    /** Client config for the sentinels themselves. */
    JedisClientConfig sentinelClientConfig() {
        return DefaultJedisClientConfig.builder()
                .user(sentinelUsername)
                .password(sentinelPassword)
                .connectionTimeoutMillis(connectTimeoutMs)
                .socketTimeoutMillis(socketTimeoutMs)
                .clientName(CLIENT_NAME)
                .protocol(RedisProtocol.RESP2)
                .build();
    }

    private static ConfigurationSection section(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("missing section '" + join(parent, path) + "'");
        }
        return section;
    }

    private static int positive(ConfigurationSection section, String path) {
        int value = section.getInt(path);
        if (value <= 0) {
            throw new IllegalArgumentException(join(section, path) + " must be greater than 0");
        }
        return value;
    }

    private static HostAndPort parseHostAndPort(String value, String path) {
        try {
            return HostAndPort.from(value.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(path + " entry '" + value + "' is not in host:port form");
        }
    }

    private static String requireText(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " must be set");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String join(ConfigurationSection parent, String path) {
        String parentPath = parent.getCurrentPath();
        return parentPath == null || parentPath.isEmpty() ? path : parentPath + "." + path;
    }
}
