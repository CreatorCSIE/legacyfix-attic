package uk.betacraft.legacyfix.addons.fullscreen;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.Opcode;
import uk.betacraft.legacyfix.addon.Addon;
import uk.betacraft.legacyfix.patch.GameClasses;
import uk.betacraft.legacyfix.patch.api.CtTransformer;
import uk.betacraft.legacyfix.patch.api.PatchPool;

/**
 * Fullscreen from game start (Betacraft v1 addon semantics).
 *
 * A one-shot flag on the first tick calls Display.setFullscreen(true) exactly once,
 * before the player sees anything. No runtime toggle (e.g. F11) is offered: old
 * classic/indev builds do not survive a mid-run display-mode switch, so switching
 * after startup would risk GL context loss / black screens.
 *
 * Mount point is discovered like upstream: game loop method (public no-arg void
 * run(), fallback = body calling Display.update()/Keyboard.next()), then the
 * per-tick method invoked from it. The call is guarded by try/catch so an
 * unsupported display mode never kills the game.
 */
public class FullscreenAddon extends Addon {
    public void patch(final PatchPool pool) throws Exception {
        final String mcName = GameClasses.findMinecraftClass(pool);
        if (mcName == null) {
            throw new Exception("Minecraft class not found");
        }

        pool.addCtTransformer(mcName, new CtTransformer() {
            public void transform(CtClass cc) throws Exception {
                CtMethod tick = findTickMethod(cc);

                try {
                    cc.addField(CtField.make("private static boolean lf$fullscreen_done = false;", cc));
                } catch (CannotCompileException e) {
                    throw new Exception("Failed to add one-shot guard field", e);
                }

                // Dual path. Under DeAWT the user-visible window is the frame
                // ANCESTOR OF THE GAME CANVAS (the launcher embeds it there);
                // Frame.getFrames()[0] can hit the game's own hidden internal
                // frame instead - DeAwtPatch's FrameHider keeps it hidden and
                // would bounce fullscreen straight back (setVisible(false) on
                // SHOWING_CHANGED). So walk canvas.getParent() to the frame and
                // strip any FrameHider listeners as a belt-and-braces measure.
                // With no AWT canvas present (native LWJGL), use Display.setFullscreen.
                String canvasField = findCanvasFieldName(cc);
                String awtPath =
                    "    java.awt.Component lf$fs_comp = " + (canvasField == null ? "null" : ("this." + canvasField)) + ";" +
                    "    java.awt.Frame lf$fs_frame = null;" +
                    "    if (lf$fs_comp != null) {" +
                    "        java.awt.Container lf$fs_p = lf$fs_comp.getParent();" +
                    "        while (lf$fs_p != null && !(lf$fs_p instanceof java.awt.Frame)) { lf$fs_p = lf$fs_p.getParent(); }" +
                    "        if (lf$fs_p != null) { lf$fs_frame = (java.awt.Frame) lf$fs_p; }" +
                    "    }" +
                    "    if (lf$fs_frame != null) {" +
                    "        java.awt.event.HierarchyListener[] lf$fs_ls = lf$fs_frame.getHierarchyListeners();" +
                    "        for (int lf$fs_i = 0; lf$fs_i < lf$fs_ls.length; lf$fs_i++) {" +
                    "            if (\"uk.betacraft.legacyfix.patch.impl.lwjgl.DeAwtPatch$FrameHider\".equals(lf$fs_ls[lf$fs_i].getClass().getName())) { lf$fs_frame.removeHierarchyListener(lf$fs_ls[lf$fs_i]); }" +
                    "        }" +
                    "        try {" +
                    "            java.awt.GraphicsDevice lf$fs_dev = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();" +
                    "            if (lf$fs_dev.isFullScreenSupported()) { lf$fs_dev.setFullScreenWindow(lf$fs_frame); }" +
                    "        } catch (Throwable ignored) {}" +
                    "    } else {" +
                    "        try { org.lwjgl.opengl.Display.setFullscreen(true); } catch (Throwable ignored) {}" +
                    "    }";
                tick.insertBefore(
                    "if (!lf$fullscreen_done) {" +
                    "    lf$fullscreen_done = true;" +
                    awtPath +
                    "}");
            }
        });
    }

    private static String findCanvasFieldName(CtClass cc) throws Exception {
        javassist.CtField[] fields = cc.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            if ("java.awt.Canvas".equals(fields[i].getType().getName())) {
                return fields[i].getName();
            }
        }
        return null;
    }

    private static CtMethod findRunLoopMethod(CtClass cc) throws Exception {
        CtMethod[] methods = cc.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            CtMethod m = methods[i];
            if ("run".equals(m.getName())
                && Modifier.isPublic(m.getModifiers())
                && m.getParameterTypes().length == 0
                && "void".equals(m.getReturnType().getName())) {
                return m;
            }
        }

        for (int i = 0; i < methods.length; i++) {
            if (isGameLoopBody(methods[i])) {
                return methods[i];
            }
        }

        throw new Exception("Game loop method not found in " + cc.getName());
    }

    private static boolean isGameLoopBody(CtMethod m) throws Exception {
        if (m.getParameterTypes().length != 0 || !"void".equals(m.getReturnType().getName())) {
            return false;
        }
        CodeAttribute ca = m.getMethodInfo().getCodeAttribute();
        if (ca == null) {
            return false;
        }

        ConstPool cp = m.getMethodInfo().getConstPool();
        CodeIterator it = ca.iterator();
        while (it.hasNext()) {
            int pos = it.next();
            int op = it.byteAt(pos);
            if (op != Opcode.INVOKESTATIC && op != Opcode.INVOKEVIRTUAL) {
                continue;
            }
            int idx = it.u16bitAt(pos + 1);
            String cls = cp.getMethodrefClassName(idx);
            String name = cp.getMethodrefName(idx);
            if ("update".equals(name) && cls.endsWith("opengl/Display")) {
                return true;
            }
            if ("next".equals(name) && cls.endsWith("input/Keyboard")) {
                return true;
            }
        }
        return false;
    }

    private static CtMethod findTickMethod(CtClass cc) throws Exception {
        CtMethod run = findRunLoopMethod(cc);
        CodeAttribute ca = run.getMethodInfo().getCodeAttribute();
        if (ca == null) {
            throw new Exception("Game loop method has no code attribute");
        }

        ConstPool cp = run.getMethodInfo().getConstPool();
        CodeIterator it = ca.iterator();
        CtMethod best = null;

        while (it.hasNext()) {
            int pos = it.next();
            int op = it.byteAt(pos);
            if (op != Opcode.INVOKESPECIAL && op != Opcode.INVOKEVIRTUAL) {
                continue;
            }
            int idx = it.u16bitAt(pos + 1);
            String cls = cp.getMethodrefClassName(idx).replace('/', '.');
            if (!cc.getName().equals(cls) || !"()V".equals(cp.getMethodrefType(idx))) {
                continue;
            }
            String name = cp.getMethodrefName(idx);
            if (name.equals(run.getName())) {
                continue;
            }

            CtMethod candidate;
            try {
                candidate = cc.getDeclaredMethod(name);
            } catch (NotFoundException e) {
                continue;
            }
            if (candidate.getMethodInfo().getCodeAttribute() == null) {
                continue; // abstract/native/bridge stub: no body to inject into
            }

            if (best == null) {
                best = candidate;
            }
            if (name.indexOf("tick") >= 0 || name.indexOf("Tick") >= 0) {
                best = candidate;
                break;
            }
        }

        if (best == null) {
            throw new Exception("Per-tick method not found in " + cc.getName());
        }
        return best;
    }
}
