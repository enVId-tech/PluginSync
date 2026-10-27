package dev.etran

import org.bukkit.plugin.Plugin
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Paper (1.19.3+) keeps its own records of loaded plugins (the plugin lists, a dependency tree,
 * provider storages and a class loader registry) behind the plain Bukkit plugin manager. If a
 * plugin is unloaded without clearing those, loading it again fails with "attempted to add
 * duplicate plugin identifier". This walks those structures by reflection and removes everything
 * that belongs to the unloaded plugin. On servers without Paper's plugin system it does nothing.
 *
 * Only objects in Paper's plugin package (plus collections and maps) are searched, and only
 * entries that are the plugin itself or carry its name are removed.
 */
object PaperRegistry {

    private const val PKG = "io.papermc.paper.plugin."
    private const val MAX_DEPTH = 6

    /** Returns how many registry entries were removed. */
    fun forget(pluginManager: Any?, plugin: Plugin, name: String, loader: ClassLoader?): Int {
        val removed = intArrayOf(0)
        val visited: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        walk(pluginManager, plugin, name, visited, 0, false, true, removed)
        walk(staticField("io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler", "INSTANCE"),
            plugin, name, visited, 0, true, true, removed)
        walk(staticCall("io.papermc.paper.plugin.PluginInitializerManager", "instance"),
            plugin, name, visited, 0, true, true, removed)

        unregisterClassLoader(loader)
        return removed[0]
    }

    // ------------------------------------------------------------------ walk

    @Suppress("UNCHECKED_CAST")
    private fun walk(
        o: Any?, plugin: Plugin, name: String, visited: MutableSet<Any>, depth: Int,
        strings: Boolean, root: Boolean, removed: IntArray
    ) {
        if (o == null || depth > MAX_DEPTH || !visited.add(o)) {
            return
        }

        if (o is Collection<*>) {
            val collection = o as MutableCollection<Any?>
            try {
                val it = collection.iterator()
                while (it.hasNext()) {
                    if (matches(it.next(), plugin, name, strings)) {
                        it.remove()
                        removed[0]++
                    }
                }
            } catch (ignored: Throwable) {
                // immutable or concurrently modified; leave it
            }
            for (element in snapshot(collection)) {
                if (walkable(element)) {
                    walk(element, plugin, name, visited, depth + 1, strings, false, removed)
                }
            }
            return
        }

        if (o is Map<*, *>) {
            val map = o as MutableMap<Any?, Any?>
            try {
                val it = map.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (matches(entry.key, plugin, name, strings) || matches(entry.value, plugin, name, false)) {
                        it.remove()
                        removed[0]++
                    }
                }
            } catch (ignored: Throwable) {
                // immutable or concurrently modified; leave it
            }
            for (value in snapshot(map.values)) {
                if (walkable(value)) {
                    walk(value, plugin, name, visited, depth + 1, strings, false, removed)
                }
            }
            return
        }

        val paper = o.javaClass.name.startsWith(PKG)
        if (!root && !paper) {
            return
        }
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) {
                    continue
                }
                try {
                    f.isAccessible = true
                    val value = f.get(o)
                    if (walkable(value)) {
                        // Bare names only count inside Paper's own structures, so we never touch
                        // unrelated string-keyed maps (permissions and the like) of the Bukkit manager.
                        walk(value, plugin, name, visited, depth + 1, paper, false, removed)
                    }
                } catch (ignored: Throwable) {
                    // inaccessible field
                }
            }
            c = c.superclass
        }
    }

    private fun walkable(v: Any?): Boolean =
        v != null && (v is Collection<*> || v is Map<*, *> || v.javaClass.name.startsWith(PKG))

    private fun snapshot(c: Collection<*>): List<Any?> = try {
        ArrayList<Any?>(c)
    } catch (t: Throwable) {
        ArrayList<Any?>()
    }

    // --------------------------------------------------------------- matching

    private fun matches(e: Any?, plugin: Plugin, name: String, strings: Boolean): Boolean {
        if (e == null) {
            return false
        }
        if (e === plugin) {
            return true
        }
        if (e is String) {
            return strings && sameName(e, name)
        }

        var meta: Any = e
        if (e.javaClass.name.startsWith(PKG)) {
            val fromProvider = call(e, "getMeta")   // a plugin provider wraps its meta
            if (fromProvider != null) {
                meta = fromProvider
            }
        }
        if (!meta.javaClass.name.startsWith(PKG) && !isMeta(meta)) {
            return false
        }
        val metaName = call(meta, "getName")
        return metaName is String && sameName(metaName, name)
    }

    private fun isMeta(o: Any): Boolean = try {
        Class.forName("io.papermc.paper.plugin.configuration.PluginMeta").isInstance(o)
    } catch (t: Throwable) {
        false
    }

    private fun sameName(candidate: String, name: String): Boolean =
        candidate.equals(name, ignoreCase = true) || candidate.equals(name.replace(' ', '_'), ignoreCase = true)

    private fun call(target: Any, method: String): Any? = try {
        val m: Method = target.javaClass.getMethod(method)
        m.isAccessible = true
        m.invoke(target)
    } catch (t: Throwable) {
        null
    }

    // ---------------------------------------------------------- static helpers

    private fun staticField(className: String, field: String): Any? = try {
        val f: Field = Class.forName(className).getDeclaredField(field)
        f.isAccessible = true
        f.get(null)
    } catch (t: Throwable) {
        null
    }

    private fun staticCall(className: String, method: String): Any? = try {
        val m: Method = Class.forName(className).getMethod(method)
        m.isAccessible = true
        m.invoke(null)
    } catch (t: Throwable) {
        null
    }

    /** Paper also tracks every plugin class loader; a closed one must not stay registered. */
    private fun unregisterClassLoader(loader: ClassLoader?) {
        if (loader == null) {
            return
        }
        try {
            val storage = Class.forName("io.papermc.paper.plugin.provider.classloader.PaperClassLoaderStorage")
            val instance = storage.getMethod("instance").invoke(null)
            for (m in storage.methods) {
                if (m.name == "unregisterClassloader" && m.parameterTypes.size == 1) {
                    m.invoke(instance, loader)
                }
            }
        } catch (ignored: Throwable) {
            // not Paper, or a different layout; nothing to do
        }
    }
}
