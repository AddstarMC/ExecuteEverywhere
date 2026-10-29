# ExecuteEverywhere

Runs a console command on every connected Paper server at once, using Redis pub/sub. Type `/ee <command>` on any server and every server running the plugin (including the one you typed it on) runs `<command>` from its console.

Originally written by vemacs. Version 2.0 is AddstarMC's maintained rewrite: it uses Redis Sentinel for failover and recovers by itself from Redis restarts, failovers and network problems.

Supports Paper **1.20.4 through 26.x**.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/ee <command>` (alias `/executeeverywhere`) | `executeeverywhere.use` | Sends `<command>` to every server, where it runs from the console. A leading `/` is optional. You get a reply saying how many servers received it. |
| `/eestatus` | `executeeverywhere.status` | Shows the Redis mode, current master, channel, whether this server's subscription is connected (and for how long, and when Redis last replied), and the last error. |

What `/ee` tells you:

- **Sent /cmd for execution on N server(s)**: N is the number of subscribers Redis delivered it to. If N is lower than the number of servers you expect, some are disconnected. Run `/eestatus` on them.
- **Sent /cmd but no servers are listening**: Redis accepted it but nothing was subscribed, so it ran nowhere.
- **Redis is not responding, still trying to send /cmd...**: shown after 2 seconds if Redis is unreachable (for example during a failover). The plugin keeps trying for `publish-retry-seconds`.
- **Could not reach Redis, /cmd was not sent**: it gave up. The command did not run anywhere, so it is safe to send again.
- **Lost the Redis connection while sending /cmd. It may or may not have run**: the connection failed after the command went out. It is not retried automatically because it could run twice. Check before sending it again.

Every send is logged on the sending server's console with the sender's name and the receiver count.

## Permissions

| Permission | Default | Description |
|---|---|---|
| `executeeverywhere.use` | op | Use `/ee`. This runs console commands on every server, so treat it like console access. |
| `executeeverywhere.status` | op | Use `/eestatus`. |

## Configuration

`plugins/ExecuteEverywhere/config.yml`:

| Key | Default | Description |
|---|---|---|
| `channel` | `ee` | Redis pub/sub channel. Every server must use the same one. `ee` is what the original plugin used. |
| `redis.mode` | `sentinel` | `sentinel` (follows failovers) or `standalone` (one fixed Redis node). |
| `redis.sentinel.master-name` | `mymaster` | The master name the sentinels monitor. |
| `redis.sentinel.nodes` | `127.0.0.1:26379` | List of sentinels as `host:port`. List all of them; any one reachable is enough. |
| `redis.sentinel.username` / `password` | blank | Credentials for the sentinels themselves, if they require auth. |
| `redis.standalone.host` / `port` | `127.0.0.1` / `6379` | Redis node, used only in standalone mode. |
| `redis.username` / `password` | blank | Credentials for the Redis data nodes. Password only means classic `AUTH <password>`; set a username too for ACL users. |
| `redis.connect-timeout-ms` | `2000` | Connect timeout for Redis and the sentinels. |
| `redis.socket-timeout-ms` | `2000` | Read timeout for normal commands (not the subscription, which is checked by the health check instead). |
| `resilience.health-check-interval-seconds` | `5` | How often the subscription is PINGed and the current master is checked. |
| `resilience.health-check-timeout-seconds` | `15` | Reconnect if the subscription gets no reply for this long. Must be larger than the interval. |
| `resilience.reconnect-min-delay-ms` | `500` | First reconnect delay. Doubles after each failed attempt. |
| `resilience.reconnect-max-delay-ms` | `15000` | Longest reconnect delay. |
| `resilience.publish-retry-seconds` | `30` | How long `/ee` keeps trying to reach Redis before giving up. `0` disables retries. |

Changes need a server restart.

### Upgrading from 1.x

The 1.x config (`ip`, `port`, `password` at the top level) is still read. The plugin connects in standalone mode and logs a warning at startup. Replace it with the new format to get Sentinel failover. The channel and message format are unchanged, so 1.x and 2.0 servers can send commands to each other during a staged rollout.

The BungeeCord side of 1.x and its `/eb` command have been removed.

## How it stays connected

- **Sentinel**: the plugin asks the sentinels where the master is and follows `+switch-master` announcements. When the master moves, the subscription is moved to the new master straight away, including when the old master is still up as a replica (where the old connection would otherwise stay open and quietly stop receiving commands).
- **Automatic reconnect**: if the subscription drops for any reason, it reconnects with backoff (`reconnect-min-delay-ms` doubling up to `reconnect-max-delay-ms`). A pending reconnect is cut short as soon as the sentinels report a new master. 1.x gave up permanently after the first failure, which is why `/ee` stopped working after a Redis restart.
- **Health check**: the subscription connection is PINGed every `health-check-interval-seconds`. If Redis stops answering (a frozen node, a dropped network path, a half-open TCP connection) it is closed and reopened after `health-check-timeout-seconds`. Without this, a dead connection can look open forever.
- **Startup with Redis down**: the server starts normally and the plugin keeps trying in the background.
- **Safe publishing**: each publish first PINGs its connection, so a dead pooled connection fails before the command is sent and can be retried. Once a command may have reached Redis, it is never retried, so it can't run twice. Commands from one server are published in the order they were typed.

## Limitations

- Redis pub/sub does not store messages. A server that is disconnected when a command is sent never runs it, even after reconnecting. The receiver count in the `/ee` reply is how you notice this.
- The receiver count counts subscriptions on the Redis master, which includes 1.x servers and anything else subscribed to the channel.
- Anyone who can publish to the channel on Redis can run console commands on every server. Keep Redis on a private network and use auth or ACLs.
- When every sentinel is unreachable, Jedis logs a warning with a stack trace for each sentinel on each connection attempt (at most one round every `reconnect-max-delay-ms`). It stops once a sentinel is reachable.

## Building

Requires JDK 25 (Gradle uses its toolchain support and won't build with any other JDK):

```
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./gradlew build
```

The plugin jar is `build/libs/ExecuteEverywhere-<version>.jar` (Jedis is shaded in). The `-plain` jar is the unshaded build output and won't run on its own.

The code compiles against the Paper 1.20.4 API so nothing newer gets used by accident, and targets Java 21 bytecode, because Paper 1.20.4's class rewriter can't read Java 25 class files. Both can be raised once the last 1.20.4 server is retired.
