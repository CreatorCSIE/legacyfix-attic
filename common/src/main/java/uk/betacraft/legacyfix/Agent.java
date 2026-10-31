package uk.betacraft.legacyfix;

import javassist.*;
import uk.betacraft.legacyfix.addon.AddonLoader;
import uk.betacraft.legacyfix.agent.FabricInjector;
import uk.betacraft.legacyfix.agent.FmlInjector;
import uk.betacraft.legacyfix.agent.LaunchWrapperInjector;
import uk.betacraft.legacyfix.patch.Patcher;
import uk.betacraft.legacyfix.patch.api.CtTransformer;
import uk.betacraft.legacyfix.patch.api.Transformer;
import uk.betacraft.legacyfix.util.BouncyCastleUtils;
import uk.betacraft.legacyfix.util.JvmUtils;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.List;

public class Agent {
    public static final boolean DEBUG;
    public static final String VERSION;
    private static final Map<String, Object> SETTINGS = new HashMap<String, Object>();

    public static boolean loaded = false;
    public static boolean active = false;

    private static String javaStringLiteral(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20 || c == '\u007f') {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public static void premain(String agentArgs, final Instrumentation inst) {
        loaded = true;
        Logger.info("Loading agent (" + VERSION + ")");

        if (LaunchWrapperInjector.inject(inst)) {
            Logger.info("Injected LaunchWrapper transformer");
            return;
        }

        if (FmlInjector.inject()) {
            Logger.info("Injected legacy FML transformer");
            return;
        }

        if (FabricInjector.inject()) {
            Logger.info("Injected Fabric mod");
            return;
        }

        if (Agent.useBouncyCastle()) {
            BouncyCastleUtils.init();
        }

        active = true;
        final Patcher patcher = new Patcher(ClassPool.getDefault());

        // -Dlf.title: force a custom window title in both DeAWT and native LWJGL.
        // Registered before patcher.apply() so that at runtime this override runs
        // AFTER DeAwtPatch's setTitle hook (javassist insertBefore: later-registered
        // code executes first) - lf.deawt.originalTitle keeps capturing the game's
        // own title, so getTitle()-based version detection (older Forge) still works.
        final Object titleSetting = SETTINGS.get("lf.title");
        if (titleSetting != null) {
            final String titleLiteral = javaStringLiteral(String.valueOf(titleSetting));
            patcher.addCtTransformer("org.lwjgl.opengl.Display", new CtTransformer() {
                public void transform(CtClass cc) throws Exception {
                    try {
                        cc.getDeclaredMethod("setTitle").insertBefore("$1 = " + titleLiteral + ";");
                    } catch (Exception ignored) {
                        // Display variant without setTitle: nothing to override.
                    }
                }
            });
        }

        patcher.apply();

        AddonLoader.load();
        AddonLoader.runPatchPhase(patcher);

        Map<String, List<CtTransformer>> ctTransformersMap = patcher.getCtTransformers();

        for (String className : ctTransformersMap.keySet()) {
            try {
                Class<?> loadedClass = Class.forName(className);

                ClassPool pool = ClassPool.getDefault();
                CtClass ctClass = pool.get(className);
                if (ctClass.isFrozen()) {
                    ctClass.defrost();
                }

                for (CtTransformer transformer : ctTransformersMap.get(className)) {
                    transformer.transform(ctClass);
                }

                // Redefine per class, never batched: one class failing verification
                // (e.g. QCL's Display shim rejects javassist output with a bare
                // VerifyError) must not take down the whole agent - a VerifyError
                // escaping premain kills the JVM outright (signal 6).
                inst.redefineClasses(new ClassDefinition(loadedClass, ctClass.toBytecode()));
                ctClass.detach();
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable e) {
                Logger.error("Failed to prepare redefinition for " + className, e);
            }
        }

        inst.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                String dotName;
                if (className != null) {
                    dotName = className.replace('/', '.');
                } else {
                    dotName = null;
                }

                byte[] currentBuffer = classfileBuffer;
                boolean modified = false;

                if (dotName != null) {
                    List<CtTransformer> ctTransformers = patcher.getCtTransformers().get(dotName);
                    if (ctTransformers != null && !ctTransformers.isEmpty()) {
                        try {
                            ClassPool ctPool = new ClassPool(true);
                            ctPool.appendClassPath(new ByteArrayClassPath(dotName, currentBuffer));
                            CtClass ctClass = ctPool.get(dotName);

                            for (CtTransformer ctTransformer : ctTransformers) {
                                ctTransformer.transform(ctClass);
                            }

                            currentBuffer = ctClass.toBytecode();
                            ctClass.detach();
                            modified = true;
                        } catch (Exception e) {
                            Logger.error("Failed to apply CtTransformers on class \"" + dotName + "\"", e);
                        }
                    }
                }

                for (Transformer transformer : patcher.getTransformers()) {
                    try {
                        byte[] result = transformer.transform(dotName, currentBuffer);
                        if (result != null) {
                            currentBuffer = result;
                            modified = true;
                        }
                    } catch (Exception e) {
                        Logger.error("Failed to apply Transformer on class \"" + dotName + "\"", e);
                    }
                }

                return modified ? currentBuffer : null;
            }
        });
    }

    private static ImageIcon icon;
    public static synchronized ImageIcon getIcon() {
        if (icon != null) {
            return icon;
        }

        try {
            URL url = Agent.class.getResource("/assets/legacyfix/icon-outlined.png");

            if (url != null) {
                Image image = new ImageIcon(url).getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                return icon = new ImageIcon(image);
            }
        } catch (Throwable ignored) {
        }
        return icon = new ImageIcon();
    }

    public static Map<String, Object> getSettings() {
        return SETTINGS;
    }

    public static String getSetting(String key) {
        return getSetting(key, null);
    }

    public static String getSetting(String key, String alt) {
        return getSettings().containsKey(key) ? (String) getSettings().get(key) : alt;
    }

    public static int getIntSetting(String key, int alt) {
        Object value = getSettings().get(key);
        if (value == null) {
            return alt;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    public static boolean getBooleanSetting(String key, boolean alt) {
        Object value = getSettings().get(key);
        if (value == null) {
            return alt;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static void setSetting(String key, Object value) {
        SETTINGS.put(key, value);
        System.getProperties().put(key, value);
    }

    public static void removeSetting(String key) {
        SETTINGS.remove(key);
        System.getProperties().remove(key);
    }

    public static boolean hasSetting(String key) {
        return getSettings().containsKey(key);
    }

    private static Boolean hasBouncyCastle;
    public static boolean hasBouncyCastle() {
        if (hasBouncyCastle != null) {
            return hasBouncyCastle;
        }

        try {
            Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider"); // prov
            Class.forName("org.bouncycastle.oer.BitBuilder"); // util
            Class.forName("org.bouncycastle.jsse.provider.BouncyCastleJsseProvider"); // tls
            return hasBouncyCastle = true;
        } catch (LinkageError ignored) {
            return hasBouncyCastle = false;
        } catch (ClassNotFoundException ignored) {
            return hasBouncyCastle = false;
        }
    }

    public static boolean useBouncyCastle() {
        if (hasSetting("lf.bouncycastle")) {
            if (!hasBouncyCastle()) {
                Logger.error("Cannot use Bouncy Castle for TLS -- no Bouncy Castle libraries in classpath");
                return false;
            }
            return true;
        }

        if (hasSetting("lf.bouncycastle.disable")) {
            return false;
        }

        if (!JvmUtils.canThisDoModernTLS()) {
            if (hasBouncyCastle()) {
                return true;
            }

            JvmUtils.printTLSWarning();
        }
        return false;
    }

    static {
        String version;

        try {
            version = new BufferedReader(new InputStreamReader(Agent.class.getResourceAsStream("/version.txt"))).readLine();
        } catch (Throwable ignored) {
            version = "unknown";
        }
        VERSION = version;

        for (Map.Entry<Object, Object> property : System.getProperties().entrySet()) {
            String propertyKey = String.valueOf(property.getKey());
            if (propertyKey.startsWith("lf.") && !SETTINGS.containsKey(propertyKey)) {
                SETTINGS.put(propertyKey, property.getValue());
            }
        }

        DEBUG = hasSetting("lf.debug");
    }
}
