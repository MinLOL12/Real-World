package dev.realworld.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.realworld.RealWorldMod;
import dev.realworld.settings.EarthSettings;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists the last-used Earth settings to {@code config/realworld.json}.
 *
 * <p>When a chunk generator is created with the built-in preset defaults,
 * the config file (if present) transparently replaces those defaults.
 * That gives dedicated servers a way to pick their Earth area without a
 * GUI: edit the JSON (same shape as the world-preset settings), drop it in
 * the config folder, and create a world with the Earth world type.</p>
 */
public final class EarthConfig {
	private EarthConfig() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static EarthSettings override;
	/** The exact object read from disk by load(), used as an identity marker. */
	private static EarthSettings diskLoaded;
	/** Settings saved during this session (e.g. via Apply in the Earth screen). */
	private static volatile EarthSettings pending;
	private static volatile long pendingNanos;

	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("realworld.json");
	}

	public static void load() {
		try {
			Path path = configPath();
			if (!Files.exists(path)) return;
			String text = Files.readString(path);
			JsonElement json = JsonParser.parseString(text);
			EarthSettings.CODEC.parse(JsonOps.INSTANCE, json).resultOrPartial(
					err -> RealWorldMod.LOGGER.warn("RealWorld: could not parse config: {}", err)
			).ifPresent(parsed -> {
				override = parsed;
				diskLoaded = parsed;
				RealWorldMod.LOGGER.info("RealWorld: loaded Earth settings from {}", path);
			});
		} catch (Exception e) {
			RealWorldMod.LOGGER.warn("RealWorld: failed to load config: {}", e.getMessage());
		}
	}

	public static void save(EarthSettings settings) {
		override = settings;
		pending = settings;
		pendingNanos = System.nanoTime();
		try {
			Path path = configPath();
			Files.createDirectories(path.getParent());
			EarthSettings.CODEC.encodeStart(JsonOps.INSTANCE, settings).result().ifPresent(json ->
					writeQuietly(path, GSON.toJson(json)));
		} catch (Exception e) {
			RealWorldMod.LOGGER.warn("RealWorld: failed to save config: {}", e.getMessage());
		}
	}

	private static void writeQuietly(Path path, String text) {
		try { Files.writeString(path, text); }
		catch (Exception e) { RealWorldMod.LOGGER.warn("RealWorld: failed to write config: {}", e.getMessage()); }
	}

	/** Settings saved this session, to be adopted by newly created Earth worlds. */
	public static EarthSettings getPending() {
		return pending;
	}

	public static long getPendingNanos() {
		return pendingNanos;
	}

	/** The exact object that was loaded from disk (identity marker), or null. */
	public static EarthSettings getDiskLoaded() {
		return diskLoaded;
	}

	/** The settings the UI should start from (last used, or the defaults). */
	public static EarthSettings current() {
		return override != null ? override : EarthSettings.DEFAULT;
	}

	/** Preset defaults are replaced by the config override when one exists. */
	public static EarthSettings applyOverride(EarthSettings settings) {
		if (override != null && settings.equals(EarthSettings.DEFAULT)) return override;
		return settings;
	}
}
