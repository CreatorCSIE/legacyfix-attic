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

                tick.insertBefore(
                    "if (!lf$fullscreen_done) {" +
                    "    lf$fullscreen_done = true;" +
                    "    try { org.lwjgl.opengl.Display.setFullscreen(true); }" +
                    "    catch (Throwable ignored) {}" +
                    "}");
            }
        });
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
