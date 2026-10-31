package uk.betacraft.legacyfix.patch.impl.misc;

import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;
import uk.betacraft.legacyfix.Logger;
import uk.betacraft.legacyfix.patch.Patcher;
import uk.betacraft.legacyfix.patch.api.Patch;
import uk.betacraft.legacyfix.patch.api.PatchPool;
import uk.betacraft.legacyfix.patch.api.Transformer;

/**
 * Fixes the diagonal "shadow triangle" artifacts on gl4es (Android / Pojav-family launchers).
 *
 * Vanilla Tessellator (beta 1.7.3 onwards) carries a static boolean TRIANGLE_MODE initialized
 * to true in its class initializer: quads are pre-triangulated on the Java side into 6-vertex
 * GL_TRIANGLES streams. gl4es' inColor attribute interpolation breaks on the duplicated
 * diagonal vertices, producing the black wedge. Flipping the flag to false makes the game emit
 * native GL_QUADS, which gl4es tessellates correctly on its own.
 *
 * Opt-in via -Dlf.gl4esquad. The Tessellator is located structurally (obfuscation-name agnostic),
 * using descriptor strings only - never CtType resolution, which would fail in an isolated
 * ClassPool that cannot see the game's other classes:
 *   1. a static field whose descriptor is the class's own type (the singleton instance);
 *   2. a declared static boolean field (descriptor "Z");
 *   3. a method with descriptor starting (III) - addVertex; returns void in beta (b1.7.3:
 *      "(III)V") and int in 1.x ("(III)I"), so both must be accepted;
 *   4. in <clinit>: ICONST_1 immediately followed by PUTSTATIC on a Z field of this class.
 * Only the <clinit> initializer is flipped (same semantics as GL4ES-Fix's javaagent branch).
 */
public class Gl4esQuadPatch extends Patch {
    private static final String ADD_VERTEX_PREFIX = "(III)";

    public Gl4esQuadPatch() {
        super("gl4esquad", "Disables Tessellator pre-triangulation (fixes shadow triangles on gl4es)", false);
    }

    @Override
    public void apply(final PatchPool patchPool) throws Exception {
        patchPool.addTransformer(new Transformer() {
            private boolean applied = false;

            public byte[] transform(String name, byte[] bytecode) throws Exception {
                if (this.applied || name == null) {
                    return null;
                }

                for (String ignore : Patcher.JVM_CLASS_IGNORES) {
                    if (name.startsWith(ignore)) {
                        return null;
                    }
                }

                // Cheap reject: the addVertex descriptor must appear somewhere in the constant
                // pool bytes, otherwise this cannot be the Tessellator. Callers of addVertex
                // also carry the descriptor, but they fail the full fingerprint below.
                if (!containsAscii(bytecode, ADD_VERTEX_PREFIX)) {
                    return null;
                }

                try {
                    CtClass clas = ctFromBytes(bytecode);
                    if (clas == null || clas.isInterface() || clas.isAnnotation() || clas.isFrozen()) {
                        return null;
                    }

                    String selfDescriptor = "L" + clas.getName().replace('.', '/') + ";";

                    boolean hasSelfTypedStatic = false;
                    boolean hasStaticBoolean = false;

                    for (CtField field : clas.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers())) {
                            continue;
                        }

                        String signature = field.getSignature();
                        if (signature.equals(selfDescriptor)) {
                            hasSelfTypedStatic = true;
                        } else if (signature.equals("Z")) {
                            hasStaticBoolean = true;
                        }
                    }

                    if (!hasSelfTypedStatic || !hasStaticBoolean) {
                        return null;
                    }

                    boolean hasAddVertex = false;
                    for (CtMethod method : clas.getDeclaredMethods()) {
                        if (method.getSignature().startsWith(ADD_VERTEX_PREFIX)) {
                            hasAddVertex = true;
                            break;
                        }
                    }

                    if (!hasAddVertex) {
                        return null;
                    }

                    CtConstructor clinit = clas.getClassInitializer();
                    if (clinit == null) {
                        return null;
                    }

                    MethodInfo mi = clinit.getMethodInfo();
                    CodeAttribute ca = mi.getCodeAttribute();
                    if (ca == null) {
                        return null;
                    }

                    ConstPool cp = mi.getConstPool();
                    CodeIterator it = ca.iterator();

                    int flipped = 0;
                    int prevPos = -1;

                    while (it.hasNext()) {
                        int pos = it.next();
                        int opcode = it.byteAt(pos);

                        if (opcode == Opcode.PUTSTATIC && prevPos >= 0) {
                            int index = it.u16bitAt(pos + 1);
                            // ConstPool.getFieldrefClassName normalizes to the DOTTED form
                            // (javassist Utils.toClassName), same as CtClass.getName() - do NOT
                            // slash-convert here; javap's slash display misled an earlier "fix"
                            // into breaking multi-package repak names that already matched.
                            if (clas.getName().equals(cp.getFieldrefClassName(index))
                                && cp.getFieldrefType(index).equals("Z")
                                && it.byteAt(prevPos) == Opcode.ICONST_1
                            ) {
                                // NB: this bundled javassist's writeByte takes (value, pos),
                                // unlike stock javassist - verified against javassist-java5.jar.
                                it.writeByte(Opcode.ICONST_0, prevPos);
                                flipped++;
                                Logger.info("gl4esquad: flipped TRIANGLE_MODE field \""
                                    + cp.getFieldrefName(index) + "\" in " + name);
                            }
                        }

                        prevPos = pos;
                    }

                    if (flipped > 0) {
                        this.applied = true;
                        return clas.toBytecode();
                    }
                } catch (Throwable t) {
                    Logger.error("gl4esquad", t);
                }

                return null;
            }
        });
    }

    private static boolean containsAscii(byte[] data, String needle) {
        byte[] pattern = needle.getBytes();
        int limit = data.length - pattern.length;

        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }

        return false;
    }
}
