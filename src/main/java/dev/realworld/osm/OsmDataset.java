package dev.realworld.osm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Surface classification for a single block column, derived from OSM data. */
public enum SurfaceKind {
	NONE(0), PARK(1), FARMLAND(2), BEACH(3), FOREST(4), WATER(5), RIVER(6), PATH(7), ROAD(8), BUILDING(9);
	final int priority;
	SurfaceKind(int priority) { this.priority = priority; }
}

/** One parsed OSM way/relation converted into Minecraft block coordinates. */
final class OsmElement {
	final SurfaceKind kind;
	final List<double[]> points; // {x, z} block coordinates
	final boolean closed;
	final double heightMeters;   // buildings: explicit height or levels*3
	final double widthMeters;    // roads / rivers
	final double minX, maxX, minZ, maxZ;

	OsmElement(SurfaceKind kind, List<double[]> points, boolean closed, double heightMeters, double widthMeters) {
		this.kind = kind;
		this.points = points;
		this.closed = closed;
		this.heightMeters = heightMeters;
		this.widthMeters = widthMeters;
		double mnx = Double.POSITIVE_INFINITY, mxx = Double.NEGATIVE_INFINITY;
		double mnz = Double.POSITIVE_INFINITY, mxz = Double.NEGATIVE_INFINITY;
		for (double[] p : points) {
			mnx = Math.min(mnx, p[0]); mxx = Math.max(mxx, p[0]);
			mnz = Math.min(mnz, p[1]); mxz = Math.max(mxz, p[1]);
		}
		this.minX = mnx; this.maxX = mxx; this.minZ = mnz; this.maxZ = mxz;
	}
}

/** All OSM elements for one downloaded super-tile, in block coordinates. */
public final class OsmDataset {
	public final List<OsmElement> elements = new ArrayList<>();
	public final Set<Long> treeBlocks = new HashSet<>();
	public boolean empty = true;

	public static long packBlock(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	void add(OsmElement e) {
		if (e.points.size() >= 2) { elements.add(e); empty = false; }
	}
}
