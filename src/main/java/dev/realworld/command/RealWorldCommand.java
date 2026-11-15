package dev.realworld.command;

import dev.realworld.osm.OsmService;
import dev.realworld.settings.EarthSettings;
import dev.realworld.world.EarthChunkGenerator;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.world.gen.chunk.ChunkGenerator;

/** /realworld — inspect the Earth settings of the current world. */
public final class RealWorldCommand {
	private RealWorldCommand() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("realworld")
						.executes(context -> info(context.getSource()))
						.then(CommandManager.literal("info").executes(context -> info(context.getSource())))
						.then(CommandManager.literal("osmreload")
								.requires(source -> source.hasPermissionLevel(3))
								.executes(context -> {
									OsmService.clearCaches();
									context.getSource().sendFeedback(
											() -> Text.literal("RealWorld: OSM/elevation caches cleared; new chunks will re-download data."), true);
									return 1;
								}))
				));
	}

	private static int info(ServerCommandSource source) {
		ChunkGenerator generator = source.getWorld().getChunkManager().getChunkGenerator();
		if (generator instanceof EarthChunkGenerator earth) {
			EarthSettings s = earth.getSettings();
			source.sendFeedback(() -> Text.literal(("RealWorld Earth settings:\n"
					+ " Centre: %.5f, %.5f | scale 1:%d | selection %s | outside %s\n"
					+ " Elevation: %s x%.2f | sea level Y %d | smoothing %d\n"
					+ " Trees %s (%d%%) | buildings %s (%s x%.2f) | roads %s (x%.2f) | farmland %s\n"
					+ " Oceans %s | rivers %s | lakes %s | water depth %d").formatted(
					s.region().centerLat(), s.region().centerLon(), s.region().scale(), s.region().selectionMode(), s.region().outsideRegion(),
					s.terrain().elevationSource(), s.terrain().elevationExaggeration(), s.terrain().seaLevel(), s.terrain().smoothing(),
					onOff(s.nature().trees()), s.nature().treeDensity(),
					onOff(s.structures().buildings()), s.structures().buildingStyle(), s.structures().buildingHeightScale(),
					onOff(s.structures().roads()), s.structures().roadWidthScale(), onOff(s.structures().farmland()),
					onOff(s.water().oceans()), onOff(s.water().rivers()), onOff(s.water().lakes()), s.water().waterDepth()
			)), false);
		} else {
			source.sendFeedback(() -> Text.literal("This world was not generated with the RealWorld Earth generator."), false);
		}
		return 1;
	}

	private static String onOff(boolean b) { return b ? "ON" : "OFF"; }
}
