package uk.betacraft.legacyfix.patch.impl.misc;

import javassist.CtClass;
import javassist.CtConstructor;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;
import uk.betacraft.legacyfix.Agent;
import uk.betacraft.legacyfix.Logger;
import uk.betacraft.legacyfix.patch.Patcher;
import uk.betacraft.legacyfix.patch.api.Patch;
import uk.betacraft.legacyfix.patch.api.PatchPool;
import uk.betacraft.legacyfix.patch.api.Transformer;

import java.util.ArrayList;

/**
 * Deterministic (mapping-table) variant of the gl4es quad fix - the primary route for
 * launchers with a closed, curated version set (Seashore). Where Gl4esQuadPatch locates
 * the Tessellator by structural fingerprint, this patch matches the launch version id
 * against the full version -> Tessellator class table ported from GL4ES-Fix v1.0.1
 * (com.itselix99), whose ASM visitor hardcodes the field name "b" (descriptor Z) across
 * the whole family - so one class name per version is all the table needs.
 *
 * Key forms (as in the source tool): exact ids, "x" wildcards ("b1.7.x"), and closed
 * lexicographic ranges ("a1.2.5-a1.2.3"). Version ids are normalized '-' -> '_' so
 * launcher ids like "inf-20100618" match keys like "inf_20100625-inf_20100611".
 * Entries are consulted exact -> wildcard -> range, ranges in most-specific order, since
 * the infdev ranges overlap the broad "a1.0.3-inf_20100630" one.
 *
 * Opt-in via -Dlf.gl4esquadtable. Version comes from lf.version (GameArgs captures
 * --version; set -Dlf.version=<id> explicitly if the launcher does not pass it).
 * The clinit pattern (ICONST_1;PUTSTATIC b:Z) is still verified before flipping - a
 * table hit whose bytecode no longer matches means a wrong row/source family, and we
 * refuse to guess; the fingerprint patch remains the fallback.
 */
public class Gl4esQuadTablePatch extends Patch {
    private static final String FIELD = "b";

    // version key -> Tessellator class (dotted), ported 1:1 from GL4ES-Fix v1.0.1
    private static final String[][] ENTRIES = {
        {"b1.7.x", "nw"},
        {"b1.6.x", "ns"},
        {"b1.5.x", "na"},
        {"b1.4.x", "lj"},
        {"b1.3.x", "kv"},
        {"b1.2.x", "jy"},
        {"b1.1.x", "jg"},
        {"b1.0.x", "jf"},
        {"a1.2.6", "is"},
        {"a1.2.5-a1.2.3", "ir"},
        {"a1.2.2", "ip"},
        {"a1.2.1_01-a1.2.0", "ij"},
        {"a1.1.x", "ho"},
        {"a1.0.17_04-a1.0.17_02", "hn"},
        {"a1.0.16", "he"},
        {"a1.0.15", "hd"},
        {"a1.0.14", "ha"},
        {"a1.0.13", "gy"},
        {"a1.0.12-a1.0.11", "gx"},
        {"a1.0.10", "gr"},
        {"a1.0.9", "go"},
        {"a1.0.8_01", "gn"},
        {"a1.0.7", "gl"},
        {"a1.0.6", "gj"},
        {"a1.0.5_01", "fz"},
        {"a1.0.5", "fy"},
        {"a1.0.4", "ai"},
        // Ranges, most specific first (the two narrow infdev ranges sit inside the
        // broad a1.0.3-inf_20100630 span; the last row is the BetaCraft repak name).
        {"inf_20100625-inf_20100611", "net.minecraft.client.a.d"},
        {"inf_20100629-inf_20100627", "ae"},
        {"a1.0.3-inf_20100630", "ag"},
    };

    public Gl4esQuadTablePatch() {
        super("gl4esquadtable", "Disables Tessellator pre-triangulation via version table (gl4es shadow triangles)", false);
    }

