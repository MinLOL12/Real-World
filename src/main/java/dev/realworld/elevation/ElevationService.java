package dev.realworld.elevation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.realworld.RealWorldMod;
import dev.realworld.geo.GeoMath;
import dev.realworld.settings.EarthSettings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides real-world ground elevation (metres above sea level) for any
 * block column.
 *
 * <p>Strategy: the world is divided into 256-block tiles. Each tile is
 * sampled on a 9x9 grid (one sample every 32 blocks). In
 * {@code AUTO_REMOTE} mode samples come from the free Open-Meteo elevation
 * API (no key required, batched into a single HTTP request per tile);
 * if the request fails (offline, rate-limited, …) or the user picked
 * {@code PROCEDURAL}, samples come from a deterministic procedural noise
 * function so generation never hard-fails. Samples are cached in memory
 * for the session and bilinearly interpolated per block. Optional
 * smoothing passes (Terrain setting) are applied to the sample grid.</p>
 */
public final class ElevationService {
	private ElevationService() {}

	private static final int TILE_BLOCKS = 256;
	private static final int SAMPLES = 9;           // 9x9 samples per tile
	private static final int SPACING = TILE_BLOCKS / (SAMPLES - 1); // 32 blocks

	private record TileKey(long settingsKey, int tileX, int tileZ) {}

	private static final Map<TileKey, double[][]> CACHE = new ConcurrentHashMap<>();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	/** Elevation in metres for the column at block (x, z). */
	public static double elevationMeters(EarthSettings settings, int blockX, int blockZ) {
		int tileX = Math.floorDiv(blockX, TILE_BLOCKS);
		int tileZ = Math.floorDiv(blockZ, TILE_BLOCKS);
		TileKey key = new TileKey(settingsKey(settings), tileX, tileZ);
		double[][] grid = CACHE.get(key);
		if (grid == null) {
			// Loaded outside any lock (parallel worldgen threads); a duplicate
			// fetch for the same tile is harmless and discarded via putIfAbsent.
			grid = loadTile(settings, tileX, tileZ);
			applySmoothing(grid, settings.terrain().smoothing());
			if (CACHE.size() > 512) CACHE.clear();
			double[][] previous = CACHE.putIfAbsent(key, grid);
			if (previous != null) grid = previous;
		}
		// Bilinear interpolation inside the tile.
		double localX = (blockX - (double) tileX * TILE_BLOCKS) / SPACING;
		double localZ = (blockZ - (double) tileZ * TILE_BLOCKS) / SPACING;
		int x0 = clampInt((int) Math.floor(localX), 0, SAMPLES - 2);
		int z0 = clampInt((int) Math.floor(localZ), 0, SAMPLES - 2);
		double fx = clamp01(localX - x0);
		double fz = clamp01(localZ - z0);
		double a = grid[z0][x0], b = grid[z0][x0 + 1], c = grid[z0 + 1][x0], d = grid[z0 + 1][x0 + 1];
		return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
	}

	private static long settingsKey(EarthSettings s) {
		long h = Double.doubleToLongBits(s.region().centerLat() * 10000.0);
		h = h * 31 + Double.doubleToLongBits(s.region().centerLon() * 10000.0);
		h = h * 31 + s.region().scale();
		h = h * 31 + s.terrain().elevationSource().ordinal();
		return h;
	}

	private static double[][] loadTile(EarthSettings settings, int tileX, int tileZ) {
		if (settings.terrain().elevationSource() == EarthSettings.ElevationSource.AUTO_REMOTE) {
			try {
				double[][] remote = fetchRemoteTile(settings, tileX, tileZ);
				if (remote != null) return remote;
				RealWorldMod.LOGGER.warn("RealWorld: elevation API unavailable for tile {},{} - using procedural fallback", tileX, tileZ);
			} catch (Exception e) {
				RealWorldMod.LOGGER.warn("RealWorld: elevation fetch failed ({}), using procedural fallback", e.getMessage());
			}
		}
		double[][] grid = new double[SAMPLES][SAMPLES];
		for (int j = 0; j < SAMPLES; j++) {
			for (int i = 0; i < SAMPLES; i++) {
				int bx = tileX * TILE_BLOCKS + i * SPACING;
				int bz = tileZ * TILE_BLOCKS + j * SPACING;
				double[] ll = GeoMath.blockToLatLon(settings, bx, bz);
				grid[j][i] = ProceduralElevation.elevationMeters(ll[0], ll[1]);
			}
		}
		return grid;
	}

