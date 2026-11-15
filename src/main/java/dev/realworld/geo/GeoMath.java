package dev.realworld.geo;

import dev.realworld.settings.EarthSettings;

/**
 * Coordinate conversions between WGS84 latitude/longitude, real-world metres
 * around the selected centre, and Minecraft blocks.
 *
 * <p>Orientation: +X is east, +Z is south in Minecraft, so north is -Z.
 * At scale 1:1 one block is one metre; at 1:N one block is N metres.</p>
 */
public final class GeoMath {
	private GeoMath() {}

	public static final double METERS_PER_DEG_LAT = 110540.0;

	public static double metersPerDegLon(double lat) {
		return 111320.0 * Math.max(0.05, Math.cos(Math.toRadians(lat)));
	}

	/** Convert a world block position to lat/lon. Returns {lat, lon}. */
	public static double[] blockToLatLon(EarthSettings settings, double blockX, double blockZ) {
		EarthSettings.Region r = settings.region();
		double scale = r.scale();
		double metersEast = blockX * scale;
		double metersNorth = -blockZ * scale;
		double lat = r.centerLat() + metersNorth / METERS_PER_DEG_LAT;
		double lon = r.centerLon() + metersEast / metersPerDegLon(r.centerLat());
		return new double[]{lat, lon};
	}

	/** Convert lat/lon to world block coordinates. Returns {x, z} (doubles). */
	public static double[] latLonToBlock(EarthSettings settings, double lat, double lon) {
		EarthSettings.Region r = settings.region();
		double metersEast = (lon - r.centerLon()) * metersPerDegLon(r.centerLat());
		double metersNorth = (lat - r.centerLat()) * METERS_PER_DEG_LAT;
		double x = metersEast / r.scale();
		double z = -metersNorth / r.scale();
		return new double[]{x, z};
	}

	/** Is this lat/lon inside the user-selected region (lasso polygon or box)? */
	public static boolean insideRegion(EarthSettings settings, double lat, double lon) {
		EarthSettings.Region r = settings.region();
		if (r.hasPolygon()) {
			return pointInPolygon(lon, lat, r.polygonLons(), r.polygonLats());
		}
		double minLat = Math.min(r.minLat(), r.maxLat());
		double maxLat = Math.max(r.minLat(), r.maxLat());
		double minLon = Math.min(r.minLon(), r.maxLon());
		double maxLon = Math.max(r.minLon(), r.maxLon());
		if (maxLat - minLat < 1.0e-9 || maxLon - minLon < 1.0e-9) {
			// No explicit box: fall back to the square around the centre.
			minLat = r.centerLat() - r.halfSizeLatDeg();
			maxLat = r.centerLat() + r.halfSizeLatDeg();
			minLon = r.centerLon() - r.halfSizeLonDeg();
			maxLon = r.centerLon() + r.halfSizeLonDeg();
		}
		return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
	}

	/** Ray-casting point in polygon. xs/ys are parallel coordinate lists. */
	public static boolean pointInPolygon(double x, double y, java.util.List<Double> xs, java.util.List<Double> ys) {
		boolean inside = false;
		int n = Math.min(xs.size(), ys.size());
		for (int i = 0, j = n - 1; i < n; j = i++) {
			double xi = xs.get(i), yi = ys.get(i);
			double xj = xs.get(j), yj = ys.get(j);
			boolean intersect = ((yi > y) != (yj > y))
					&& (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
			if (intersect) inside = !inside;
		}
		return inside;
	}

	/** Point-in-polygon in block space for OSM footprints. pts are {x, z} pairs. */
	public static boolean pointInPolygonBlock(double x, double z, java.util.List<double[]> pts) {
		boolean inside = false;
		int n = pts.size();
		for (int i = 0, j = n - 1; i < n; j = i++) {
			double xi = pts.get(i)[0], zi = pts.get(i)[1];
			double xj = pts.get(j)[0], zj = pts.get(j)[1];
			boolean intersect = ((zi > z) != (zj > z))
					&& (x < (xj - xi) * (z - zi) / (zj - zi) + xi);
			if (intersect) inside = !inside;
		}
		return inside;
	}

	/** Distance from point (px,pz) to segment (ax,az)-(bx,bz), in blocks. */
	public static double distToSegment(double px, double pz, double ax, double az, double bx, double bz) {
		double dx = bx - ax, dz = bz - az;
		double len2 = dx * dx + dz * dz;
		double t = len2 == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / len2;
		t = Math.max(0, Math.min(1, t));
		double cx = ax + t * dx, cz = az + t * dz;
		return Math.hypot(px - cx, pz - cz);
	}

	/** Deterministic 2D hash -> [0,1), used for trees/vegetation scatter. */
	public static double hash01(int x, int z, int salt) {
		long h = (long) x * 0x9E3779B97F4A7C15L ^ (long) z * 0xC2B2AE3D27D4EB4FL ^ (long) salt * 0x165667B19E3779F9L;
		h ^= (h >>> 33);
		h *= 0xFF51AFD7ED558CCDL;
		h ^= (h >>> 33);
		return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
	}
}