    private static String classForVersion(String rawId) {
        String id = rawId.replace('-', '_');
        // exact
        for (String[] e : ENTRIES) {
            if (!e[0].contains("-") && !e[0].endsWith(".x") && e[0].equals(id)) {
                return e[1];
            }
        }
        // wildcard "b1.7.x" style
        for (String[] e : ENTRIES) {
            if (e[0].endsWith(".x") && id.startsWith(e[0].substring(0, e[0].length() - 1))) {
                return e[1];
            }
        }
        if (id.length() == 0) {
            return null;
        }

        // closed ranges, lexicographic (endpoints share shape/length by construction).
        // Lexicographic order only ranks ids of the SAME family: without the
        // leading-char guard, the broad "a1.0.3-inf_20100630" span swallows
        // "b1.9"/"c0.30" (a < b < c < inf_...). Require the id to start with
        // one of the endpoint initials.
        char first = id.charAt(0);
        for (String[] e : ENTRIES) {
            if (!e[0].contains("-") || e[0].endsWith(".x")) {
                continue;
            }
            String[] ends = e[0].split("-");
            if (first != ends[0].charAt(0) && first != ends[1].charAt(0)) {
                continue;
            }
            if (id.compareTo(ends[0]) >= 0 && id.compareTo(ends[1]) <= 0
                || id.compareTo(ends[1]) >= 0 && id.compareTo(ends[0]) <= 0) {
                return e[1];
            }
        }
        return null;
    }

    @Override
    public void apply(final PatchPool patchPool) throws Exception {
        patchPool.addTransformer(new Transformer() {
            private boolean applied = false;
            private boolean warned = false;

            public byte[] transform(String name, byte[] bytecode) throws Exception {
                if (this.applied || name == null) {
                    return null;
                }

                for (String ignore : Patcher.JVM_CLASS_IGNORES) {
                    if (name.startsWith(ignore)) {
                        return null;
                    }
                }

                Object version = Agent.getSettings().get("lf.version");
                if (version == null) {
                    if (!this.warned) {
                        this.warned = true;
                        Logger.error("gl4esquadtable: lf.version unknown - pass --version or set -Dlf.version=<id>, table inactive");
                    }
                    return null;
                }

                String expected = classForVersion(version.toString());
                if (expected == null || !expected.equals(name)) {
                    return null; // version unknown, or class is not this version's Tessellator
                }

                try {
                    CtClass clas = ctFromBytes(bytecode);
                    if (clas == null || clas.isInterface() || clas.isAnnotation() || clas.isFrozen()) {
                        return null;
                    }

                    CtConstructor clinit = clas.getClassInitializer();
                    if (clinit == null) {
                        Logger.error("gl4esquadtable: no <clinit> in " + name + " - table row is wrong for this jar");
                        return null;
                    }

                    MethodInfo mi = clinit.getMethodInfo();
                    CodeAttribute ca = mi.getCodeAttribute();
                    ConstPool cp = mi.getConstPool();
                    CodeIterator it = ca.iterator();

                    int flipped = 0;
                    int prevPos = -1;

                    while (it.hasNext()) {
                        int pos = it.next();
                        int opcode = it.byteAt(pos);

                        if (opcode == Opcode.PUTSTATIC && prevPos >= 0) {
                            int index = it.u16bitAt(pos + 1);
                            // ConstPool.getFieldrefClassName is DOTTED, same as the transformer name.
                            if (name.equals(cp.getFieldrefClassName(index))
                                && cp.getFieldrefType(index).equals("Z")
                                && FIELD.equals(cp.getFieldrefName(index))
                                && it.byteAt(prevPos) == Opcode.ICONST_1
                            ) {
                                // NB: this bundled javassist's writeByte takes (value, pos).
                                it.writeByte(Opcode.ICONST_0, prevPos);
                                flipped++;
                            }
                        }

                        prevPos = pos;
                    }

                    if (flipped > 0) {
                        this.applied = true;
                        Logger.info("gl4esquadtable: flipped TRIANGLE_MODE field \"" + FIELD + "\" in " + name
                            + " (version " + version + ")");
                        return clas.toBytecode();
                    }

                    // Table hit but pattern absent: the jar's bytecode differs from the table's
                    // expectation (wrong family/source). Refuse to guess; fingerprint patch can cover.
                    Logger.error("gl4esquadtable: table hit \"" + name + "." + FIELD + "\" but no "
                        + "ICONST_1;PUTSTATIC in <clinit> - row mismatch, leaving class untouched");
                } catch (Throwable t) {
                    Logger.error("gl4esquadtable", t);
                }

                return null;
            }
        });
    }
}
