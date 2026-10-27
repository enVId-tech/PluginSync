# PluginSync

Detects plugin jars added to or removed from `plugins/` and loads or unloads just those; lets you pick between several versions of the same plugin; and lets you enable, disable, restart or reload one specific plugin. Plugins you don't name are never touched.

## Install
Drop `PluginSync-1.3.0.jar` into `plugins/` and restart once.

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

`disable`, `restart`, `reload` and `choose` refuse if another enabled plugin depends on the target. Add `-f` to do it anyway (you'll get a warning that the dependents may misbehave until a restart). PluginSync won't act on itself. Plugin names tab-complete.

## Several versions of one plugin
If two or more jars in `plugins/` have the same plugin name (for example `Foo-1.0.jar` and `Foo-1.1.jar`), `/pluginsync` does not guess. It leaves a running plugin running, skips loading that plugin, and prints a line like `Multiple jars for Foo: ...` that players can click. Then:
- **Chat:** `/pluginsync choose Foo` lists the jars with their versions and marks the running one. Players can click a line (or `[Open menu]`); anyone, including console, can run `/pluginsync choose Foo 2`.
- **Menu:** `/pluginsync choose Foo gui` opens a chest-style menu with one item per jar.

Choosing a jar: if nothing is running it is loaded; if another version is running it is swapped in (unload, then load); if the running one is chosen it simply keeps running. Unchosen jars are then moved to `plugins/PluginSync/archived/` (never deleted) so the server doesn't complain about a duplicate plugin name on its next restart. Set `archive-unchosen: false` in the config to leave them in place.

A single jar whose version differs from the running one is only reported by `scan`/`sync`; use `reload <plugin>` to switch to it.

## Compatibility
Targets Bukkit/Spigot/Paper/Purpur from 1.8 through 26.x with a single jar:
- Compiled against the Spigot 1.8.8 API as Java 8 bytecode, so it runs on any Java the server itself runs on.
- No Paper-only API and no Adventure; messages use legacy `ChatColor`.
- Anything newer than 1.8 (command map access, `syncCommands`, `updateCommands`, clickable chat components, Paper's plugin metadata) goes through reflection and degrades gracefully if missing. Without clickable chat, players just get plain text and type the command.
- The menu uses only paper items and standard inventory events, so it works on every version.
- Not Folia compatible.

## Build
`mvn package` (any JDK 8+; output is Java 8 bytecode). The jar ends up in `target/`.

## Config
`plugins/PluginSync/config.yml`:
- `ignore:` plugin names that sync/scan will never load or unload.
- `archive-unchosen:` see above (default `true`).

## Behavior and limits
- New jars are loaded in dependency order (depend/softdepend among the new jars).
- A removed plugin is only unloaded if no other loaded plugin hard-depends on it.
- `reload` needs exactly one jar in `plugins/` whose plugin.yml name matches; with several it asks you to `choose`.
- If an unload works but the new load fails, the plugin stays unloaded and the error is shown.
- Jars that only have `paper-plugin.yml` (no `plugin.yml`) are reported but not loaded at runtime.
- Unloading uses reflection into server internals, so it is best-effort. Plugins that register commands through Paper's lifecycle/Brigadier API, or that leave threads/static references behind, may not unload completely. Restart if a plugin misbehaves.
- Moving the old jar on Windows only works once the plugin has been unloaded and its jar released; if a move fails you're told which file to remove by hand.
- Only compiled so far; not yet run on live servers. Test on a throwaway server for each version you care about.

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.