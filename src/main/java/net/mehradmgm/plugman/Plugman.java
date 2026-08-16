package net.mehradmgm.plugman;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.mehradmgm.plugman.command.PlugmanCommand;
import net.mehradmgm.plugman.core.PluginController;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Plugman for Velocity.
 * <p>
 * A runtime plugin manager for the Velocity proxy, inspired by PlugManX for
 * Bukkit/Spigot/Paper. Allows loading, unloading and reloading plugin jars
 * without restarting the proxy.
 *
 * @author mehradmgm
 */
@Plugin(
        id = "plugman",
        name = "Plugman",
        version = BuildConstants.VERSION,
        description = "PlugManX-style runtime plugin manager for Velocity.",
        authors = {"mehradmgm"}
)
public final class Plugman {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginController controller;

    @Inject
    public Plugman(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            this.controller = new PluginController(server, logger, dataDirectory);
            server.getCommandManager().register(
                    server.getCommandManager().metaBuilder("plugman")
                            .aliases("pm", "plm")
                            .plugin(this)
                            .build(),
                    new PlugmanCommand(controller, logger)
            );
            logger.info("Plugman has been enabled. Reflection compatibility: {}",
                    controller.isReflectionAvailable() ? "OK" : "DEGRADED (list/info only, see logs above)");
        } catch (Throwable t) {
            // Never let Plugman bring the proxy down. Log and degrade gracefully.
            logger.error("Plugman failed to initialize fully. The proxy will continue to run normally, " +
                    "but plugin load/unload features may be unavailable.", t);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (controller != null) {
            controller.shutdown();
        }
    }
}
