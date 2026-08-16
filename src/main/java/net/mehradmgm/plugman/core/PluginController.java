package net.mehradmgm.plugman.core;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Core engine behind the /plugman command.
 * <p>
 * Provides three tiers of functionality:
 * <ol>
 *     <li><b>List / info</b> - always works, uses only the public
 *     {@link com.velocitypowered.api.plugin.PluginManager} API.</li>
 *     <li><b>Unload</b> - best-effort cleanup using public API
 *     (event listener/command unregistration) plus reflective removal
 *     from the proxy's internal plugin map.</li>
 *     <li><b>Load / reload</b> - reflectively drives Velocity's own
 *     {@code JavaPluginLoader} pipeline to bring a jar in at runtime,
 *     the same pipeline Velocity itself uses on startup.</li>
 * </ol>
 * Every reflective operation is wrapped so a failure degrades to a
 * clear in-game/console error message instead of an exception bubbling
 * up and destabilizing the proxy.
 */
public final class PluginController {

    private final ProxyServer server;
    private final Logger logger;
    private final Path pluginsDirectory;
    private final ReflectionBridge bridge;

    // Tracks jars we loaded at runtime so /plugman unload and /plugman reload
    // know which source file corresponds to a loaded plugin id.
    private final Map<String, Path> loadedFromSource = new ConcurrentHashMap<>();

    public PluginController(ProxyServer server, Logger logger, Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        // dataDirectory is Plugman's own data folder (plugins/plugman/); the
        // actual plugins folder is its parent.
        Path parent = dataDirectory.getParent();
        this.pluginsDirectory = parent != null ? parent : Path.of("plugins");
        this.bridge = new ReflectionBridge(server, logger);
    }

    public boolean isReflectionAvailable() {
        return bridge.isAvailable();
    }

    public Path getPluginsDirectory() {
        return pluginsDirectory;
    }

    // ----------------------------------------------------------------
    // LIST / INFO — public API only, always safe.
    // ----------------------------------------------------------------

    public List<PluginContainer> listPlugins() {
        return server.getPluginManager().getPlugins().stream()
                .sorted((a, b) -> a.getDescription().getId().compareTo(b.getDescription().getId()))
                .collect(Collectors.toList());
    }

    public Optional<PluginContainer> find(String id) {
        return server.getPluginManager().getPlugin(id);
    }

