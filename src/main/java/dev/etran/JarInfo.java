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
final class JarInfo {

    final File file;
    final String name;
    final String version;
    final List<String> deps;
    final boolean paperOnly;

    private JarInfo(File file, String name, String version, List<String> deps, boolean paperOnly) {
        this.file = file;
        this.name = name;
        this.version = version;
        this.deps = deps;
        this.paperOnly = paperOnly;
    }

    /** Returns null if the jar has no plugin.yml / paper-plugin.yml or can't be read. */
    static JarInfo read(File file) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            boolean paperOnly = false;
            ZipEntry entry = zip.getEntry("plugin.yml");
            if (entry == null) {
                entry = zip.getEntry("paper-plugin.yml");
                paperOnly = true;
            }
            if (entry == null) {
                return null;
            }
            Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8);
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
                String name = yaml.getString("name");
                if (name == null || name.trim().isEmpty()) {
                    return null;
                }
                List<String> deps = new ArrayList<String>();
                if (!paperOnly) {
                    deps.addAll(yaml.getStringList("depend"));
                    deps.addAll(yaml.getStringList("softdepend"));
                }
                return new JarInfo(file, name.trim(), String.valueOf(yaml.get("version", "?")), deps, paperOnly);
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception ignored) {
                    // nothing useful to do
                }
            }
        }
    }
}
