package dev.dawoud.simpleesp;

import com.mojang.blaze3d.platform.InputConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class SimpleEspClient implements ClientModInitializer {
	public static final String MOD_ID = "simple-esp";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static KeyMapping openMenuKey;
	private static KeyMapping toggleKey;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		EspConfig.load();

		// 26.3 uses SDL: key codes are InputConstants.KEY_* (SDL scancodes), not GLFW codes.
		KeyMapping.Category category = KeyMapping.Category.register(id("main"));
		openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.simple-esp.open_menu", InputConstants.KEY_RSHIFT, category));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.simple-esp.toggle", InputConstants.KEY_BACKSLASH, category));

		ClientTickEvents.END_CLIENT_TICK.register(SimpleEspClient::tick);
		EspRenderer.init();
	}

	private static void tick(Minecraft client) {
		while (openMenuKey.consumeClick()) {
			if (client.player != null) {
				client.gui.setScreen(new EspScreen());
			}
		}
		while (toggleKey.consumeClick()) {
			EspConfig cfg = EspConfig.get();
			cfg.enabled = !cfg.enabled;
			cfg.save();
			if (client.player != null) {
				client.player.sendSystemMessage(Component.literal("Simple ESP: " + (cfg.enabled ? "ON" : "OFF")));
			}
		}
		EspRenderer.tick(client);
	}
}
