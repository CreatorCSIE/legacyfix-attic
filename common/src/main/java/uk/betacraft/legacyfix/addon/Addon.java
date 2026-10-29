package uk.betacraft.legacyfix.addon;

import uk.betacraft.legacyfix.patch.api.PatchPool;

import java.util.ArrayList;
import java.util.List;

/**
 * Addon API, isomorphic to Betacraft v1 {@code org.betacraft.Addon}, with the host
 * context changed from the launcher Wrapper to the LegacyFix agent.
 *
 * Entry classes are declared per addon jar via the {@code LegacyFix-Addon} manifest
 * attribute and loaded by {@link AddonLoader} from the {@code -Dlf.addons=} property.
 */
public abstract class Addon {
    public enum WhatToDo { STOP_LOOP, NORMAL, STOP_CODE }

    /** Deduplication key, simple class name (same as v1). */
    public String getName() {
        return getClass().getSimpleName();
    }

    /** Dependency graph: addons which must be loaded before this one (same as v1). */
    public List<String> applyAfter() {
        return new ArrayList<String>();
    }

    /**
     * Patch phase (premain, after {@code Patcher.apply()}): register
     * CtTransformer/Transformer instances into the PatchPool.
     */
    public void patch(PatchPool pool) throws Exception {
    }

    /** Applet lifecycle hooks, same names and return type as v1. */
    public WhatToDo preAppletInit(Object gameInstance, List<Addon> addonsLeft) {
        return WhatToDo.NORMAL;
    }

    public WhatToDo postAppletInit(Object gameInstance, List<Addon> addonsLeft) {
        return WhatToDo.NORMAL;
    }
}
