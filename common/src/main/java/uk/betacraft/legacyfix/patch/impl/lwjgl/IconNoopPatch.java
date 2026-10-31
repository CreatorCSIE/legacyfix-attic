package uk.betacraft.legacyfix.patch.impl.lwjgl;

import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import uk.betacraft.legacyfix.patch.api.CtTransformer;
import uk.betacraft.legacyfix.patch.api.Patch;
import uk.betacraft.legacyfix.patch.api.PatchPool;

/**
 * Opt-in (-Dlf.iconnoop) no-op for Display.setIcon, for runtimes whose GLFW shim
 * cannot accept window icons. On Pojav-family Android launchers, QCL's bundled
 * LaunchWrapper injector (net.minecraft.launchwrapper.injector.VanillaTweakInjector
 * .loadIconsOnFrames) calls Display.setIcon during startup; the shim constructs a
 * GLFWImage buffer over a null pointer and throws NullPointerException, killing
 * indev/alpha launches (inf-20100618) before any world class is loaded. Icons are
 * cosmetic on Android, so the whole call is skipped when the flag is set.
 * Desktop (Attic) never sets the flag and keeps its DeAWT window icon.
 */
public class IconNoopPatch extends Patch {
    public IconNoopPatch() {
        super("iconnoop", "No-ops Display.setIcon (Pojav GLFW shim throws NPE on window icons)", false);
    }

    @Override
    public void apply(PatchPool patchPool) throws Exception {
        patchPool.addCtTransformer("org.lwjgl.opengl.Display", new CtTransformer() {
            public void transform(CtClass ctClass) throws Exception {
                CtMethod setIconMethod;
                try {
                    setIconMethod = ctClass.getDeclaredMethod("setIcon");
                } catch (NotFoundException e) {
                    return;
                }
                setIconMethod.insertBefore("return;");
            }
        });
    }
}
