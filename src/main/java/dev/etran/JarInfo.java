package dev.etran;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** What PluginSync needs to know about a jar, read straight from its plugin.yml. */
record JarInfo(File file, String name, String version, List<String> deps, boolean paperOnly) {

    /** Returns null if the jar has no plugin.yml / paper-plugin.yml or can't be read. */
    static JarInfo read(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            boolean paperOnly = false;
            ZipEntry entry = zip.getEntry("plugin.yml");
            if (entry == null) {
                entry = zip.getEntry("paper-plugin.yml");
                paperOnly = true;
            }
            if (entry == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
                String name = yaml.getString("name");
                if (name == null || name.isBlank()) {
                    return null;
                }
                List<String> deps = new ArrayList<>();
                if (!paperOnly) {
                    deps.addAll(yaml.getStringList("depend"));
                    deps.addAll(yaml.getStringList("softdepend"));
                }
                return new JarInfo(file, name, yaml.getString("version", "?"), deps, paperOnly);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
