package au.com.addstar.executeeverywhere;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

public final class ExecuteEverywhere extends JavaPlugin {
    private RedisLink link;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings settings;
        try {
            settings = Settings.load(getConfig(), getLogger());
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid config.yml: " + e.getMessage() + ". Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        link = new RedisLink(settings, getLogger(), this::dispatch);
        link.start();

        Commands commands = new Commands(this, link, settings);
        for (String name : new String[]{"ee", "eestatus"}) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), name);
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }
    }

    @Override
    public void onDisable() {
        if (link != null) {
            link.stop();
            link = null;
        }
    }

    /** Called on the subscriber thread for each received command. */
    private void dispatch(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        try {
            getServer().getScheduler().runTask(this, () -> {
                getLogger().info("Dispatching /" + command);
                try {
                    getServer().dispatchCommand(getServer().getConsoleSender(), command);
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error running /" + command, e);
                }
            });
        } catch (IllegalPluginAccessException e) {
            // Plugin is shutting down.
        }
    }
}
