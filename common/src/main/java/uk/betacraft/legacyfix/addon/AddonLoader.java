package uk.betacraft.legacyfix.addon;

import uk.betacraft.legacyfix.Agent;
import uk.betacraft.legacyfix.Logger;
import uk.betacraft.legacyfix.patch.api.PatchPool;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Loads addons from the {@code -Dlf.addons=} system property (already collected into
 * Agent SETTINGS by the static block). The value is a comma-separated list of paths;
 * a path ending in {@code .jar} is treated as a file, otherwise as a directory whose
 * top-level {@code *.jar} entries are scanned.
 *
 * Each jar declares its entry classes via the {@code LegacyFix-Addon} manifest
 * attribute (comma-separated class names reserved for multi-entry jars). Jars are
 * loaded through a URLClassLoader whose parent is the agent classloader, so addons
 * can {@code import javassist.*} and {@code uk.betacraft.legacyfix.*}.
 *
 * Load order replicates v1 {@code Wrapper.loadAddon}: dependencies from
 * {@link Addon#applyAfter()} are resolved first, names deduplicate, cycles abort
 * explicitly. A broken addon never takes the agent down (non-required semantics).
 */
public class AddonLoader {
    public static final String MANIFEST_ATTRIBUTE = "LegacyFix-Addon";

    private static final List<Addon> candidates = new ArrayList<Addon>();
    private static final List<Addon> loaded = new ArrayList<Addon>();
    private static final Map<String, Addon> byName = new HashMap<String, Addon>();
    private static final Map<String, Addon> byClassName = new HashMap<String, Addon>();
    private static final Set<String> inProgress = new HashSet<String>();

    private static boolean loadedAlready = false;

    public static synchronized void load() {
        if (loadedAlready) {
            return;
        }
        loadedAlready = true;

        Object raw = Agent.getSettings().get("lf.addons");
        String value = raw == null ? null : String.valueOf(raw);
        if (value == null || value.trim().length() == 0) {
            return;
        }

        List<File> jars = new ArrayList<File>();
        String[] entries = value.split(",");
        for (int i = 0; i < entries.length; i++) {
            String path = entries[i].trim();
            if (path.length() == 0) {
                continue;
            }
            File f = new File(path);
            if (f.isFile()) {
                jars.add(f);
            } else if (f.isDirectory()) {
                File[] children = f.listFiles();
                if (children != null) {
                    Arrays.sort(children);
                    for (int j = 0; j < children.length; j++) {
                        File child = children[j];
                        if (child.isFile() && child.getName().toLowerCase().endsWith(".jar")) {
                            jars.add(child);
                        }
                    }
                }
            } else {
                Logger.error("Addon path not found: " + path);
            }
        }

        if (jars.isEmpty()) {
            return;
        }

        URL[] urls = new URL[jars.size()];
        int urlCount = 0;
        for (int i = 0; i < jars.size(); i++) {
            try {
                urls[urlCount++] = jars.get(i).toURI().toURL();
            } catch (Exception e) {
                Logger.error("Failed to resolve addon jar URL: " + jars.get(i), e);
            }
        }
        if (urlCount == 0) {
            return;
        }
        if (urlCount != urls.length) {
            URL[] trimmed = new URL[urlCount];
            System.arraycopy(urls, 0, trimmed, 0, urlCount);
            urls = trimmed;
        }

        ClassLoader loader = new URLClassLoader(urls, AddonLoader.class.getClassLoader());

        for (int i = 0; i < jars.size(); i++) {
            File jar = jars.get(i);
            try {
                JarFile jf = new JarFile(jar);
                String attr;
                try {
                    Manifest mf = jf.getManifest();
                    attr = mf == null ? null : mf.getMainAttributes().getValue(MANIFEST_ATTRIBUTE);
                } finally {
                    jf.close();
                }

                if (attr == null || attr.trim().length() == 0) {
                    Logger.error("Addon jar has no \"" + MANIFEST_ATTRIBUTE + "\" manifest attribute: " + jar);
                    continue;
                }

                String[] classNames = attr.split(",");
                for (int j = 0; j < classNames.length; j++) {
                    instantiate(loader, jar, classNames[j].trim());
                }
            } catch (Throwable t) {
                Logger.error("Failed to load addon jar: " + jar, t);
            }
        }

        for (int i = 0; i < candidates.size(); i++) {
            resolve(candidates.get(i), loaded);
        }

        if (!loaded.isEmpty()) {
            List<String> names = new ArrayList<String>();
            for (int i = 0; i < loaded.size(); i++) {
                names.add(loaded.get(i).getName());
            }
            Logger.logList("Addons:", names);
        }
    }

    private static void instantiate(ClassLoader loader, File jar, String className) {
        if (className.length() == 0 || byClassName.containsKey(className)) {
            return;
        }

        try {
            Class<?> cls = loader.loadClass(className);
            if (!Addon.class.isAssignableFrom(cls)) {
                Logger.error("Addon entry class does not extend Addon: " + className + " in " + jar);
                return;
            }

            Addon addon = (Addon) cls.newInstance();
            if (byName.containsKey(addon.getName())) {
                Logger.error("Duplicate addon name \"" + addon.getName() + "\" in " + jar + ", skipping");
                return;
            }

            byName.put(addon.getName(), addon);
            byClassName.put(className, addon);
            candidates.add(addon);
        } catch (Throwable t) {
            Logger.error("Failed to instantiate addon " + className + " from " + jar, t);
        }
    }

    /** v1 loadAddon recursion: applyAfter dependencies first, explicit cycle error. */
    private static void resolve(Addon addon, List<Addon> ordered) {
        if (ordered.contains(addon)) {
            return;
        }
        if (inProgress.contains(addon.getName())) {
            Logger.error("Addon dependency cycle detected at: " + addon.getName());
            return;
        }

        inProgress.add(addon.getName());
        List<String> deps = addon.applyAfter();
        if (deps != null) {
            for (Iterator<String> it = deps.iterator(); it.hasNext(); ) {
                String dep = it.next();
                Addon depAddon = byName.get(dep);
                if (depAddon == null) {
                    depAddon = byClassName.get(dep);
                }
                if (depAddon == null) {
                    Logger.error("Addon \"" + addon.getName() + "\" requires unknown addon: " + dep);
                    continue;
                }
                resolve(depAddon, ordered);
            }
        }
        inProgress.remove(addon.getName());
        ordered.add(addon);
    }

    /** Patch phase: run after Patcher.apply(), before redefinition collection. */
    public static void runPatchPhase(PatchPool pool) {
        for (int i = 0; i < loaded.size(); i++) {
            Addon addon = loaded.get(i);
            try {
                addon.patch(pool);
            } catch (Throwable t) {
                Logger.error("Addon \"" + addon.getName() + "\" failed to patch", t);
            }
        }
    }

    /** @return false when an addon requested STOP_CODE (abort launch). */
    public static boolean addonsPreAppletInit(Object gameInstance) {
        return runLifecycle(gameInstance, true);
    }

    public static boolean addonsPostAppletInit(Object gameInstance) {
        return runLifecycle(gameInstance, false);
    }

    private static boolean runLifecycle(Object gameInstance, boolean pre) {
        for (Iterator<Addon> it = loaded.iterator(); it.hasNext(); ) {
            Addon addon = it.next();
            it.remove();
            try {
                Addon.WhatToDo result = pre
                    ? addon.preAppletInit(gameInstance, loaded)
                    : addon.postAppletInit(gameInstance, loaded);
                if (result == Addon.WhatToDo.STOP_CODE) {
                    return false;
                }
                if (result == Addon.WhatToDo.STOP_LOOP) {
                    break;
                }
            } catch (Throwable t) {
                Logger.error("Addon \"" + addon.getName() + "\" threw in applet init hook", t);
            }
        }
        return true;
    }

    public static List<Addon> getLoaded() {
        return loaded;
    }
}
