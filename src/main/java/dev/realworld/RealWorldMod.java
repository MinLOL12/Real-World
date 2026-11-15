package dev.realworld;

import dev.realworld.command.RealWorldCommand;
import dev.realworld.config.EarthConfig;
import dev.realworld.world.EarthChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RealWorld: import real places on Earth into Minecraft.
 *
 * <p>Pick the "Earth" world type when creating a world, open the Earth
 * settings (button in the top-right of the create-world screen), choose an
 * area on the OpenStreetMap map with a box or lasso selection, tune 27
 * settings in 5 categories, and generate a world with real terrain, water,
 * trees, roads and buildings.</p>
 */
public class RealWorldMod implements ModInitializer {
	public static final String MOD_ID = "realworld";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		Registry.register(Registries.CHUNK_GENERATOR, id("earth"), EarthChunkGenerator.CODEC);
		EarthConfig.load();
		RealWorldCommand.register();
		LOGGER.info("RealWorld initialised: Earth world type registered (data: OpenStreetMap + Open-Meteo)");
	}
}