    /**
     * Lists jar files present in the plugins directory that are not
     * currently loaded as a plugin (candidates for /plugman load).
     */
    public List<String> listUnloadedJars() {
        if (!Files.isDirectory(pluginsDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(pluginsDirectory)) {
            return paths
                    .filter(p -> p.toString().endsWith(".jar"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.warn("[Plugman] Could not list plugins directory: {}", String.valueOf(e));
            return List.of();
        }
    }

    // ----------------------------------------------------------------
    // LOAD
    // ----------------------------------------------------------------

    public LoadResult load(String jarFileName) {
        if (!bridge.isAvailable()) {
            return LoadResult.failure("Plugman could not bind to this proxy build's internals, " +
                    "so runtime loading is unavailable. Restart the proxy to load new plugins.");
        }

        Path jar = pluginsDirectory.resolve(jarFileName);
        if (!Files.isRegularFile(jar)) {
            return LoadResult.failure("File not found: " + jar);
        }

        try {
            Object pluginManagerInstance = getPluginManagerInstance();

            // Build a JavaPluginLoader the same way VelocityServer does.
            Constructor<?> loaderCtor = bridge.getJavaPluginLoaderClass()
                    .getDeclaredConstructor(ProxyServer.class, Path.class);
            loaderCtor.setAccessible(true);
            Object javaPluginLoader = loaderCtor.newInstance(server, pluginsDirectory);

            Method loadCandidateMethod = findMethod(bridge.getJavaPluginLoaderClass(), "loadCandidate", Path.class);
            Method createFromCandidateMethod = findMethod(bridge.getJavaPluginLoaderClass(), "createPluginFromCandidate",
                    Class.forName("com.velocitypowered.api.plugin.PluginDescription"));
            Method createModuleMethod = findMethod(bridge.getJavaPluginLoaderClass(), "createModule",
                    Class.forName("com.velocitypowered.api.plugin.PluginContainer"));
            Method createPluginMethod = findMethodVarargs(bridge.getJavaPluginLoaderClass(), "createPlugin");

            Object candidateDescription = loadCandidateMethod.invoke(javaPluginLoader, jar);
            String pluginId = (String) invokeNoArg(candidateDescription, "getId");

            if (server.getPluginManager().isLoaded(pluginId)) {
                return LoadResult.failure("Plugin '" + pluginId + "' is already loaded.");
            }

            Object fullDescription = createFromCandidateMethod.invoke(javaPluginLoader, candidateDescription);

            // Build a VelocityPluginContainer(PluginDescription) to host the instance.
            Class<?> containerClass = Class.forName("com.velocitypowered.proxy.plugin.loader.VelocityPluginContainer");
            Constructor<?> containerCtor = containerClass.getDeclaredConstructor(
                    Class.forName("com.velocitypowered.api.plugin.PluginDescription"));
            containerCtor.setAccessible(true);
            Object container = containerCtor.newInstance(fullDescription);

            Object module = createModuleMethod.invoke(javaPluginLoader, container);

            // Velocity's own VelocityPluginModule only binds plugin-specific
            // things (data directory, per-plugin logger, etc). At proxy boot,
            // ProxyServer/EventManager/CommandManager/etc. come from a parent
            // injector that JavaPluginLoader.createPlugin() never sees when we
            // call it directly at runtime. Without that parent, Guice fails
            // with "No implementation for ProxyServer was bound." So we supply
            // our own small module that binds ProxyServer on top of Velocity's
            // module.
            Module proxyBindingModule = new AbstractModule() {
                @Override
                protected void configure() {
                    bind(ProxyServer.class).toInstance(server);
                }
            };

            // createPlugin(PluginContainer, Module...) — build a correctly typed array.
            Object moduleArray = Array.newInstance(Module.class, 2);
            Array.set(moduleArray, 0, proxyBindingModule);
            Array.set(moduleArray, 1, module);
            createPluginMethod.invoke(javaPluginLoader, container, moduleArray);

            // Register into VelocityPluginManager's internal maps, mirroring registerPlugin(PluginContainer).
            registerContainer(pluginManagerInstance, container);

            // Note: JavaPluginLoader.createPlugin() already registers the main
            // instance as an event listener internally (Velocity throws
            // "The plugin main instance is automatically registered." if we
            // try to register it again ourselves) — so no explicit
            // eventManager.register() call is needed here, unlike what an
            // earlier version of this method assumed.
            Object instance = ((Optional<?>) invokeNoArg(container, "getInstance")).orElse(null);

            // Trigger the plugin's own startup logic. Velocity fires
            // ProxyInitializeEvent proxy-wide once at boot for every plugin
            // together; we deliberately do NOT re-fire it proxy-wide here,
            // since that would re-run every already-loaded plugin's init logic
            // a second time (unsafe for plugins with non-idempotent startup,
            // e.g. opening DB connections or ports again). Instead we invoke
            // just the newly loaded plugin's own @Subscribe(ProxyInitializeEvent)
            // method(s) directly, which is what actually matters for it to
            // finish enabling (register commands, etc.) — matching the
            // observable effect of boot without the broadcast side effect.
            invokeProxyInitializeOnInstanceOnly(instance);

            loadedFromSource.put(pluginId, jar);
            logger.info("[Plugman] Loaded plugin '{}' from {}", pluginId, jarFileName);
            return LoadResult.success(pluginId);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.error("[Plugman] Failed to load {}", jarFileName, cause);
            return LoadResult.failure("Failed to load " + jarFileName + ": " + cause);
        } catch (Throwable t) {
            logger.error("[Plugman] Failed to load {}", jarFileName, t);
            return LoadResult.failure("Failed to load " + jarFileName + ": " + t);
        }
    }

    // ----------------------------------------------------------------
    // UNLOAD
    // ----------------------------------------------------------------

    public LoadResult unload(String pluginId) {
        Optional<PluginContainer> containerOpt = server.getPluginManager().getPlugin(pluginId);
        if (containerOpt.isEmpty()) {
            return LoadResult.failure("Plugin '" + pluginId + "' is not loaded.");
        }
        if (pluginId.equalsIgnoreCase("plugman")) {
            return LoadResult.failure("Refusing to unload Plugman itself.");
        }

        PluginContainer container = containerOpt.get();
        Object instance = container.getInstance().orElse(null);

        try {
            // Unregister all event listeners this plugin's instance registered.
            // EventManager#unregisterListeners(Object plugin) takes the plugin
            // instance itself (Velocity keys listener registrations by it).
            if (instance != null) {
                server.getEventManager().unregisterListeners(instance);
            }
        } catch (Throwable t) {
            logger.warn("[Plugman] Non-fatal issue while unregistering listeners for {}: {}", pluginId, String.valueOf(t));
        }

        try {
            unregisterCommandsOwnedBy(container);
        } catch (Throwable t) {
            logger.warn("[Plugman] Non-fatal issue while unregistering commands for {}: {}", pluginId, String.valueOf(t));
        }

        if (!bridge.isAvailable()) {
            return LoadResult.failure("Listeners/commands for '" + pluginId + "' were unregistered where possible, " +
                    "but Plugman cannot fully remove it from the plugin registry on this proxy build. " +
                    "A restart is recommended.");
        }

        try {
            Object pluginManagerInstance = getPluginManagerInstance();
            @SuppressWarnings("unchecked")
            Map<String, Object> plugins = (Map<String, Object>) bridge.getPluginsField().get(pluginManagerInstance);
            plugins.remove(pluginId);

            if (bridge.getPluginInstancesField() != null && instance != null) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> instances = (Map<Object, Object>) bridge.getPluginInstancesField().get(pluginManagerInstance);
                instances.remove(instance);
            }

            logger.info("[Plugman] Unloaded plugin '{}'", pluginId);
            return LoadResult.success(pluginId);
        } catch (Throwable t) {
            logger.error("[Plugman] Failed to fully unload {}", pluginId, t);
            return LoadResult.failure("Failed to fully unload " + pluginId + ": " + t);
        }
    }

    /**
     * Unregisters every command whose {@link com.velocitypowered.api.command.CommandMeta#getPlugin()}
     * matches this plugin's main instance or its {@link PluginContainer}. Uses only
     * public API (CommandManager#getAliases / #getCommandMeta / #unregister), so this
     * works even when the reflection bridge is unavailable.
     */
    private void unregisterCommandsOwnedBy(PluginContainer container) {
        Object instance = container.getInstance().orElse(null);
        List<String> aliasesToRemove = new ArrayList<>();

        for (String alias : server.getCommandManager().getAliases()) {
            var meta = server.getCommandManager().getCommandMeta(alias);
            if (meta == null) {
                continue;
            }
            Object owner = meta.getPlugin();
            if (owner != null && (owner == container || owner == instance
                    || owner.equals(container) || (instance != null && owner.equals(instance)))) {
                aliasesToRemove.add(alias);
            }
        }

        for (String alias : aliasesToRemove) {
            server.getCommandManager().unregister(alias);
        }
    }

    // ----------------------------------------------------------------
    // RELOAD
    // ----------------------------------------------------------------

    public LoadResult reload(String pluginId) {
        Path source = loadedFromSource.get(pluginId);
        if (source == null) {
            // Try to guess the jar from the plugins directory by matching the id.
            source = guessJarForId(pluginId);
        }
        if (source == null) {
            return LoadResult.failure("Plugman doesn't know which jar '" + pluginId +
                    "' came from. Unload it and use '/plugman load <jar>' instead.");
        }

        LoadResult unloadResult = unload(pluginId);
        if (!unloadResult.success()) {
            return unloadResult;
        }
        return load(source.getFileName().toString());
    }

    private Path guessJarForId(String pluginId) {
        if (!Files.isDirectory(pluginsDirectory)) {
            return null;
        }
        try (Stream<Path> paths = Files.list(pluginsDirectory)) {
            return paths
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> p.getFileName().toString().toLowerCase().contains(pluginId.toLowerCase()))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ----------------------------------------------------------------
    // Reflection helpers
    // ----------------------------------------------------------------

    private Object getPluginManagerInstance() throws Exception {
        Method getPm = findMethodNoArgWalkingHierarchy(server.getClass(), "getPluginManager");
        getPm.setAccessible(true);
        return getPm.invoke(server);
    }

    private void registerContainer(Object pluginManagerInstance, Object container) throws Exception {
        if (bridge.getRegisterMethod() != null) {
            bridge.getRegisterMethod().invoke(pluginManagerInstance, container);
            return;
        }
        // Fallback: manipulate the maps directly, replicating registerPlugin's body.
        @SuppressWarnings("unchecked")
        Map<String, Object> plugins = (Map<String, Object>) bridge.getPluginsField().get(pluginManagerInstance);
        String id = (String) invokeNoArg(invokeNoArg(container, "getDescription"), "getId");
        plugins.put(id, container);

        if (bridge.getPluginInstancesField() != null) {
            Object instanceOpt = invokeNoArg(container, "getInstance");
            Optional<?> opt = (Optional<?>) instanceOpt;
            if (opt.isPresent()) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> instances = (Map<Object, Object>) bridge.getPluginInstancesField().get(pluginManagerInstance);
                instances.put(opt.get(), container);
            }
        }
    }

    /**
     * Directly invokes every method on {@code instance} (walking its full class
     * hierarchy) that is annotated {@code @Subscribe} and accepts a single
     * {@code ProxyInitializeEvent} parameter. This achieves the same practical
     * effect as Velocity's own proxy-wide ProxyInitializeEvent firing at boot,
     * but scoped only to the plugin we just loaded, so other already-running
     * plugins are not re-notified.
     */
    private void invokeProxyInitializeOnInstanceOnly(Object instance) {
        if (instance == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends java.lang.annotation.Annotation> subscribeAnnotation =
                    (Class<? extends java.lang.annotation.Annotation>) Class.forName("com.velocitypowered.api.event.Subscribe");
            Class<?> eventClass = Class.forName("com.velocitypowered.api.event.proxy.ProxyInitializeEvent");
            Constructor<?> eventCtor = eventClass.getDeclaredConstructor();
            eventCtor.setAccessible(true);
            Object event = eventCtor.newInstance();

            boolean invokedAny = false;
            for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(subscribeAnnotation)
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isAssignableFrom(eventClass)) {
                        m.setAccessible(true);
                        m.invoke(instance, event);
                        invokedAny = true;
                    }
                }
            }

            if (!invokedAny) {
                logger.warn("[Plugman] Loaded plugin instance has no @Subscribe ProxyInitializeEvent " +
                        "handler; if it relies on that event to finish starting up, it may not be fully enabled.");
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.error("[Plugman] The plugin's ProxyInitializeEvent handler threw an exception while starting up", cause);
        } catch (Throwable t) {
            logger.warn("[Plugman] Could not dispatch ProxyInitializeEvent to newly loaded plugin: {}", String.valueOf(t));
        }
    }

