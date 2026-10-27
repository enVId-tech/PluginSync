package dev.etran;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

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
final class PaperRegistry {

    private static final String PKG = "io.papermc.paper.plugin.";
    private static final int MAX_DEPTH = 6;

    private PaperRegistry() {
    }

    /** Returns how many registry entries were removed. */
    static int forget(Object pluginManager, Plugin plugin, String name, ClassLoader loader) {
        int[] removed = {0};
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

        walk(pluginManager, plugin, name, visited, 0, false, true, removed);
        walk(staticField("io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler", "INSTANCE"),
                plugin, name, visited, 0, true, true, removed);
        walk(staticCall("io.papermc.paper.plugin.PluginInitializerManager", "instance"),
                plugin, name, visited, 0, true, true, removed);

        unregisterClassLoader(loader);
        return removed[0];
    }

    // ------------------------------------------------------------------ walk

    private static void walk(Object o, Plugin plugin, String name, Set<Object> visited, int depth,
                             boolean strings, boolean root, int[] removed) {
        if (o == null || depth > MAX_DEPTH || !visited.add(o)) {
            return;
        }

        if (o instanceof Collection) {
            Collection<?> collection = (Collection<?>) o;
            try {
                for (Iterator<?> it = collection.iterator(); it.hasNext(); ) {
                    if (matches(it.next(), plugin, name, strings)) {
                        it.remove();
                        removed[0]++;
                    }
                }
            } catch (Throwable ignored) {
                // immutable or concurrently modified; leave it
            }
            for (Object element : snapshot(collection)) {
                if (walkable(element)) {
                    walk(element, plugin, name, visited, depth + 1, strings, false, removed);
                }
            }
            return;
        }

        if (o instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) o;
            try {
                for (Iterator<?> it = map.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next();
                    if (matches(entry.getKey(), plugin, name, strings)
                            || matches(entry.getValue(), plugin, name, false)) {
                        it.remove();
                        removed[0]++;
                    }
                }
            } catch (Throwable ignored) {
                // immutable or concurrently modified; leave it
            }
            for (Object value : snapshot(map.values())) {
                if (walkable(value)) {
                    walk(value, plugin, name, visited, depth + 1, strings, false, removed);
                }
            }
            return;
        }

        boolean paper = o.getClass().getName().startsWith(PKG);
        if (!root && !paper) {
            return;
        }
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(o);
                    if (walkable(value)) {
                        // Bare names only count inside Paper's own structures, so we never touch
                        // unrelated string-keyed maps (permissions and the like) of the Bukkit manager.
                        walk(value, plugin, name, visited, depth + 1, paper, false, removed);
                    }
                } catch (Throwable ignored) {
                    // inaccessible field
                }
            }
        }
    }

    private static boolean walkable(Object v) {
        return v != null && (v instanceof Collection || v instanceof Map || v.getClass().getName().startsWith(PKG));
    }

    private static Collection<Object> snapshot(Collection<?> c) {
        try {
            return new ArrayList<Object>(c);
        } catch (Throwable t) {
            return new ArrayList<Object>();
        }
    }

    // --------------------------------------------------------------- matching

    private static boolean matches(Object e, Plugin plugin, String name, boolean strings) {
        if (e == null) {
            return false;
        }
        if (e == plugin) {
            return true;
        }
        if (e instanceof String) {
            return strings && sameName((String) e, name);
        }

        Object meta = e;
        if (e.getClass().getName().startsWith(PKG)) {
            Object fromProvider = call(e, "getMeta");   // a plugin provider wraps its meta
            if (fromProvider != null) {
                meta = fromProvider;
            }
        }
        if (!meta.getClass().getName().startsWith(PKG) && !isMeta(meta)) {
            return false;
        }
        Object metaName = call(meta, "getName");
        return metaName instanceof String && sameName((String) metaName, name);
    }

    private static boolean isMeta(Object o) {
        try {
            return Class.forName("io.papermc.paper.plugin.configuration.PluginMeta").isInstance(o);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean sameName(String candidate, String name) {
        return candidate.equalsIgnoreCase(name) || candidate.equalsIgnoreCase(name.replace(' ', '_'));
    }

    private static Object call(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------- static helpers

    private static Object staticField(String className, String field) {
        try {
            Field f = Class.forName(className).getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object staticCall(String className, String method) {
        try {
            Method m = Class.forName(className).getMethod(method);
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Paper also tracks every plugin class loader; a closed one must not stay registered. */
    private static void unregisterClassLoader(ClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            Class<?> storage = Class.forName("io.papermc.paper.plugin.provider.classloader.PaperClassLoaderStorage");
            Object instance = storage.getMethod("instance").invoke(null);
            for (Method m : storage.getMethods()) {
                if (m.getName().equals("unregisterClassloader") && m.getParameterTypes().length == 1) {
                    m.invoke(instance, loader);
                }
            }
        } catch (Throwable ignored) {
            // not Paper, or a different layout; nothing to do
        }
    }
}
