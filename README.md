# PluginSync

Detects plugin jars added to or removed from `plugins/` and loads or unloads just those; lets you pick between several versions of the same plugin; and lets you enable, disable, restart or reload one specific plugin. Plugins you don't name are never touched.

## Install
Drop `PluginSync-1.5.0.jar` into `plugins/` and restart once.

## Commands
Permission for all of them: `pluginsync.use` (default: op). They work from the console too. Alias: `/psync`.

| Command | What it does |
|---|---|
| `/pluginsync` | Load jars that were added, unload plugins whose jar was deleted |
| `/pluginsync scan` | Dry run: show what the above would do |
| `/pluginsync choose [plugin]` | List the competing jars of a plugin (all plugins if none given) |
| `/pluginsync choose <plugin> <number>` | Use that jar |
| `/pluginsync choose <plugin> gui` | Same, as a click-to-pick menu (players only) |
| `/pluginsync enable <plugin>` | Turn a disabled plugin on |
| `/pluginsync disable <plugin>` | Turn a plugin off (it stays loaded, so `enable` brings it back) |
| `/pluginsync restart <plugin>` | Disable then enable the same plugin (no jar re-read) |
| `/pluginsync reload <plugin>` | Unload and load it again from its jar, so a replaced jar takes effect |
| `/pluginsync update` | Switch PluginSync itself to its newest jar (see below) |

`disable`, `restart`, `reload` and `choose` refuse if another enabled plugin depends on the target. Add `-f` to do it anyway (you'll get a warning that the dependents may misbehave until a restart). PluginSync won't act on itself. Plugin names tab-complete.

## Updating PluginSync itself
Drop a newer PluginSync jar into `plugins/` (either next to the old one or overwriting it) and run `/pluginsync update` (same as `/pluginsync reload PluginSync`). PluginSync then replaces itself without a server restart:
- With several PluginSync jars in the folder, the most recently modified one is used, and the older ones are archived according to `archive-unchosen`.
- The new jar is checked first (it must have a `plugin.yml` and its main class). If it is bad, nothing happens.
- If the new jar fails to load, the previous version is loaded again from its jar, if it is still in the folder.
- All of PluginSync's classes are loaded into memory when it starts, so it keeps working even if its jar is overwritten on disk mid-update. The old class loader is only closed once the new version is running.

This needs a PluginSync that already contains the feature (1.4.0 or newer; 1.5.0 is the Kotlin port with identical behaviour), so the first time you have to restart the server once to get there. `/pluginsync choose PluginSync` and `/pluginsync scan` also understand the situation.

## Several versions of one plugin
If two or more jars in `plugins/` have the same plugin name (for example `Foo-1.0.jar` and `Foo-1.1.jar`), `/pluginsync` does not guess. It leaves a running plugin running, skips loading that plugin, and prints a line like `Multiple jars for Foo: ...` that players can click. Then:
- **Chat:** `/pluginsync choose Foo` lists the jars with their versions and marks the running one. Players can click a line (or `[Open menu]`); anyone, including console, can run `/pluginsync choose Foo 2`.
- **Menu:** `/pluginsync choose Foo gui` opens a chest-style menu with one item per jar.

Choosing a jar: if nothing is running it is loaded; if another version is running it is swapped in (unload, then load); if the running one is chosen it simply keeps running. Unchosen jars are then moved to `plugins/PluginSync/archived/` (never deleted) so the server doesn't complain about a duplicate plugin name on its next restart. Set `archive-unchosen: false` in the config to leave them in place.

A single jar whose version differs from the running one is only reported by `scan`/`sync`; use `reload <plugin>` to switch to it.

## Compatibility
Targets Bukkit/Spigot/Paper/Purpur from 1.8 through 26.x with a single jar:
- Written in Kotlin, compiled against the Spigot 1.8.8 API as Java 8 bytecode, so it runs on any Java the server itself runs on. The Kotlin runtime is bundled and relocated to `dev.etran.libs.kotlin`, so it never clashes with other plugins (the jar is ~1.3 MB because of it).
- No Paper-only API and no Adventure; messages use legacy `ChatColor`.
- Anything newer than 1.8 (command map access, `syncCommands`, `updateCommands`, clickable chat components, Paper's plugin metadata) goes through reflection and degrades gracefully if missing. Without clickable chat, players just get plain text and type the command.
- The menu uses only paper items and standard inventory events, so it works on every version.
- Not Folia compatible.

## Build
`mvn package` (any JDK 8+; sources are in `src/main/kotlin`, output is Java 8 bytecode). The shaded jar ends up in `target/` as `PluginSync-<version>.jar`.

## Config
`plugins/PluginSync/config.yml`:
- `ignore:` plugin names that sync/scan will never load or unload.
- `archive-unchosen:` see above (default `true`).

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.