    private static Object invokeNoArg(Object target, String methodName) throws Exception {
        Method m = findMethodNoArgWalkingHierarchy(target.getClass(), methodName);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) throws NoSuchMethodException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // continue searching
            }
        }
        throw new NoSuchMethodException(name + " on " + clazz);
    }

    private static Method findMethodVarargs(Class<?> clazz, String name) throws NoSuchMethodException {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.isVarArgs()) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodException(name + " (varargs) on " + clazz);
    }

    private static Method findMethodNoArgWalkingHierarchy(Class<?> clazz, String name) throws NoSuchMethodException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
            for (Class<?> iface : c.getInterfaces()) {
                try {
                    return findMethodNoArgWalkingHierarchy(iface, name);
                } catch (NoSuchMethodException ignored) {
                    // continue
                }
            }
        }
        throw new NoSuchMethodException(name + " on " + clazz);
    }

    public void shutdown() {
        loadedFromSource.clear();
    }

    /**
     * Simple result holder for load/unload/reload operations.
     */
    public static final class LoadResult {
        private final boolean success;
        private final String message;

        private LoadResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static LoadResult success(String pluginId) {
            return new LoadResult(true, "Plugin '" + pluginId + "' operation completed successfully.");
        }

        public static LoadResult failure(String message) {
            return new LoadResult(false, message);
        }

        public boolean success() {
            return success;
        }

        public String message() {
            return message;
        }
    }
}