	/** One batched Open-Meteo request: /v1/elevation?latitude=a,b,c&longitude=d,e,f */
	private static double[][] fetchRemoteTile(EarthSettings settings, int tileX, int tileZ) throws Exception {
		StringBuilder lats = new StringBuilder();
		StringBuilder lons = new StringBuilder();
		for (int j = 0; j < SAMPLES; j++) {
			for (int i = 0; i < SAMPLES; i++) {
				int bx = tileX * TILE_BLOCKS + i * SPACING;
				int bz = tileZ * TILE_BLOCKS + j * SPACING;
				double[] ll = GeoMath.blockToLatLon(settings, bx, bz);
				if (lats.length() > 0) { lats.append(','); lons.append(','); }
				lats.append(String.format(java.util.Locale.ROOT, "%.5f", ll[0]));
				lons.append(String.format(java.util.Locale.ROOT, "%.5f", ll[1]));
			}
		}
		String url = "https://api.open-meteo.com/v1/elevation?latitude=" + lats + "&longitude=" + lons;
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", "RealWorld-Fabric-Mod/1.0 (Minecraft Earth import)")
				.GET().build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) return null;
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonArray arr = root.getAsJsonArray("elevation");
		if (arr == null || arr.size() != SAMPLES * SAMPLES) return null;
		double[][] grid = new double[SAMPLES][SAMPLES];
		int k = 0;
		for (int j = 0; j < SAMPLES; j++) {
			for (int i = 0; i < SAMPLES; i++) {
				grid[j][i] = arr.get(k++).getAsDouble();
			}
		}
		return grid;
	}

	private static void applySmoothing(double[][] grid, int passes) {
		passes = Math.max(0, Math.min(4, passes));
		for (int p = 0; p < passes; p++) {
			double[][] copy = new double[SAMPLES][SAMPLES];
			for (int j = 0; j < SAMPLES; j++) {
				for (int i = 0; i < SAMPLES; i++) {
					double sum = 0; int n = 0;
					for (int dj = -1; dj <= 1; dj++) {
						for (int di = -1; di <= 1; di++) {
							int jj = clampInt(j + dj, 0, SAMPLES - 1);
							int ii = clampInt(i + di, 0, SAMPLES - 1);
							sum += grid[jj][ii]; n++;
						}
					}
					copy[j][i] = sum / n;
				}
			}
			for (int j = 0; j < SAMPLES; j++) System.arraycopy(copy[j], 0, grid[j], 0, SAMPLES);
		}
	}

	private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
	private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

	/** Deterministic offline terrain used when no elevation data is available. */
	static final class ProceduralElevation {
		static double elevationMeters(double lat, double lon) {
			// fBm value noise in degree space: rolling hills up to ~200 m.
			double f = 0, amp = 1, freq = 6.0, norm = 0;
			for (int oct = 0; oct < 4; oct++) {
				f += amp * valueNoise(lat * freq, lon * freq);
				norm += amp;
				amp *= 0.5; freq *= 2.1;
			}
			f /= norm; // 0..1
			double hills = Math.pow(Math.max(0, f), 1.6) * 220.0;
			return hills - 8.0;
		}

		private static double valueNoise(double x, double y) {
			long xi = (long) Math.floor(x), yi = (long) Math.floor(y);
			double xf = x - xi, yf = y - yi;
			double u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
			double a = hash(xi, yi), b = hash(xi + 1, yi), c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
			return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v;
		}

		private static double hash(long x, long y) {
			long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL;
			h ^= (h >>> 33); h *= 0xFF51AFD7ED558CCDL; h ^= (h >>> 33);
			return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
		}
	}
}
