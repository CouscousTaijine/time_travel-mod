package com.negger.chronos;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.PersistentState;

/** Persists the fact that the pocket world has already been initialized.
 *  This prevents the starter island/tree from being regenerated after a restart
 *  and therefore preserves everything the player builds, breaks or leaves inside.
 */
public class PortalWorldState extends PersistentState {
    private boolean initialized;

    public PortalWorldState() {
        this(false);
    }

    private PortalWorldState(boolean initialized) {
        this.initialized = initialized;
    }

    public static PortalWorldState fromNbt(NbtCompound nbt) {
        return new PortalWorldState(nbt.getBoolean("initialized"));
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized() {
        if (!initialized) {
            initialized = true;
            markDirty();
        }
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putBoolean("initialized", initialized);
        return nbt;
    }
}
