package uk.betacraft.legacyfix.addons.gamemodeswitch;

import sun.misc.Unsafe;
import uk.betacraft.legacyfix.Agent;
import uk.betacraft.legacyfix.Logger;
import uk.betacraft.legacyfix.addon.Addon;
import uk.betacraft.legacyfix.proxy.GameArgs;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Forces creative mode in classic/indev by replacing the Minecraft game-mode field
 * with an allocated creative instance. Core logic ported from the battle-tested
 * Betacraft v1 launcher addon {@code GameModeSwitch} (reflection-based, per-version
 * obfuscated name table). The c0.2* entry of the original table is intentionally
 * dropped: those names are wrong for c0.24 (Minecraft there is {@code k}, not {@code l}).
 */
public class GameModeSwitchAddon extends Addon {

    private enum Era {
        CLASSIC,
        INDEV
    }

    @Override
    public WhatToDo postAppletInit(Object gameInstance, List<Addon> addonsLeft) {
        try {
            if (GameArgs.getValue("server", null) != null) {
                Logger.info("[gamemodeswitch] cannot apply when joining servers.");
                return WhatToDo.NORMAL;
            }

            Object versionSetting = Agent.getSettings().get("lf.version");
            if (versionSetting == null) {
                Logger.info("[gamemodeswitch] no version known (lf.version), skipping.");
                return WhatToDo.NORMAL;
            }
            String version = String.valueOf(versionSetting);

            if (version.startsWith("inf-2010063")) {
                inject(Era.INDEV, gameInstance, "aw", "aj", "bt");
            } else if (version.equals("inf-20100627") || version.equals("inf-20100629")) {
                inject(Era.INDEV, gameInstance, "at", "ah", "bq");
            } else if (version.equals("inf-20100624") || version.startsWith("inf-20100625")) {
                inject(Era.INDEV, gameInstance, "net.minecraft.client.f", "net.minecraft.client.d.b", "net.minecraft.client.d.d");
            } else if (!version.startsWith("in-20100201") && (version.startsWith("in-201002") || version.startsWith("inf-20100227") || version.startsWith("inf-201003"))) {
                inject(Era.INDEV, gameInstance, "net.minecraft.client.d", "net.minecraft.client.d.a", "net.minecraft.client.d.c");
            } else if (version.startsWith("in-201001") || version.startsWith("in-20100201")) {
                inject(Era.INDEV, gameInstance, "net.minecraft.client.d", "net.minecraft.client.c.a", "net.minecraft.client.c.c");
            } else if (version.startsWith("in-20091223")) {
                inject(Era.CLASSIC, gameInstance, "net.minecraft.client.e", "net.minecraft.client.e.a", "net.minecraft.client.e.b");
            } else if (version.startsWith("in-20091231")) {
                inject(Era.INDEV, gameInstance, "net.minecraft.client.e", "net.minecraft.client.e.a", "net.minecraft.client.e.c");
            } else if (version.startsWith("c0.30")) {
                inject(Era.CLASSIC, gameInstance, "com.mojang.minecraft.l", "com.mojang.minecraft.d.b", "com.mojang.minecraft.d.c");
            } else {
                Logger.info("[gamemodeswitch] version \"" + version + "\" not supported, skipping.");
            }
        } catch (Throwable t) {
            Logger.error("gamemodeswitch", t);
        }
        return WhatToDo.NORMAL;
    }

    private void inject(Era era, Object appletInstance, String eMinecraftClassName, String eGamemodeMainClassName, String eGamemode2ClassName) throws Exception {
        ClassLoader classLoader = appletInstance.getClass().getClassLoader();

        for (final Field minecraftField : appletInstance.getClass().getDeclaredFields()) {
            final String name = minecraftField.getType().getName();
            if (name.contains(eMinecraftClassName)) {
                // Access the Minecraft instance
                minecraftField.setAccessible(true);
                Object mc = minecraftField.get(appletInstance);

                // Begin injecting the chosen gamemode...
                final Class<?> clazz = classLoader.loadClass(name);
                for (final Field field1 : clazz.getDeclaredFields()) {
                    if (field1.getType().getName().equals(eGamemodeMainClassName)) {
                        final Class<?> clazz2 = classLoader.loadClass(eGamemode2ClassName);
                        final Class<?> clazz3 = classLoader.loadClass(eGamemodeMainClassName);

                        Field funsafe = Unsafe.class.getDeclaredField("theUnsafe");
                        funsafe.setAccessible(true);
                        Unsafe unsafe = (Unsafe) funsafe.get(null);
                        Object gamemodeInstance = unsafe.allocateInstance(clazz2);

                        // Reproduce the init method which is removed in c0.28 - creative c0.30s
                        if (era == Era.CLASSIC)
                            gamemodeInstance = classicInject(clazz2, clazz3, gamemodeInstance, mc);
                        else if (era == Era.INDEV)
                            gamemodeInstance = indevInject(clazz3, gamemodeInstance, mc);

                        field1.setAccessible(true);
                        field1.set(mc, gamemodeInstance);
                        Logger.info("[gamemodeswitch] creative mode injected.");
                        break;
                    }
                }
            }
        }
    }

    private Object indevInject(final Class<?> clazz, Object gamemodeInstance, Object minecraftInstance) {
        try {
            try {
                Field b = clazz.getDeclaredField("b");
                b.setAccessible(true);
                b.set(gamemodeInstance, true);
            } catch (NoSuchFieldException e) {
                // Some indev builds lack the creative flag; the a=minecraft link below still applies.
            }

            Field a = clazz.getDeclaredField("a");
            a.setAccessible(true);
            a.set(gamemodeInstance, minecraftInstance);
            return gamemodeInstance;
        } catch (Throwable t) {
            Logger.error("gamemodeswitch", t);
        }
        return null;
    }

    private Object classicInject(final Class<?> clazz, final Class<?> clazz2, Object gamemodeInstance, Object minecraftInstance) {
        try {
            Field c = clazz.getDeclaredField("c");
            Field d = clazz.getDeclaredField("d");
            Field e = clazz.getDeclaredField("e");
            Field f = clazz.getDeclaredField("f");
            Field g = clazz.getDeclaredField("g");
            Field h = clazz.getDeclaredField("h");
            Field a = clazz2.getDeclaredField("a");

            c.setAccessible(true);
            d.setAccessible(true);
            e.setAccessible(true);
            f.setAccessible(true);
            g.setAccessible(true);
            h.setAccessible(true);
            a.setAccessible(true);

            c.set(gamemodeInstance, -1);
            d.set(gamemodeInstance, -1);
            e.set(gamemodeInstance, -1);
            f.set(gamemodeInstance, 0);
            g.set(gamemodeInstance, 0);
            h.set(gamemodeInstance, 0);
            a.set(gamemodeInstance, minecraftInstance);
            return gamemodeInstance;
        } catch (Throwable t) {
            Logger.error("gamemodeswitch", t);
        }
        return null;
    }
}
