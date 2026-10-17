package dev.lavaesp;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu entry point: the "configure" button on the Lava ESP card opens {@link LavaEspScreen}.
 *
 * <p>Only loaded when Mod Menu is installed; {@code fabric.mod.json} declares it under the
 * {@code modmenu} entrypoint and lists Mod Menu as a suggestion, so the mod itself has no hard
 * dependency on it.
 */
public final class LavaEspModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return LavaEspScreen::new;
    }
}
