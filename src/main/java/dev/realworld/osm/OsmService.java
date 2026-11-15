package dev.realworld.osm;

import dev.realworld.geo.GeoMath;
import dev.realworld.settings.EarthSettings;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns downloaded OpenStreetMap vector data into fast per-block lookups.
 *
 * <p>Data is fetched in 512x512-block "super-tiles" (one Overpass request
 * each) and rasterised into 64x64-block tiles: for every block we store its
 * {@link SurfaceKind}, the building height if it is inside a footprint, and
 * whether OSM has an explicit tree node there. Raster tiles are cached, so
 * the per-column cost during chunk generation is a single array read.</p>
 */
public final class OsmService {
	private OsmService() {}

	private static final int SUPER_BLOCKS = 512;
	private static final int TILE_BLOCKS = 64;

	/** Rasterised 64x64 tile. kind ordinals index into a byte array. */
	public static final class RasterTile {
		final byte[] kinds = new byte[TILE_BLOCKS * TILE_BLOCKS];
		final short[] buildingHeights = new short[TILE_BLOCKS * TILE_BLOCKS];
		final boolean[] treeNodes = new boolean[TILE_BLOCKS * TILE_BLOCKS];
	}

	private record SuperKey(long settingsKey, int sx, int sz) {}
	private record TileKey(long settingsKey, int tx, int tz) {}

	private static final Map<SuperKey, OsmDataset> SUPER_CACHE = new ConcurrentHashMap<>();
	private static final Map<TileKey, RasterTile> TILE_CACHE = new ConcurrentHashMap<>();

	private static long settingsKey(EarthSettings s) {
		long h = Double.doubleToLongBits(s.region().centerLat() * 10000.0);
		h = h * 31 + Double.doubleToLongBits(s.region().centerLon() * 10000.0);
		h = h * 31 + s.region().scale();
		h = h * 31 + (s.structures().buildings() ? 1 : 0);
		h = h * 31 + (s.structures().roads() ? 1 : 0);
		return h;
	}

	/** OSM is only used when at least one OSM-driven feature category is on. */
	public static boolean osmEnabled(EarthSettings s) {
		return s.structures().buildings() || s.structures().roads() || s.structures().farmland()
				|| s.nature().trees() || s.water().lakes() || s.water().rivers();
	}

	public static RasterTile tileFor(EarthSettings settings, int blockX, int blockZ) {
		int tx = Math.floorDiv(blockX, TILE_BLOCKS);
		int tz = Math.floorDiv(blockZ, TILE_BLOCKS);
		TileKey key = new TileKey(settingsKey(settings), tx, tz);
		RasterTile tile = TILE_CACHE.get(key);
		if (tile == null) {
			tile = rasterize(settings, tx, tz);
			if (TILE_CACHE.size() > 2048) TILE_CACHE.clear();
			TILE_CACHE.put(key, tile);
		}
		return tile;
	}

	static SurfaceKind kindAt(RasterTile tile, int blockX, int blockZ) {
		int lx = Math.floorMod(blockX, TILE_BLOCKS);
		int lz = Math.floorMod(blockZ, TILE_BLOCKS);
		return SurfaceKind.values()[tile.kinds[lz * TILE_BLOCKS + lx]];
	}

	public static SurfaceKind kindAt(EarthSettings settings, int blockX, int blockZ) {
		return kindAt(tileFor(settings, blockX, blockZ), blockX, blockZ);
	}

	public static int buildingHeightAt(RasterTile tile, int blockX, int blockZ) {
		int lx = Math.floorMod(blockX, TILE_BLOCKS);
		int lz = Math.floorMod(blockZ, TILE_BLOCKS);
		return tile.buildingHeights[lz * TILE_BLOCKS + lx];
	}

	public static boolean treeNodeAt(RasterTile tile, int blockX, int blockZ) {
		int lx = Math.floorMod(blockX, TILE_BLOCKS);
		int lz = Math.floorMod(blockZ, TILE_BLOCKS);
		return tile.treeNodes[lz * TILE_BLOCKS + lx];
	}

	private static OsmDataset superTile(EarthSettings settings, int sx, int sz) {
		SuperKey key = new SuperKey(settingsKey(settings), sx, sz);
		OsmDataset data = SUPER_CACHE.get(key);
		if (data == null) {
			synchronized (OsmService.class) {
				data = SUPER_CACHE.get(key);
				if (data == null) {
					// Convert the super-tile's block bounds to a lat/lon bbox (+ margin).
					double[] a = GeoMath.blockToLatLon(settings, sx * (double) SUPER_BLOCKS - 32, sz * (double) SUPER_BLOCKS + SUPER_BLOCKS + 32);
					double[] b = GeoMath.blockToLatLon(settings, sx * (double) SUPER_BLOCKS + SUPER_BLOCKS + 32, sz * (double) SUPER_BLOCKS - 32);
					double minLat = Math.min(a[0], b[0]), maxLat = Math.max(a[0], b[0]);
					double minLon = Math.min(a[1], b[1]), maxLon = Math.max(a[1], b[1]);
					data = OsmClient.fetch(settings, minLat, minLon, maxLat, maxLon);
					if (SUPER_CACHE.size() > 256) SUPER_CACHE.clear();
					SUPER_CACHE.put(key, data);
				}
			}
		}
		return data;
	}

