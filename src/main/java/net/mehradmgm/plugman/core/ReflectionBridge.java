package net.mehradmgm.plugman.core;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Reflection bridge for Velocity internals.
 *
 * Compatible with the Velocity 4.x plugin loading structure where:
 *
 * - plugins are stored in VelocityPluginManager.pluginsById
 * - plugin instances are stored in VelocityPluginManager.pluginInstances
 * - JavaPluginLoader creates the plugin
 * - common Guice bindings are created by Plugman itself
 */
public final class ReflectionBridge {

    private final Logger logger;

    private boolean available;

    private Class<?> velocityServerClass;
    private Class<?> velocityPluginManagerClass;
    private Class<?> javaPluginLoaderClass;

    private Field pluginsField;
    private Field pluginInstancesField;

    private Method registerMethod;

    public ReflectionBridge(
            ProxyServer server,
            Logger logger
    ) {

        this.logger = logger;

        try {

            resolve(server);

            available = true;

        } catch (Throwable t) {

            available = false;

            logger.warn(
                    "[Plugman] Could not bind to Velocity internals. " +
                            "Runtime plugin loading/unloading is unavailable. Reason: {}",
                    String.valueOf(t)
            );
        }
    }

    private void resolve(
            ProxyServer server
    ) throws Exception {

        /*
         * VelocityServer
         */
        velocityServerClass =
                Class.forName(
                        "com.velocitypowered.proxy.VelocityServer"
                );

        if (!velocityServerClass.isInstance(server)) {

            throw new IllegalStateException(
                    "ProxyServer is not a VelocityServer instance"
            );
        }

        /*
         * VelocityPluginManager
         */
        velocityPluginManagerClass =
                Class.forName(
                        "com.velocitypowered.proxy.plugin.VelocityPluginManager"
                );

        /*
         * IMPORTANT:
         *
         * Current Velocity uses:
         *
         *     pluginsById
         *
         * NOT:
         *
         *     plugins
         */
        pluginsField =
                findField(
                        velocityPluginManagerClass,
                        "pluginsById"
                );

        pluginsField.setAccessible(true);

        /*
         * pluginInstances is also present in current Velocity.
         */
        try {

            pluginInstancesField =
                    findField(
                            velocityPluginManagerClass,
                            "pluginInstances"
                    );

            pluginInstancesField.setAccessible(true);

        } catch (NoSuchFieldException ignored) {

            pluginInstancesField = null;
        }

        /*
         * registerPlugin(PluginContainer)
         */
        registerMethod =
                findRegisterPluginMethod(
                        velocityPluginManagerClass
                );

        /*
         * JavaPluginLoader
         */
        javaPluginLoaderClass =
                Class.forName(
                        "com.velocitypowered.proxy.plugin.loader.java.JavaPluginLoader"
                );
    }

    private static Field findField(
            Class<?> clazz,
            String name
    ) throws NoSuchFieldException {

        for (
                Class<?> current = clazz;
                current != null;
                current = current.getSuperclass()
        ) {

            try {

                Field field =
                        current.getDeclaredField(name);

                field.setAccessible(true);

                return field;

            } catch (NoSuchFieldException ignored) {
            }
        }

        throw new NoSuchFieldException(
                "Field '" +
                        name +
                        "' not found in " +
                        clazz.getName()
        );
    }

    private static Method findRegisterPluginMethod(
            Class<?> clazz
    ) throws NoSuchMethodException {

        for (
                Class<?> current = clazz;
                current != null;
                current = current.getSuperclass()
        ) {

            for (Method method :
                    current.getDeclaredMethods()) {

                if (!method.getName().equals("registerPlugin")) {
                    continue;
                }

                if (method.getParameterCount() != 1) {
                    continue;
                }

                method.setAccessible(true);

                return method;
            }
        }

        throw new NoSuchMethodException(
                "registerPlugin(PluginContainer)"
        );
    }

    public boolean isAvailable() {
        return available;
    }

    public Class<?> getVelocityPluginManagerClass() {
        return velocityPluginManagerClass;
    }

    public Class<?> getJavaPluginLoaderClass() {
        return javaPluginLoaderClass;
    }

    public Field getPluginsField() {
        return pluginsField;
    }

    public Field getPluginInstancesField() {
        return pluginInstancesField;
    }

    public Method getRegisterMethod() {
        return registerMethod;
    }

    public Logger getLogger() {
        return logger;
    }
}