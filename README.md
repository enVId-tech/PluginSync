# PluginSync

Adds a command that detects plugin jars added to or removed from `plugins/` and loads or unloads just those. Plugins that are already running are never reloaded.

## Install
Drop `PluginSync-1.0.0.jar` into `plugins/` and restart once. After that:

| Command | What it does |
|---|---|
| `/pluginsync scan` | Dry run: shows what would be loaded/unloaded |
| `/pluginsync` (or `/psync`) | Loads new jars, unloads plugins whose jar was deleted |

Permission: `pluginsync.use` (default: op). Works from console too.

## Build
`mvn package` (Java 21). The jar ends up in `target/`. Change `paper.api.version` in `pom.xml` to compile against a newer Paper API.

## Config
`plugins/PluginSync/config.yml` has an `ignore:` list of plugin names that PluginSync will never load or unload.

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.