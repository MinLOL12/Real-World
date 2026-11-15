package dev.realworld.client.map;

import dev.realworld.RealWorldMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads and caches OpenStreetMap raster map tiles
 * (https://tile.openstreetmap.org/{z}/{x}/{y}.png) as Minecraft textures
 * for the interactive area-selection map. Tiles are fetched on a background
 * pool and registered with the texture manager on the render thread.
 * Please credit (c) OpenStreetMap contributors wherever the map is shown -
 * the map screen does this in its corner.
 */
public final class MapTileManager {
	private MapTileManager() {}

	private static final Map<String, Identifier> LOADED = new ConcurrentHashMap<>();
	private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "RealWorld-MapTiles");
		t.setDaemon(true);
		return t;
	});
	private static int sequence = 0;

	/** Returns the texture for a tile, or null while it is still loading. */
	public static Identifier getTile(int zoom, int tileX, int tileY) {
		int n = 1 << zoom;
		if (tileY < 0 || tileY >= n) return null;
		tileX = Math.floorMod(tileX, n);
		String key = zoom + "/" + tileX + "/" + tileY;
		Identifier id = LOADED.get(key);
		if (id != null) return id;
		if (PENDING.add(key)) {
			final int fx = tileX, fy = tileY;
			EXECUTOR.execute(() -> download(zoom, fx, fy, key));
		}
		return null;
	}

	public static boolean isLoaded(int zoom, int tileX, int tileY) {
		return getTile(zoom, tileX, tileY) != null;
	}

	private static void download(int zoom, int tileX, int tileY, String key) {
		try {
			String url = "https://tile.openstreetmap.org/" + zoom + "/" + tileX + "/" + tileY + ".png";
			HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setRequestProperty("User-Agent", "RealWorld-Fabric-Mod/1.0 (Minecraft Earth import; map (c) OpenStreetMap contributors)");
			conn.setConnectTimeout(8000);
			conn.setReadTimeout(8000);
			if (conn.getResponseCode() != 200) return;
			byte[] bytes = conn.getInputStream().readAllBytes();
			MinecraftClient client = MinecraftClient.getInstance();
			client.execute(() -> {
				try {
					NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
					Identifier id = new Identifier(RealWorldMod.MOD_ID, "tile/" + (sequence++));
					client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
					LOADED.put(key, id);
				} catch (Exception e) {
					RealWorldMod.LOGGER.warn("RealWorld: failed to decode map tile {}: {}", key, e.getMessage());
				} finally {
					PENDING.remove(key);
				}
			});
		} catch (Exception e) {
			PENDING.remove(key);
		}
	}
}
