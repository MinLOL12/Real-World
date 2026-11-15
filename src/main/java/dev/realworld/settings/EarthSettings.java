package dev.realworld.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringIdentifiable;

import java.util.List;

/**
 * All user-facing Earth generation settings, grouped into the 5 categories
 * shown in the in-game settings screen. The whole object is serialised with
 * {@link #CODEC} into the chunk generator, so the settings are stored inside
 * the world (level.dat) and survive reloads / are sent to clients joining a
 * server.
 *
 * <p>Categories (27 visible settings total):</p>
 * <ol>
 *   <li>{@link Region}     – where on Earth, selection mode and scale (6 + map selection data)</li>
 *   <li>{@link Terrain}    – elevation source, exaggeration, sea level, smoothing, cliffs (5)</li>
 *   <li>{@link Nature}     – trees, density, vegetation, beaches, snow (5)</li>
 *   <li>{@link Water}      – oceans, rivers, lakes, depth, wetlands (5)</li>
 *   <li>{@link Structures} – buildings, height, style, roads, road width, farmland (6)</li>
 * </ol>
 */
public record EarthSettings(Region region, Terrain terrain, Nature nature, Water water, Structures structures) {

	public static final Codec<EarthSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Region.CODEC.fieldOf("region").forGetter(EarthSettings::region),
			Terrain.CODEC.fieldOf("terrain").forGetter(EarthSettings::terrain),
			Nature.CODEC.fieldOf("nature").forGetter(EarthSettings::nature),
			Water.CODEC.fieldOf("water").forGetter(EarthSettings::water),
			Structures.CODEC.fieldOf("structures").forGetter(EarthSettings::structures)
	).apply(instance, EarthSettings::new));

	/** Default: a 2 km x 2 km slice of Manhattan, New York City at 1:1 scale. */
	public static final EarthSettings DEFAULT = new EarthSettings(
			new Region(40.7128, -74.0060, 1, SelectionMode.BOX, 2.0, OutsideRegion.OCEAN,
					40.70375, 40.72185, -74.01785, -73.99415, List.of(), List.of()),
			new Terrain(ElevationSource.AUTO_REMOTE, 1.0, 62, 1, true),
			new Nature(true, 35, true, true, true),
			new Water(true, true, true, 3, true),
			new Structures(true, 1.0, BuildingStyle.CONCRETE, true, 1.0, true)
	);

	// ---------------------------------------------------------------- Region
	public enum SelectionMode implements StringIdentifiable {
		BOX("box"), LASSO("lasso");
		private final String name;
		SelectionMode(String name) { this.name = name; }
		@Override public String asString() { return name; }
		public SelectionMode next() { return values()[(ordinal() + 1) % values().length]; }
		public static final Codec<SelectionMode> CODEC = StringIdentifiable.createCodec(SelectionMode::values);
	}

	public enum OutsideRegion implements StringIdentifiable {
		OCEAN("ocean"), PLAINS("plains");
		private final String name;
		OutsideRegion(String name) { this.name = name; }
		@Override public String asString() { return name; }
		public OutsideRegion next() { return values()[(ordinal() + 1) % values().length]; }
		public static final Codec<OutsideRegion> CODEC = StringIdentifiable.createCodec(OutsideRegion::values);
	}

	/**
	 * @param centerLat       latitude of the centre of the imported area (degrees)
	 * @param centerLon       longitude of the centre of the imported area (degrees)
	 * @param scaleDenominator scale denominator: 1 = 1:1 (1 block = 1 metre), 100 = 1 block = 100 m, …
	 * @param selectionMode   whether the import area came from a box or a lasso selection
	 * @param importSizeKm    fallback square size (km) used when no explicit bbox/polygon is set
	 * @param outsideRegion   what to generate outside the selected area
	 * @param minLat/maxLat/minLon/maxLon bounding box of the selection (set by the map screen)
	 * @param polygonLats/polygonLons lasso polygon vertices (empty for box selections)
	 */
	public record Region(double centerLat, double centerLon, int scaleDenominator, SelectionMode selectionMode,
						 double importSizeKm, OutsideRegion outsideRegion,
						 double minLat, double maxLat, double minLon, double maxLon,
						 List<Double> polygonLats, List<Double> polygonLons) {

		public static final Codec<Region> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.DOUBLE.optionalFieldOf("center_lat", 40.7128).forGetter(Region::centerLat),
				Codec.DOUBLE.optionalFieldOf("center_lon", -74.0060).forGetter(Region::centerLon),
				Codec.INT.optionalFieldOf("scale_denominator", 1).forGetter(Region::scaleDenominator),
				SelectionMode.CODEC.optionalFieldOf("selection_mode", SelectionMode.BOX).forGetter(Region::selectionMode),
				Codec.DOUBLE.optionalFieldOf("import_size_km", 2.0).forGetter(Region::importSizeKm),
				OutsideRegion.CODEC.optionalFieldOf("outside_region", OutsideRegion.OCEAN).forGetter(Region::outsideRegion),
				Codec.DOUBLE.optionalFieldOf("min_lat", 40.70375).forGetter(Region::minLat),
				Codec.DOUBLE.optionalFieldOf("max_lat", 40.72185).forGetter(Region::maxLat),
				Codec.DOUBLE.optionalFieldOf("min_lon", -74.01785).forGetter(Region::minLon),
				Codec.DOUBLE.optionalFieldOf("max_lon", -73.99415).forGetter(Region::maxLon),
				Codec.DOUBLE.listOf().optionalFieldOf("polygon_lats", List.of()).forGetter(Region::polygonLats),
				Codec.DOUBLE.listOf().optionalFieldOf("polygon_lons", List.of()).forGetter(Region::polygonLons)
		).apply(instance, Region::new));

		public boolean hasPolygon() {
			return selectionMode == SelectionMode.LASSO && polygonLats.size() >= 3 && polygonLats.size() == polygonLons.size();
		}

		public int scale() {
			return Math.max(1, scaleDenominator);
		}

		/** Half-size of the fallback square, in degrees latitude. */
		public double halfSizeLatDeg() {
			return (importSizeKm * 500.0) / 110540.0;
		}

		public double halfSizeLonDeg() {
			double cos = Math.max(0.05, Math.cos(Math.toRadians(centerLat)));
			return (importSizeKm * 500.0) / (111320.0 * cos);
		}
	}

	// ---------------------------------------------------------------- Terrain
	public enum ElevationSource implements StringIdentifiable {
		/** Real elevation from the Open-Meteo elevation API, procedural fallback offline. */
		AUTO_REMOTE("auto_remote"),
		/** Fully offline deterministic procedural terrain (no internet needed). */
		PROCEDURAL("procedural");
		private final String name;
		ElevationSource(String name) { this.name = name; }
		@Override public String asString() { return name; }
		public ElevationSource next() { return values()[(ordinal() + 1) % values().length]; }
		public static final Codec<ElevationSource> CODEC = StringIdentifiable.createCodec(ElevationSource::values);
	}

	/**
	 * @param elevationExaggeration vertical multiplier applied after the horizontal scale
	 * @param seaLevel              Y level of the sea surface in the generated world
	 * @param smoothing             0-4 smoothing passes over the sampled elevation grid
	 * @param rockyCliffs           expose stone on steep slopes
	 */
	public record Terrain(ElevationSource elevationSource, double elevationExaggeration, int seaLevel,
						  int smoothing, boolean rockyCliffs) {
		public static final Codec<Terrain> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				ElevationSource.CODEC.optionalFieldOf("elevation_source", ElevationSource.AUTO_REMOTE).forGetter(Terrain::elevationSource),
				Codec.DOUBLE.optionalFieldOf("elevation_exaggeration", 1.0).forGetter(Terrain::elevationExaggeration),
				Codec.INT.optionalFieldOf("sea_level", 62).forGetter(Terrain::seaLevel),
				Codec.INT.optionalFieldOf("smoothing", 1).forGetter(Terrain::smoothing),
				Codec.BOOL.optionalFieldOf("rocky_cliffs", true).forGetter(Terrain::rockyCliffs)
		).apply(instance, Terrain::new));

		/** Blocks of height per metre of real elevation, after horizontal scale. */
		public double verticalFactor(int scaleDenominator) {
			return elevationExaggeration / Math.max(1, scaleDenominator);
		}
	}

	// ---------------------------------------------------------------- Nature
	/**
	 * @param treeDensity percent chance (0-100) for a forest column to grow a tree
	 */
	public record Nature(boolean trees, int treeDensity, boolean vegetation, boolean beaches, boolean snow) {
		public static final Codec<Nature> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("trees", true).forGetter(Nature::trees),
				Codec.INT.optionalFieldOf("tree_density", 35).forGetter(Nature::treeDensity),
				Codec.BOOL.optionalFieldOf("vegetation", true).forGetter(Nature::vegetation),
				Codec.BOOL.optionalFieldOf("beaches", true).forGetter(Nature::beaches),
				Codec.BOOL.optionalFieldOf("snow", true).forGetter(Nature::snow)
		).apply(instance, Nature::new));
	}

	// ---------------------------------------------------------------- Water
	/**
	 * @param waterDepth how far (in blocks) lake/river beds are pushed below sea level
	 */
	public record Water(boolean oceans, boolean rivers, boolean lakes, int waterDepth, boolean wetlands) {
		public static final Codec<Water> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("oceans", true).forGetter(Water::oceans),
				Codec.BOOL.optionalFieldOf("rivers", true).forGetter(Water::rivers),
				Codec.BOOL.optionalFieldOf("lakes", true).forGetter(Water::lakes),
				Codec.INT.optionalFieldOf("water_depth", 3).forGetter(Water::waterDepth),
				Codec.BOOL.optionalFieldOf("wetlands", true).forGetter(Water::wetlands)
		).apply(instance, Water::new));
	}

	// ---------------------------------------------------------------- Structures
	public enum BuildingStyle implements StringIdentifiable {
		BRICK("brick"), CONCRETE("concrete"), SANDSTONE("sandstone"), TERRACOTTA("terracotta");
		private final String name;
		BuildingStyle(String name) { this.name = name; }
		@Override public String asString() { return name; }
		public BuildingStyle next() { return values()[(ordinal() + 1) % values().length]; }
		public static final Codec<BuildingStyle> CODEC = StringIdentifiable.createCodec(BuildingStyle::values);
	}

	/**
	 * @param buildingHeightScale multiplier on real building heights (after scale)
	 * @param roadWidthScale      multiplier on real road widths (after scale)
	 */
	public record Structures(boolean buildings, double buildingHeightScale, BuildingStyle buildingStyle,
							 boolean roads, double roadWidthScale, boolean farmland) {
		public static final Codec<Structures> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("buildings", true).forGetter(Structures::buildings),
				Codec.DOUBLE.optionalFieldOf("building_height_scale", 1.0).forGetter(Structures::buildingHeightScale),
				BuildingStyle.CODEC.optionalFieldOf("building_style", BuildingStyle.CONCRETE).forGetter(Structures::buildingStyle),
				Codec.BOOL.optionalFieldOf("roads", true).forGetter(Structures::roads),
				Codec.DOUBLE.optionalFieldOf("road_width_scale", 1.0).forGetter(Structures::roadWidthScale),
				Codec.BOOL.optionalFieldOf("farmland", true).forGetter(Structures::farmland)
		).apply(instance, Structures::new));
	}
}
