package uk.betacraft.legacyfix.addons.quitgame;

import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
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
 * Returning to the launcher from in-game, ported from the Betacraft v1 QuitGame addon.
 *
 * v1 semantics: the launcher's "Quit Game" button ran reflection code that located the
 * game's running flag (the first public primitive boolean field that is true) and set
 * it to false, ending the game loop cleanly. LegacyFix runs the game in its own process,
 * so the equivalent of that button is closing the game window: on a close request the
 * discovered running flag is set to false (loop exits cleanly, the game's own shutdown
 * /save paths run), and when the loop method returns the JVM exits so the launcher
 * regains control instead of hanging on a dead AWT frame.
 *
 * Running-flag detection (mirrors the v1 heuristic at bytecode level):
 *   1. boolean field of this class assigned ICONST_1 inside the game loop method;
 *   2. same, inside a constructor;
 *   3. fallback: first public primitive boolean field (v1's own rule).
 */
public class QuitGameAddon extends Addon {
    public void patch(final PatchPool pool) throws Exception {
        final String mcName = GameClasses.findMinecraftClass(pool);
        if (mcName == null) {
            throw new Exception("Minecraft class not found");
        }

        pool.addCtTransformer(mcName, new CtTransformer() {
            public void transform(CtClass cc) throws Exception {
                CtMethod run = findRunLoopMethod(cc);
                CtMethod tick = findTickMethod(cc, run);
                String running = findRunningField(cc, run);

                tick.insertBefore(
                    "if (org.lwjgl.opengl.Display.isCreated() && org.lwjgl.opengl.Display.isCloseRequested()) {" +
                    "    this." + running + " = false;" +
                    "}");

                run.insertAfter("System.exit(0);");
            }
        });
    }

    private static String findRunningField(CtClass cc, CtMethod run) throws Exception {
        String name = scanForTrueAssignment(cc, run);
        if (name != null) {
            return name;
        }

        CtConstructor[] cons = cc.getConstructors();
        for (int i = 0; i < cons.length; i++) {
            name = scanForTrueAssignment(cc, cons[i]);
            if (name != null) {
                return name;
            }
        }

        // v1 fallback: first public primitive boolean field.
        CtField[] fields = cc.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            if (Modifier.isPublic(fields[i].getModifiers())
                && fields[i].getType() == CtClass.booleanType) {
                return fields[i].getName();
            }
        }

        throw new Exception("Running flag field not found in " + cc.getName());
    }

    /** Field of this class with descriptor Z assigned ICONST_1 just before the PUTFIELD. */
    private static String scanForTrueAssignment(CtClass cc, CtBehavior m) throws Exception {
        CodeAttribute ca = m.getMethodInfo().getCodeAttribute();
        if (ca == null) {
            return null;
        }

        ConstPool cp = m.getMethodInfo().getConstPool();
        CodeIterator it = ca.iterator();
        int prevOp = -1;
        while (it.hasNext()) {
            int pos = it.next();
            int op = it.byteAt(pos);

            if (op == Opcode.PUTFIELD) {
                int idx = it.u16bitAt(pos + 1);
                if (cc.getName().equals(cp.getFieldrefClassName(idx).replace('/', '.'))
                    && "Z".equals(cp.getFieldrefType(idx))
                    && prevOp == Opcode.ICONST_1) {
                    return cp.getFieldrefName(idx);
                }
            }
            prevOp = op;
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

    private static CtMethod findTickMethod(CtClass cc, CtMethod run) throws Exception {
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
