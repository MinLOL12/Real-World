package dev.realworld.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.realworld.config.EarthConfig;
import dev.realworld.geo.GeoMath;
import dev.realworld.osm.OsmService;
import dev.realworld.osm.SurfaceKind;
import dev.realworld.settings.EarthSettings;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Chunk generator that builds Minecraft terrain from real Earth data:
 * elevation (Open-Meteo / procedural fallback) for the land shape and
 * OpenStreetMap for water, roads, buildings, forests, beaches and farmland.
 *
 * <p>The {@link EarthSettings} travel inside this generator's codec, so the
 * exact settings used for a world are stored in its level.dat. Vanilla
 * overworld biome noise (from the world preset's biome_source) still picks
 * biomes for mob spawning / ambience, while the actual surface is ours.</p>
 */
public class EarthChunkGenerator extends ChunkGenerator {

	public static final Codec<EarthChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
			EarthSettings.CODEC.fieldOf("settings").forGetter(EarthChunkGenerator::getSettings)
	).apply(instance, EarthChunkGenerator::new));

	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("realworld");
	private static final java.util.concurrent.atomic.AtomicBoolean LOGGED_ONCE = new java.util.concurrent.atomic.AtomicBoolean();

	private EarthSettings settings;

	public EarthChunkGenerator(BiomeSource biomeSource, EarthSettings settings) {
		super(biomeSource);
		// A config-file override (config/realworld.json) replaces the preset
		// defaults - this is how dedicated servers choose their Earth area.
		this.settings = EarthConfig.applyOverride(settings);
		if (LOGGED_ONCE.compareAndSet(false, true)) {
			LOGGER.info("RealWorld Earth generator active: centre {}, {} | scale 1:{} | selection {} | buildings {} | roads {}",
					this.settings.region().centerLat(), this.settings.region().centerLon(),
					this.settings.region().scale(), this.settings.region().selectionMode(),
					this.settings.structures().buildings(), this.settings.structures().roads());
		}
	}

	public EarthSettings getSettings() {
		return settings;
	}

	/** Used by the create-world Earth settings screen before the world exists. */
	public void setSettings(EarthSettings settings) {
		this.settings = settings;
	}

	@Override
	protected Codec<? extends ChunkGenerator> getCodec() {
		return CODEC;
	}

	// ------------------------------------------------------------ heights
	@Override
	public int getWorldHeight() {
		return EarthTerrain.WORLD_HEIGHT;
	}

	@Override
	public int getMinimumY() {
		return EarthTerrain.MIN_Y;
	}

	@Override
	public int getSeaLevel() {
		return settings.terrain().seaLevel();
	}

	@Override
	public int getHeight(int x, int z, Heightmap.Type type, HeightLimitView world, NoiseConfig noiseConfig) {
		EarthTerrain.Column c = EarthTerrain.column(settings, x, z);
		if (type == Heightmap.Type.MOTION_BLOCKING && c.groundY() < c.seaLevel()
				&& EarthTerrain.shouldFillWater(settings, c)) {
			return c.seaLevel() + 1;
		}
		return c.groundY() + 1;
	}

	@Override
	public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
		EarthTerrain.Column c = EarthTerrain.column(settings, x, z);
		BlockState[] column = new BlockState[getWorldHeight()];
		for (int i = 0; i < column.length; i++) {
			int y = getMinimumY() + i;
			if (y <= c.groundY() - 3) column[i] = Blocks.STONE.getDefaultState();
			else if (y <= c.groundY()) column[i] = Blocks.DIRT.getDefaultState();
			else if (y <= c.seaLevel() && EarthTerrain.shouldFillWater(settings, c)) column[i] = Blocks.WATER.getDefaultState();
			else column[i] = Blocks.AIR.getDefaultState();
		}
		return new VerticalBlockSample(getMinimumY(), column);
	}

	@Override
	public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
		EarthTerrain.Column c = EarthTerrain.column(settings, pos.getX(), pos.getZ());
		text.add("RealWorld: lat %.5f lon %.5f surface %s ground %d".formatted(c.lat(), c.lon(), c.kind(), c.groundY()));
	}

	// ------------------------------------------------------------ generation
	@Override
	public CompletableFuture<Chunk> populateNoise(Executor executor, Blender blender, NoiseConfig noiseConfig,
												  StructureAccessor structureAccessor, Chunk chunk) {
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		int baseX = chunk.getPos().x * 16;
		int baseZ = chunk.getPos().z * 16;
		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int wx = baseX + lx, wz = baseZ + lz;
				EarthTerrain.Column c = EarthTerrain.column(settings, wx, wz);
				BlockState top = topBlock(c);
				for (int y = getMinimumY(); y <= c.groundY(); y++) {
					BlockState state;
					if (y < c.groundY() - 3) state = Blocks.STONE.getDefaultState();
					else if (y < c.groundY()) state = Blocks.DIRT.getDefaultState();
					else state = top;
					chunk.setBlockState(mutable.set(lx, y, lz), state, false);
				}
				if (EarthTerrain.shouldFillWater(settings, c)) {
					for (int y = c.groundY() + 1; y <= c.seaLevel(); y++) {
						chunk.setBlockState(mutable.set(lx, y, lz), Blocks.WATER.getDefaultState(), false);
					}
				}
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	private BlockState topBlock(EarthTerrain.Column c) {
		boolean underwater = c.groundY() < c.seaLevel();
		return switch (c.kind()) {
			case WATER, RIVER -> underwater ? Blocks.SAND.getDefaultState() : Blocks.DIRT.getDefaultState();
			case BEACH -> Blocks.SAND.getDefaultState();
			case FARMLAND -> settings.structures().farmland() ? Blocks.FARMLAND.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState();
			case BUILDING -> Blocks.SMOOTH_STONE.getDefaultState();
			default -> {
				if (underwater) yield Blocks.SAND.getDefaultState();
				if (c.groundY() <= c.seaLevel() + 1 && settings.nature().beaches()) yield Blocks.SAND.getDefaultState();
				yield Blocks.GRASS_BLOCK.getDefaultState();
			}
		};
	}

	@Override
	public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		int baseX = chunk.getPos().x * 16;
		int baseZ = chunk.getPos().z * 16;
		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int wx = baseX + lx, wz = baseZ + lz;
				EarthTerrain.Column c = EarthTerrain.column(settings, wx, wz);

				// Rocky cliffs: exposed stone where the slope is steep.
				if (settings.terrain().rockyCliffs() && c.kind() == SurfaceKind.NONE) {
					int east = EarthTerrain.column(settings, wx + 1, wz).groundY();
					int south = EarthTerrain.column(settings, wx, wz + 1).groundY();
					if (Math.abs(east - c.groundY()) >= 3 || Math.abs(south - c.groundY()) >= 3) {
						chunk.setBlockState(mutable.set(lx, c.groundY(), lz), Blocks.STONE.getDefaultState(), false);
						chunk.setBlockState(mutable.set(lx, c.groundY() - 1, lz), Blocks.STONE.getDefaultState(), false);
						continue;
					}
				}

				if (c.kind() == SurfaceKind.ROAD && settings.structures().roads()) {
					chunk.setBlockState(mutable.set(lx, c.groundY(), lz), Blocks.GRAY_CONCRETE.getDefaultState(), false);
				} else if (c.kind() == SurfaceKind.PATH && settings.structures().roads()) {
					chunk.setBlockState(mutable.set(lx, c.groundY(), lz), Blocks.DIRT_PATH.getDefaultState(), false);
				} else if (c.kind() == SurfaceKind.BUILDING && settings.structures().buildings() && c.buildingHeight() > 0) {
					buildBuildingColumn(chunk, mutable, lx, lz, wx, wz, c);
				}
			}
		}
	}

	/** Walls on footprint edges (with windows), roof on top, hollow interior. */
	private void buildBuildingColumn(Chunk chunk, BlockPos.Mutable mutable, int lx, int lz, int wx, int wz, EarthTerrain.Column c) {
		BlockState wall = switch (settings.structures().buildingStyle()) {
			case BRICK -> Blocks.BRICKS.getDefaultState();
			case CONCRETE -> Blocks.WHITE_CONCRETE.getDefaultState();
			case SANDSTONE -> Blocks.SANDSTONE.getDefaultState();
			case TERRACOTTA -> Blocks.TERRACOTTA.getDefaultState();
		};
		BlockState roof = Blocks.STONE_BRICKS.getDefaultState();
		boolean edge = OsmService.kindAt(settings, wx + 1, wz) != SurfaceKind.BUILDING
				|| OsmService.kindAt(settings, wx - 1, wz) != SurfaceKind.BUILDING
				|| OsmService.kindAt(settings, wx, wz + 1) != SurfaceKind.BUILDING
				|| OsmService.kindAt(settings, wx, wz - 1) != SurfaceKind.BUILDING;
		int top = Math.min(EarthTerrain.MAX_Y - 2, c.groundY() + c.buildingHeight());
		for (int y = c.groundY() + 1; y <= top; y++) {
			if (y == top) {
				chunk.setBlockState(mutable.set(lx, y, lz), roof, false);
			} else if (edge) {
				boolean window = y > c.groundY() + 1 && ((wx + wz + y) & 3) == 0;
				chunk.setBlockState(mutable.set(lx, y, lz), window ? Blocks.GLASS.getDefaultState() : wall, false);
			}
		}
	}

	@Override
	public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess,
					  StructureAccessor structureAccessor, Chunk chunk, GenerationStep.Carver carverStep) {
		// No caves: real terrain fidelity matters more, and carvers would
		// happily slice through imported buildings and lake beds.
	}

	@Override
	public void populateEntities(ChunkRegion region) {
		// Intentionally empty: animals/monsters still spawn over time via the
		// normal natural-spawning system using the column's biome.
	}

	// ------------------------------------------------------------ decoration
	@Override
	public void generateFeatures(StructureWorldAccess world, Chunk chunk,
								 StructureAccessor structureAccessor) {
		super.generateFeatures(world, chunk, structureAccessor); // vanilla biome features (flowers, ores are elsewhere)
		int baseX = chunk.getPos().x * 16;
		int baseZ = chunk.getPos().z * 16;
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		for (int lx = 1; lx < 15; lx++) {
			for (int lz = 1; lz < 15; lz++) {
				int wx = baseX + lx, wz = baseZ + lz;
				EarthTerrain.Column c = EarthTerrain.column(settings, wx, wz);
				if (c.kind() == SurfaceKind.BUILDING || c.kind() == SurfaceKind.ROAD || c.kind() == SurfaceKind.PATH) continue;
				int ground = c.groundY();
				if (ground <= c.seaLevel() && c.isWaterKind()) {
					// Lily pads on calm imported water.
					if (settings.water().wetlands() && GeoMath.hash01(wx, wz, 77) < 0.04) {
						world.setBlockState(mutable.set(wx, c.seaLevel() + 1, wz), Blocks.LILY_PAD.getDefaultState(), 3);
					}
					continue;
				}
				if (ground <= c.seaLevel()) continue;

				// Trees: explicit OSM tree nodes always, forest scatter by density.
				boolean wantTree = c.treeNode()
						|| (c.kind() == SurfaceKind.FOREST
							&& GeoMath.hash01(wx, wz, 12) * 100.0 < settings.nature().treeDensity());
				if (settings.nature().trees() && wantTree && lx >= 2 && lx <= 13 && lz >= 2 && lz <= 13) {
					placeTree(world, mutable, wx, ground, wz);
					continue;
				}
				// Crops on farmland.
				if (c.kind() == SurfaceKind.FARMLAND && settings.structures().farmland()
						&& GeoMath.hash01(wx, wz, 31) < 0.55) {
					int age = (int) (GeoMath.hash01(wx, wz, 32) * 8.0);
					world.setBlockState(mutable.set(wx, ground + 1, wz),
							Blocks.WHEAT.getDefaultState().with(CropBlock.AGE, Math.min(7, age)), 3);
					continue;
				}
				// Grass tufts / flowers in parks and open land.
				if (settings.nature().vegetation()
						&& (c.kind() == SurfaceKind.PARK || c.kind() == SurfaceKind.NONE || c.kind() == SurfaceKind.FOREST)) {
					double r = GeoMath.hash01(wx, wz, 41);
					if (r < 0.22) {
						world.setBlockState(mutable.set(wx, ground + 1, wz), Blocks.GRASS.getDefaultState(), 3);
					} else if (r < 0.26) {
						world.setBlockState(mutable.set(wx, ground + 1, wz), Blocks.FERN.getDefaultState(), 3);
					} else if (r < 0.28) {
						world.setBlockState(mutable.set(wx, ground + 1, wz), Blocks.DANDELION.getDefaultState(), 3);
					}
				}
				// Snow cover on high / cold ground.
				if (settings.nature().snow() && isSnowy(c)) {
					world.setBlockState(mutable.set(wx, ground + 1, wz), Blocks.SNOW.getDefaultState(), 3);
				}
			}
		}
	}

	private boolean isSnowy(EarthTerrain.Column c) {
		// Snowline drops towards the poles: ~140 blocks at the equator at 1:1
		// vertical scale, sea level beyond ~60 degrees latitude.
		double latFactor = Math.max(0.0, 1.0 - Math.abs(c.lat()) / 60.0);
		int snowline = c.seaLevel() + (int) (140 * latFactor * Math.max(0.2, settings.terrain().elevationExaggeration()));
		return c.groundY() >= snowline;
	}

	private void placeTree(net.minecraft.world.StructureWorldAccess world, BlockPos.Mutable mutable, int x, int ground, int z) {
		int trunkHeight = 4 + (int) (GeoMath.hash01(x, z, 99) * 3.0);
		BlockState leaves = Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
		for (int dy = trunkHeight - 2; dy <= trunkHeight + 1; dy++) {
			int radius = dy <= trunkHeight - 1 ? 2 : 1;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (dx == 0 && dz == 0 && dy <= trunkHeight) continue;
					if (Math.abs(dx) == radius && Math.abs(dz) == radius && GeoMath.hash01(x + dx, z + dz, dy) < 0.5) continue;
					int y = ground + dy;
					if (y < EarthTerrain.MAX_Y - 1) {
						mutable.set(x + dx, y, z + dz);
						if (world.getBlockState(mutable).isAir()) {
							world.setBlockState(mutable, leaves, 3);
						}
					}
				}
			}
		}
		for (int dy = 1; dy <= trunkHeight; dy++) {
			world.setBlockState(mutable.set(x, ground + dy, z), Blocks.OAK_LOG.getDefaultState(), 3);
		}
	}
}
