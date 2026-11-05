package ml.mypals.litematicafastmove.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import fi.dy.masa.litematica.gui.GuiConfigs;
import net.minecraft.client.gui.screens.Screen;

/**
 * ModMenu 入口：直接打开 Litematica 自带的配置界面。
 *
 * FastMove 的选项本来就注册在 malilib (随 Litematica 存取 litematica.json)，并由 MixinGuiConfigs
 * 追加到 Generic 页和 All 页末尾，这里无需再另建一套 GUI。Litematica 是硬依赖，因此不需要运行时判空。
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<Screen> getModConfigScreenFactory() {
        return parent -> new GuiConfigs().setParent(parent);
    }
}
