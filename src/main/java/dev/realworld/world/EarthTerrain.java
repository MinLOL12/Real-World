package dev.realworld.world;

import dev.realworld.elevation.ElevationService;
import dev.realworld.geo.GeoMath;
import dev.realworld.osm.OsmService;
import dev.realworld.osm.SurfaceKind;
import dev.realworld.settings.EarthSettings;

/**
 * Computes everything the chunk generator needs to know about one block
 * column: ground height, sea level, OSM surface classification and building
 * height. All lookups go through cached tiles, so this stays cheap enough
 * to call for every column of every chunk.
 */
public final class EarthTerrain {
	private EarthTerrain() {}

	public static final int MIN_Y = -64;
	public static final int WORLD_HEIGHT = 384;
	public static final int MAX_Y = MIN_Y + WORLD_HEIGHT; // 320, exclusive top is 319

	public record Column(int groundY, int seaLevel, SurfaceKind kind, int buildingHeight,
						 boolean treeNode, boolean insideRegion, double lat, double lon) {
		public boolean isWaterKind() {
			return kind == SurfaceKind.WATER || kind == SurfaceKind.RIVER;
		}
	}

	public static Column column(EarthSettings settings, int x, int z) {
		double[] ll = GeoMath.blockToLatLon(settings, x, z);
		double lat = ll[0], lon = ll[1];
		boolean inside = GeoMath.insideRegion(settings, lat, lon);
		int sea = settings.terrain().seaLevel();

		SurfaceKind kind = SurfaceKind.NONE;
		int buildingHeight = 0;
		boolean treeNode = false;
		if (inside && OsmService.osmEnabled(settings)) {
			OsmService.RasterTile tile = OsmService.tileFor(settings, x, z);
			kind = OsmService.kindAt(tile, x, z);
			buildingHeight = OsmService.buildingHeightAt(tile, x, z);
			treeNode = OsmService.treeNodeAt(tile, x, z);
		}

		int ground;
		if (!inside) {
			// Outside the imported area: endless ocean or plains, per settings.
			if (settings.region().outsideRegion() == EarthSettings.OutsideRegion.PLAINS) {
				ground = sea + 1;
				kind = SurfaceKind.NONE;
			} else {
				ground = sea - 4;
				kind = SurfaceKind.WATER;
			}
			buildingHeight = 0;
			treeNode = false;
		} else {
			double elevation = ElevationService.elevationMeters(settings, x, z);
			double vFactor = settings.terrain().verticalFactor(settings.region().scale());
			ground = (int) Math.round(sea + elevation * vFactor);

			// Water bodies are pushed below sea level so they actually fill.
			if (kind == SurfaceKind.WATER && settings.water().lakes()) {
				ground = Math.min(ground, sea - Math.max(1, settings.water().waterDepth()));
			} else if (kind == SurfaceKind.RIVER && settings.water().rivers()) {
				ground = Math.min(ground, sea - Math.max(1, settings.water().waterDepth() - 1));
			}
			// Roads bridge across water instead of sinking.
			if ((kind == SurfaceKind.ROAD || kind == SurfaceKind.PATH) && ground < sea) {
				ground = sea;
			}
			// Without oceans, low-lying basins stay dry land.
			if (!settings.water().oceans() && kind == SurfaceKind.NONE && ground < sea) {
				ground = sea + 1;
			}
		}

		ground = Math.max(MIN_Y + 2, Math.min(MAX_Y - 20, ground));
		return new Column(ground, sea, kind, buildingHeight, treeNode, inside, lat, lon);
	}

	/** Whether a column at this ground height should be filled with water up to sea level. */
	public static boolean shouldFillWater(EarthSettings settings, Column c) {
		if (c.groundY() >= c.seaLevel()) return false;
		if (c.kind() == SurfaceKind.WATER) return settings.water().lakes() || settings.water().oceans();
		if (c.kind() == SurfaceKind.RIVER) return settings.water().rivers();
		return settings.water().oceans() && c.insideRegion();
	}
}
