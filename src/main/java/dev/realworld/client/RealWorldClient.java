package dev.realworld.client;

import dev.realworld.client.screen.EarthSettingsScreen;
import dev.realworld.config.EarthConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint: a keybind (default G) that opens the Earth settings
 * anywhere. Outside the create-world screen it edits the saved defaults
 * (config/realworld.json) used by the next Earth world / dedicated server.
 */
public class RealWorldClient implements ClientModInitializer {

	private static KeyBinding openSettingsKey;

	@Override
	public void onInitializeClient() {
		openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.realworld.open_settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.realworld"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openSettingsKey.wasPressed()) {
				if (client.currentScreen == null) {
					client.setScreen(new EarthSettingsScreen(null, EarthConfig.current(), settings -> {
						EarthConfig.save(settings);
						if (client.player != null) {
							client.player.sendMessage(Text.literal(
									"RealWorld: Earth defaults saved. They apply to the next Earth world you create."), false);
						}
					}));
				}
			}
		});
	}
}
