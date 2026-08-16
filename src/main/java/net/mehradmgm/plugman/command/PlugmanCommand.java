package net.mehradmgm.plugman.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.PluginContainer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mehradmgm.plugman.core.PluginController;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implements the {@code /plugman} command (aliases: {@code /pm}, {@code /plm}),
 * modelled after PlugManX's command layout.
 * <p>
 * Usage:
 * <pre>
 * /plugman list                - list loaded plugins
 * /plugman load &lt;jar&gt;          - load a jar from the plugins folder
 * /plugman unload &lt;id&gt;         - unload a loaded plugin by id
 * /plugman reload &lt;id&gt;         - unload + load a plugin by id
 * /plugman info &lt;id&gt;           - show plugin details
 * </pre>
 */
public final class PlugmanCommand implements SimpleCommand {

    private static final Component PREFIX = Component.text("[Plugman] ", NamedTextColor.GOLD);

    private final PluginController controller;
    private final Logger logger;

    public PlugmanCommand(PluginController controller, Logger logger) {
        this.controller = controller;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendUsage(source);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list", "l" -> handleList(source);
            case "load" -> handleLoad(source, args);
            case "unload" -> handleUnload(source, args);
            case "reload" -> handleReload(source, args);
            case "info", "i" -> handleInfo(source, args);
            default -> sendUsage(source);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("plugman.use");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        List<String> options = new ArrayList<>();

        if (args.length <= 1) {
            options.addAll(List.of("list", "load", "unload", "reload", "info"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("load")) {
                options.addAll(controller.listUnloadedJars());
            } else if (sub.equals("unload") || sub.equals("reload") || sub.equals("info")) {
                options.addAll(controller.listPlugins().stream()
                        .map(p -> p.getDescription().getId())
                        .collect(Collectors.toList()));
            }
        }
        return options;
    }

    // ------------------------------------------------------------

    private void sendUsage(CommandSource source) {
        source.sendMessage(PREFIX.append(Component.text("Plugman — a runtime plugin manager for Velocity.", NamedTextColor.GRAY)));
        source.sendMessage(Component.text("/plugman list", NamedTextColor.YELLOW)
                .append(Component.text("  - list loaded plugins", NamedTextColor.GRAY)));
        source.sendMessage(Component.text("/plugman load <jar>", NamedTextColor.YELLOW)
                .append(Component.text("  - load a jar from the plugins folder", NamedTextColor.GRAY)));
        source.sendMessage(Component.text("/plugman unload <id>", NamedTextColor.YELLOW)
                .append(Component.text("  - unload a loaded plugin", NamedTextColor.GRAY)));
        source.sendMessage(Component.text("/plugman reload <id>", NamedTextColor.YELLOW)
                .append(Component.text("  - unload then load a plugin again", NamedTextColor.GRAY)));
        source.sendMessage(Component.text("/plugman info <id>", NamedTextColor.YELLOW)
                .append(Component.text("  - show plugin details", NamedTextColor.GRAY)));

        if (!controller.isReflectionAvailable()) {
            source.sendMessage(PREFIX.append(Component.text(
                    "Note: this proxy build is not compatible with runtime load/unload. " +
                            "Only 'list' and 'info' are available; a restart is needed to add plugins.",
                    NamedTextColor.RED)));
        }
    }

    private void handleList(CommandSource source) {
        List<PluginContainer> plugins = controller.listPlugins();
        source.sendMessage(PREFIX.append(Component.text(
                "Loaded plugins (" + plugins.size() + "):", NamedTextColor.GRAY)));
        String joined = plugins.stream()
                .map(p -> p.getDescription().getId())
                .collect(Collectors.joining(", "));
        source.sendMessage(Component.text(joined, NamedTextColor.WHITE));
    }

    private void handleLoad(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(PREFIX.append(Component.text("Usage: /plugman load <jarFileName>", NamedTextColor.RED)));
            return;
        }
        String jarFileName = args[1];
        source.sendMessage(PREFIX.append(Component.text("Loading " + jarFileName + "...", NamedTextColor.GRAY)));

        PluginController.LoadResult result = controller.load(jarFileName);
        reportResult(source, result);
    }

    private void handleUnload(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(PREFIX.append(Component.text("Usage: /plugman unload <pluginId>", NamedTextColor.RED)));
            return;
        }
        String id = args[1];
        source.sendMessage(PREFIX.append(Component.text("Unloading " + id + "...", NamedTextColor.GRAY)));

        PluginController.LoadResult result = controller.unload(id);
        reportResult(source, result);
    }

    private void handleReload(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(PREFIX.append(Component.text("Usage: /plugman reload <pluginId>", NamedTextColor.RED)));
            return;
        }
        String id = args[1];
        source.sendMessage(PREFIX.append(Component.text("Reloading " + id + "...", NamedTextColor.GRAY)));

        PluginController.LoadResult result = controller.reload(id);
        reportResult(source, result);
    }

    private void handleInfo(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(PREFIX.append(Component.text("Usage: /plugman info <pluginId>", NamedTextColor.RED)));
            return;
        }
        String id = args[1];
        Optional<PluginContainer> containerOpt = controller.find(id);
        if (containerOpt.isEmpty()) {
            source.sendMessage(PREFIX.append(Component.text("No plugin with id '" + id + "' is loaded.", NamedTextColor.RED)));
            return;
        }

        PluginContainer container = containerOpt.get();
        var description = container.getDescription();
        source.sendMessage(PREFIX.append(Component.text(description.getId(), NamedTextColor.AQUA)));
        source.sendMessage(Component.text("  Name: ", NamedTextColor.GRAY)
                .append(Component.text(description.getName().orElse("(none)"), NamedTextColor.WHITE)));
        source.sendMessage(Component.text("  Version: ", NamedTextColor.GRAY)
                .append(Component.text(description.getVersion().orElse("(unknown)"), NamedTextColor.WHITE)));
        source.sendMessage(Component.text("  Authors: ", NamedTextColor.GRAY)
                .append(Component.text(String.join(", ", description.getAuthors()), NamedTextColor.WHITE)));
        source.sendMessage(Component.text("  Description: ", NamedTextColor.GRAY)
                .append(Component.text(description.getDescription().orElse("(none)"), NamedTextColor.WHITE)));
    }

    private void reportResult(CommandSource source, PluginController.LoadResult result) {
        if (result.success()) {
            source.sendMessage(PREFIX.append(Component.text(result.message(), NamedTextColor.GREEN)));
        } else {
            source.sendMessage(PREFIX.append(Component.text(result.message(), NamedTextColor.RED)));
        }
    }
}
