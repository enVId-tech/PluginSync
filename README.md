# PluginSync

Detects plugin jars added to or removed from `plugins/` and loads or unloads just those, and lets you enable, disable, restart or reload one specific plugin. Plugins you don't name are never touched.

## Install
Drop `PluginSync-1.2.0.jar` into `plugins/` and restart once.

## Commands
Permission for all of them: `pluginsync.use` (default: op). They work from the console too. Alias: `/psync`.

| Command | What it does |
|---|---|
| `/pluginsync` | Load jars that were added, unload plugins whose jar was deleted |
| `/pluginsync scan` | Dry run: show what the above would do |
| `/pluginsync enable <plugin>` | Turn a disabled plugin on |
| `/pluginsync disable <plugin>` | Turn a plugin off (it stays loaded, so `enable` brings it back) |
| `/pluginsync restart <plugin>` | Disable then enable the same plugin (no jar re-read) |
| `/pluginsync reload <plugin>` | Unload and load it again from its jar, so a replaced jar takes effect |

`disable`, `restart` and `reload` refuse if another enabled plugin depends on the target. Add `-f` to do it anyway (you'll get a warning that the dependents may misbehave until a restart). PluginSync won't act on itself. Plugin names tab-complete.

## Compatibility
Targets Bukkit/Spigot/Paper/Purpur from 1.8 through 26.x with a single jar:
- Compiled against the Spigot 1.8.8 API as Java 8 bytecode, so it runs on any Java the server itself runs on.
- No Paper-only API and no Adventure; messages use legacy `ChatColor`.
- Anything newer than 1.8 (command map access, `syncCommands`, `updateCommands`, Paper's plugin metadata) goes through reflection and degrades gracefully if missing.
- Not Folia compatible.

## Build
`mvn package` (any JDK 8+; output is Java 8 bytecode). The jar ends up in `target/`.

## Config
`plugins/PluginSync/config.yml` has an `ignore:` list of plugin names that the scan/sync will never load or unload.

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.