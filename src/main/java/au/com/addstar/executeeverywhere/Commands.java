package au.com.addstar.executeeverywhere;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * /ee sends a command to every server, /eestatus shows the Redis connection state.
 */
final class Commands implements TabExecutor {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long SLOW_NOTICE_TICKS = 40;

    private final Plugin plugin;
    private final RedisLink link;
    private final Settings settings;

    Commands(Plugin plugin, RedisLink link, Settings settings) {
        this.plugin = plugin;
        this.link = link;
        this.settings = settings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("eestatus")) {
            sendStatus(sender);
            return true;
        }
        if (args.length == 0) {
            return false;
        }
        String cmd = String.join(" ", args).strip();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        if (cmd.isBlank()) {
            return false;
        }
        send(sender, cmd);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ee") && args.length > 0) {
            // Completes against this server's commands, which is the best guess for what the others have.
            List<String> completions = plugin.getServer().getCommandMap().tabComplete(sender, String.join(" ", args));
            if (completions != null) {
                return completions;
            }
        }
        return List.of();
    }

    private void send(CommandSender sender, String cmd) {
        TagResolver command = Placeholder.unparsed("command", cmd);
        CompletableFuture<Long> result = link.publish(cmd);

        // Only speak up if Redis is slow, e.g. while a failover is in progress.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!result.isDone()) {
                sender.sendMessage(MM.deserialize("<yellow>Redis is not responding, still trying to send "
                        + "<white>/<command></white>...", command));
            }
        }, SLOW_NOTICE_TICKS);

        result.whenComplete((receivers, error) -> runSync(() -> {
            if (error == null) {
                plugin.getLogger().info(sender.getName() + " sent /" + cmd + " (received by " + receivers + ")");
                if (receivers > 0) {
                    sender.sendMessage(MM.deserialize("<green>Sent <white>/<command></white> for execution on "
                            + "<white><count></white> server(s).", command,
                            Placeholder.unparsed("count", String.valueOf(receivers))));
                } else {
                    sender.sendMessage(MM.deserialize("<red>Sent <white>/<command></white> but no servers are "
                            + "listening. Check <white>/eestatus</white>.", command));
                }
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            plugin.getLogger().warning(sender.getName() + " failed to send /" + cmd + ": " + cause.getMessage());
            if (cause instanceof RedisLink.PublishException publish && publish.maybeSent()) {
                sender.sendMessage(MM.deserialize("<red>Lost the Redis connection while sending "
                        + "<white>/<command></white>. It may or may not have run, check before sending it again.",
                        command));
            } else {
                sender.sendMessage(MM.deserialize("<red>Could not reach Redis, <white>/<command></white> was "
                        + "<bold>not</bold> sent. Check <white>/eestatus</white>.", command));
            }
        }));
    }

    private void sendStatus(CommandSender sender) {
        RedisLink.Status status = link.status();
        sender.sendMessage(MM.deserialize("<gold>ExecuteEverywhere status"));
        if (settings.mode() == Settings.Mode.SENTINEL) {
            sender.sendMessage(MM.deserialize("<yellow>Mode: <white>Sentinel</white>, master name <white><name>"
                            + "</white>, <white><count></white> sentinel(s) configured",
                    Placeholder.unparsed("name", settings.masterName()),
                    Placeholder.unparsed("count", String.valueOf(settings.sentinels().size()))));
            sender.sendMessage(MM.deserialize("<yellow>Current master: <white><master>",
                    Placeholder.unparsed("master", status.master() == null ? "unknown" : status.master().toString())));
        } else {
            sender.sendMessage(MM.deserialize("<yellow>Mode: <white>Standalone</white> <white><node>",
                    Placeholder.unparsed("node", settings.standalone().toString())));
        }
        sender.sendMessage(MM.deserialize("<yellow>Channel: <white><channel>",
                Placeholder.unparsed("channel", settings.channel())));
        if (status.subscribed()) {
            sender.sendMessage(MM.deserialize("<yellow>Subscription: <green>connected</green> to <white><node></white>"
                            + " for <white><uptime></white>, last reply <white><reply></white> ago",
                    Placeholder.unparsed("node", String.valueOf(status.node())),
                    Placeholder.unparsed("uptime", format(Duration.between(status.since(), Instant.now()))),
                    Placeholder.unparsed("reply", format(status.sinceLastReply()))));
        } else {
            sender.sendMessage(MM.deserialize("<yellow>Subscription: <red>disconnected</red>, reconnecting. "
                    + "Commands sent from other servers will not run here until it reconnects."));
        }
        if (status.lastError() != null) {
            sender.sendMessage(MM.deserialize("<yellow>Last error <white><ago></white> ago: <gray><error>",
                    Placeholder.unparsed("ago", format(Duration.between(status.lastErrorAt(), Instant.now()))),
                    Placeholder.unparsed("error", status.lastError())));
        }
    }

    private void runSync(Runnable task) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException e) {
            // Plugin is shutting down.
        }
    }

    private static String format(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return seconds / 60 + "m " + seconds % 60 + "s";
        }
        if (seconds < 86400) {
            return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
        }
        return seconds / 86400 + "d " + seconds % 86400 / 3600 + "h";
    }
}
