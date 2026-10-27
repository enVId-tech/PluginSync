package dev.etran

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile

/** What PluginSync needs to know about a jar, read straight from its plugin.yml. */
class JarInfo private constructor(
    val file: File,
    val name: String,
    val version: String,
    val deps: List<String>,
    val paperOnly: Boolean,
    /** The "main:" class from plugin.yml, or null. */
    val main: String?
) {

    /** True if the jar really contains the class named by "main:" (a cheap sanity check). */
    fun hasMainClass(): Boolean {
        val mainClass = main?.trim()
        if (mainClass.isNullOrEmpty()) {
            return false
        }
        return try {
            ZipFile(file).use { it.getEntry(mainClass.replace('.', '/') + ".class") != null }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /** Returns null if the jar has no plugin.yml / paper-plugin.yml or can't be read. */
        fun read(file: File): JarInfo? {
            return try {
                ZipFile(file).use { zip ->
                    var paperOnly = false
                    var entry = zip.getEntry("plugin.yml")
                    if (entry == null) {
                        entry = zip.getEntry("paper-plugin.yml")
                        paperOnly = true
                    }
                    if (entry == null) {
                        return null
                    }
                    InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8).use { reader ->
                        val yaml = YamlConfiguration.loadConfiguration(reader)
                        val name = yaml.getString("name")
                        if (name == null || name.trim().isEmpty()) {
                            return null
                        }
                        val deps = ArrayList<String>()
                        if (!paperOnly) {
                            deps.addAll(yaml.getStringList("depend"))
                            deps.addAll(yaml.getStringList("softdepend"))
                        }
                        JarInfo(file, name.trim(), yaml.get("version", "?").toString(), deps, paperOnly,
                            yaml.getString("main"))
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
