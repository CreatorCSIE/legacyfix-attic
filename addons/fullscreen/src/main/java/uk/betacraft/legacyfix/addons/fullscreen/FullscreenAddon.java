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

                // Target selection, third time (learned from real-device bounces):
                // - canvas.getParent() chain hits the game's OWN internal frame,
                //   which DeAWT keeps hidden via FrameHider; the user's picture
                //   lives in a different, visible window. Fullscreening the
                //   hidden one shows a contentless window (attic.3 bug).
                // - Frame.getFrames()[0] has the same problem (attic.2 bug).
                // So: fullscreen the frame that is actually SHOWING, and strip
                // any FrameHider listeners from it so nothing bounces it back.
                // If the game has an AWT canvas but no visible frame yet, retry
                // next tick (one-shot only on success). No canvas field at all =
                // native LWJGL path -> Display.setFullscreen.
                String canvasField = findCanvasFieldName(cc);
                if (canvasField == null) {
                    tick.insertBefore(
                        "if (!lf$fullscreen_done) {" +
                        "    lf$fullscreen_done = true;" +
                        "    try { org.lwjgl.opengl.Display.setFullscreen(true); } catch (Throwable ignored) {}" +
                        "}");
                    return;
                }
                tick.insertBefore(
                    "if (!lf$fullscreen_done) {" +
                    "    if (this." + canvasField + " == null) { lf$fullscreen_done = true; }" +
                    "    else {" +
                    "        java.awt.Frame[] lf$fs_frames = java.awt.Frame.getFrames();" +
                    "        java.awt.Frame lf$fs_frame = null;" +
                    "        for (int lf$fs_i = 0; lf$fs_i < lf$fs_frames.length; lf$fs_i++) {" +
                    "            java.awt.Frame lf$fs_f = lf$fs_frames[lf$fs_i];" +
                    "            if (lf$fs_f.isDisplayable() && lf$fs_f.isShowing()) { lf$fs_frame = lf$fs_f; break; }" +
                    "        }" +
                    "        if (lf$fs_frame != null) {" +
                    "            lf$fullscreen_done = true;" +
                    "            java.awt.event.HierarchyListener[] lf$fs_ls = lf$fs_frame.getHierarchyListeners();" +
                    "            for (int lf$fs_j = 0; lf$fs_j < lf$fs_ls.length; lf$fs_j++) {" +
                    "                if (\"uk.betacraft.legacyfix.patch.impl.lwjgl.DeAwtPatch$FrameHider\".equals(lf$fs_ls[lf$fs_j].getClass().getName())) { lf$fs_frame.removeHierarchyListener(lf$fs_ls[lf$fs_j]); }" +
                    "            }" +
                    "            try {" +
                    "                java.awt.GraphicsDevice lf$fs_dev = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();" +
                    "                if (lf$fs_dev.isFullScreenSupported()) { lf$fs_dev.setFullScreenWindow(lf$fs_frame); }" +
                    "            } catch (Throwable ignored) {}" +
                    "        }" +
                    "    }" +
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
