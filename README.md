# Real World — Minecraft Earth Import (Fabric 1.20.1)

A Minecraft **Fabric 1.20.1** mod that adds an **Earth** world generation option.
Pick any place on Earth on an in-game OpenStreetMap map (box or lasso select),
choose how big it should be compared to real life (1:1, 1:100, …), and generate a
world with real **terrain, oceans, rivers, lakes, trees, beaches, roads,
farmland and buildings** in it.

![Settings](https://img.shields.io/badge/settings-27%20in%205%20categories-brightgreen)
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-blue)
![Loader](https://img.shields.io/badge/Loader-Fabric-purple)

---

## Getting the jar (no local setup needed)

This repo has a GitHub Actions workflow that builds the jar for you:

1. Push to GitHub (or open the **Actions** tab → **Build mod jar** → **Run workflow**).
2. Wait for the run to go green.
3. Download the **RealWorld-mod-jar** artifact from the run summary.
4. Put **`realworld-1.0.1.jar`** (not the `-dev` / `-sources` jars) into your
   Minecraft `mods` folder, together with **Fabric Loader 1.20.1** and **Fabric API**.

To build locally instead (needs Java 17):

```bash
./gradlew build        # Windows: gradlew.bat build
# jar ends up in build/libs/realworld-1.0.1.jar
```

## How to use

1. **Singleplayer → Create New World.** On the **World** tab, click the
   **World Type** button until you see **Earth** (this mod adds it to the
   normal world-type list).
2. Click **Earth Settings…** (top-right of the create-world screen — only
   visible while Earth is selected).
3. Browse the 5 setting tabs, then press **Map: Select Area…**:
   - **Pan** mode: drag to move the OpenStreetMap map, scroll to zoom.
   - **Box** mode: drag a rectangle around the area you want.
   - **Lasso** mode: drag a freehand loop — blocks only generate inside the loop.
   - Press **Use selection**.
4. Back in the settings screen, check the summary line (area size in km and in
   blocks at your chosen scale), tweak anything else, press **Apply**, then
   **Create New World**.

Outside world creation, press **G** anywhere to edit the saved default Earth
settings (`config/realworld.json`), which are used for the next Earth world.

### In-world commands

| Command | What it does |
|---|---|
| `/realworld` / `/realworld info` | Shows the Earth settings of the current world |
| `/realworld osmreload` (op) | Clears cached OSM/elevation data so new chunks re-download |

## The 27 settings (5 categories)

### 1. Region & Scale
| Setting | Meaning |
|---|---|
| Centre latitude / longitude | Centre of the imported area (set automatically from the map) |
| Scale denominator | **1 = 1:1** (1 block = 1 m), 100 = 1 block = 100 m, quick presets 1:1 / 1:10 / 1:100 / 1:1000 |
| Selection mode | `BOX` or `LASSO`, as drawn on the map |
| Import size (km) | Fallback square size when no map selection exists |
| Outside selected area | Fill the rest of the world with `OCEAN` or `PLAINS` |

### 2. Terrain
| Setting | Meaning |
|---|---|
| Elevation source | `AUTO_REMOTE` = real elevation via Open-Meteo (procedural fallback offline), `PROCEDURAL` = fully offline |
| Elevation exaggeration | Vertical multiplier after scaling (raise it at small scales so hills survive) |
| Sea level (Y) | Y level of the sea surface |
| Terrain smoothing | 0–4 blur passes over the sampled elevation grid |
| Rocky cliffs | Expose stone on steep slopes |

### 3. Nature
| Setting | Meaning |
|---|---|
| Trees | Oak trees in OSM forests/woods + exact OSM tree nodes |
| Tree density % | Chance per forest column to grow a tree |
| Vegetation | Grass tufts, ferns, flowers in parks/open land |
| Beaches | Sand on shores and OSM beaches |
| Snow | Snow cover on high/cold ground (snowline depends on latitude) |

### 4. Water
| Setting | Meaning |
|---|---|
| Oceans | Terrain below sea level fills with water |
| Rivers | OSM rivers/streams/canals carved & filled |
| Lakes | OSM water polygons filled |
| Water depth | How far lake/river beds sit below sea level (blocks) |
| Wetlands | Lily pads sprinkled on calm water |

### 5. Structures & Roads
| Setting | Meaning |
|---|---|
| Buildings | OSM building footprints with walls, glass windows and roofs |
| Building height scale | Multiplier on real heights (from `building:levels` / `height` tags) |
| Building style | BRICK / CONCRETE / SANDSTONE / TERRACOTTA walls |
| Roads | OSM highways as grey concrete, paths as dirt paths (width from type/lanes) |
| Road width scale | Multiplier on real road widths |
| Farmland | OSM farmland/orchards as tilled soil with wheat crops |

All settings are stored **inside the world** (in the chunk generator data in
`level.dat`), so a world always regenerates exactly as it was created.

## How it works

- **World type** — the mod registers a custom chunk generator (`realworld:earth`)
  plus a world preset, and adds the preset to the vanilla world-type list. The
  Nether and End stay vanilla.
- **Area selection** — real OpenStreetMap tiles are downloaded and drawn in-game;
  your box/lasso is converted from pixels → lat/lon → the region settings.
- **Elevation** — the world is sampled in 256-block tiles (9×9 grid) from the
  free [Open-Meteo Elevation API](https://open-meteo.com/) (no key needed),
  bilinearly interpolated per block. If you're offline it falls back to
  deterministic procedural hills, so generation never fails.
- **OpenStreetMap features** — per 512-block area, the
  [Overpass API](https://overpass-api.de/) is queried for buildings, highways,
  water, waterways, landuse and trees; results are cached on disk for a week in
  `realworld-cache/osm/` and rasterised into per-block surface data used for
  water, roads, buildings (with real heights), forests, beaches and farmland.
- **Scale** — at 1:1, one block is one metre: a 2 km × 2 km selection is a
  2000 × 2000-block world area. At 1:100 that same area is only 20 × 20
  blocks, so pick the scale that fits your area: a neighbourhood at 1:1, a
  city district at 1:10–1:100, a whole country at 1:1000+ (and raise
  Elevation exaggeration there so mountains survive).

> **Performance note:** first-time generation of an area downloads elevation
> and OSM data, so new chunks can take a moment. Everything is cached
> afterwards. Very large selections at 1:1 (whole countries) are not
> recommended — that's a *lot* of blocks.

## Dedicated servers

Create the world in singleplayer and copy it over, **or**: copy
`config/realworld.json` (written whenever you press Apply; same JSON shape as
the settings in `data/realworld/worldgen/world_preset/earth.json`) to the
server, then use the `Earth` world type / preset when making the world.
Generators created from the preset defaults automatically pick up
`config/realworld.json`. Players only need the mod on the server for
generation — but give it to everyone anyway so the settings screens work.

## Data & attribution

- Map data © **OpenStreetMap** contributors (ODbL) — https://www.openstreetmap.org/copyright
- Elevation data by the **Open-Meteo** Elevation API — https://open-meteo.com/
- Not affiliated with Mojang, Microsoft, OpenStreetMap or Open-Meteo.

## Known limitations

- Multipolygon relations (some big lakes/forests) are approximated by their
  member ways.
- No caves in Earth worlds (they'd slice through buildings/lake beds);
  vanilla structures & biome surface features still appear.
- Biomes for mob spawning/ambience come from vanilla overworld biome noise,
  not from latitude — surfaces are fully Earth-driven regardless.
- Internet is required for *real* elevation/OSM data; offline you still get
  terrain (procedural) but without imported features.

## License

MIT — see [LICENSE](LICENSE).
