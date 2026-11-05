package ml.mypals.litematicafastmove.util;

import fi.dy.masa.litematica.config.Configs;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;

/**
 * 功能名：Litematica 版本兼容
 * Tolerant access to litematica APIs that exist in 0.27.11+ but not in 0.27.10 (the
 * version this fork's runtime instance pins to for ecosystem compatibility). When the
 * host litematica lacks the config, behavior falls back to litematica's own default
 * for it (false), which equals 0.27.10's native semantics: crop-age differences count
 * as mismatches.
 */
public final class LitematicaCompat {
    private static final Field IGNORE_CROP_AGE = resolveIgnoreCropAge();

    private LitematicaCompat() {
    }

    private static Field resolveIgnoreCropAge() {
        try {
            return Configs.Visuals.class.getField("IGNORE_CROP_AGE");
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** True only when the host litematica has the config and the user enabled it. */
    public static boolean ignoreCropAge() {
        if (IGNORE_CROP_AGE == null) {
            return false;
        }
        try {
            return ((fi.dy.masa.malilib.config.options.ConfigBoolean) IGNORE_CROP_AGE.get(null)).getBooleanValue();
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    /** Port of litematica 0.27.14 BlockUtils.areStatesEqualIgnoringAge (absent in 0.27.10). */
    public static boolean areStatesEqualIgnoringAge(BlockState stateSchematic, BlockState stateClient) {
        if (stateSchematic.equals(stateClient)) {
            return true;
        }
        if (!stateSchematic.getBlock().equals(stateClient.getBlock())) {
            return false;
        }
        for (var property : stateSchematic.getProperties()) {
            if (property.getName().equalsIgnoreCase("age")) {
                continue;
            }
            if (!stateSchematic.getValue(property).equals(stateClient.getValue(property))) {
                return false;
            }
        }
        return true;
    }
}
