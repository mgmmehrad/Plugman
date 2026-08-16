# Velocity Plugin Manager

A lightweight plugin management tool for **Velocity Proxy** that allows you to manage plugins without restarting the proxy.

## Features

* Load plugins without restarting Velocity
* Unload plugins without restarting Velocity
* Reload plugins
* View detailed plugin information
* Manage plugins directly from the proxy
* Designed specifically for Velocity Proxy

## Commands

| Command                    | Description                         |
| -------------------------- | ----------------------------------- |
| `/plugman load <plugin>`   | Loads a plugin                      |
| `/plugman unload <plugin>` | Unloads a plugin                    |
| `/plugman reload <plugin>` | Reloads a plugin                    |
| `/plugman info <plugin>`   | Displays information about a plugin |

## Why?

Normally, changing or updating a Velocity plugin requires restarting the proxy.

This plugin provides a convenient way to manage plugins at runtime, making development, testing, and server administration easier.

## Compatibility

* **Platform:** Velocity
* **Java:** Depends on your Velocity version
* **Proxy:** Velocity Proxy

## Important Notice

Dynamic plugin loading and unloading can have limitations depending on how a plugin is implemented.

Some plugins may not support being unloaded or reloaded safely because they may create background tasks, network connections, event listeners, or other resources that are not properly released.

Use reload and unload functionality carefully, especially on production proxies.

## Installation

1. Download the latest release.
2. Place the `.jar` file into your Velocity `plugins` directory.
3. Start or restart Velocity once to load the plugin.
4. Use the available commands to manage other plugins.

##Commands:

1. /plugman load <filename>.jar
2. /plugman unload ExamplePluginName
3. /plugman reload ExamplePluginName
4. /plugman info ExamplePluginName
5. /plugman list