	private static RasterTile rasterize(EarthSettings settings, int tx, int tz) {
		RasterTile tile = new RasterTile();
		if (!osmEnabled(settings)) return tile;
		int baseX = tx * TILE_BLOCKS, baseZ = tz * TILE_BLOCKS;
		int sx = Math.floorDiv(baseX, SUPER_BLOCKS), sz = Math.floorDiv(baseZ, SUPER_BLOCKS);
		// Elements can spill over super-tile borders; check the 3x3 neighbourhood.
		java.util.List<OsmDataset> sets = new java.util.ArrayList<>();
		sets.add(superTile(settings, sx, sz));
		// Fetch adjacent super-tiles only when the tile touches a border area.
		if (Math.floorMod(baseX, SUPER_BLOCKS) < 64) sets.add(superTile(settings, sx - 1, sz));
		if (Math.floorMod(baseX, SUPER_BLOCKS) > SUPER_BLOCKS - 128) sets.add(superTile(settings, sx + 1, sz));
		if (Math.floorMod(baseZ, SUPER_BLOCKS) < 64) sets.add(superTile(settings, sx, sz - 1));
		if (Math.floorMod(baseZ, SUPER_BLOCKS) > SUPER_BLOCKS - 128) sets.add(superTile(settings, sx, sz + 1));

		int scale = settings.region().scale();
		for (int lz = 0; lz < TILE_BLOCKS; lz++) {
			for (int lx = 0; lx < TILE_BLOCKS; lx++) {
				int bx = baseX + lx, bz = baseZ + lz;
				double cx = bx + 0.5, cz = bz + 0.5;
				SurfaceKind best = SurfaceKind.NONE;
				int bestHeight = 0;
				for (OsmDataset data : sets) {
					if (data.treeBlocks.contains(OsmDataset.packBlock(bx, bz))) {
						tile.treeNodes[lz * TILE_BLOCKS + lx] = true;
					}
					for (OsmElement e : data.elements) {
						if (e.kind.priority <= best.priority && e.kind != best) continue;
						if (!elementEnabled(settings, e.kind)) continue;
						if (cx < e.minX - 16 || cx > e.maxX + 16 || cz < e.minZ - 16 || cz > e.maxZ + 16) continue;
						boolean hit;
						if (e.closed) {
							hit = GeoMath.pointInPolygonBlock(cx, cz, e.points);
						} else {
							double widthBlocks = Math.max(1.0, (e.widthMeters / scale)
									* (e.kind == SurfaceKind.ROAD || e.kind == SurfaceKind.PATH
											? settings.structures().roadWidthScale() : 1.0));
							hit = false;
							List<double[]> pts = e.points;
							for (int i = 0; i + 1 < pts.size(); i++) {
								if (GeoMath.distToSegment(cx, cz, pts.get(i)[0], pts.get(i)[1], pts.get(i + 1)[0], pts.get(i + 1)[1]) <= widthBlocks / 2.0) {
									hit = true; break;
								}
							}
						}
						if (hit && e.kind.priority >= best.priority) {
							best = e.kind;
							if (e.kind == SurfaceKind.BUILDING) {
								double meters = e.heightMeters * settings.structures().buildingHeightScale();
								int blocks = (int) Math.round((meters / scale) * Math.max(0.2, settings.terrain().elevationExaggeration()));
								bestHeight = Math.max(3, Math.min(180, blocks));
							}
						}
					}
				}
				tile.kinds[lz * TILE_BLOCKS + lx] = (byte) best.ordinal();
				tile.buildingHeights[lz * TILE_BLOCKS + lx] = (short) bestHeight;
			}
		}
		return tile;
	}

	private static boolean elementEnabled(EarthSettings s, SurfaceKind kind) {
		return switch (kind) {
			case BUILDING -> s.structures().buildings();
			case ROAD, PATH -> s.structures().roads();
			case WATER -> s.water().lakes();
			case RIVER -> s.water().rivers();
			case FOREST, PARK -> s.nature().trees() || s.nature().vegetation();
			case FARMLAND -> s.structures().farmland();
			case BEACH -> s.nature().beaches();
			case NONE -> false;
		};
	}

	/** Drop all cached OSM data (used by the /realworld osmreload command). */
	public static void clearCaches() {
		SUPER_CACHE.clear();
		TILE_CACHE.clear();
	}
}
