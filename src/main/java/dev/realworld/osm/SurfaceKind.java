package dev.realworld.osm;

/** Surface classification for a single block column, derived from OSM data. */
public enum SurfaceKind {
	NONE(0), PARK(1), FARMLAND(2), BEACH(3), FOREST(4), WATER(5), RIVER(6), PATH(7), ROAD(8), BUILDING(9);
	public final int priority;
	SurfaceKind(int priority) { this.priority = priority; }
}
