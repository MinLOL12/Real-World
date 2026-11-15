package dev.realworld.osm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.realworld.RealWorldMod;
import dev.realworld.geo.GeoMath;
import dev.realworld.settings.EarthSettings;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Downloads OpenStreetMap data for a lat/lon bounding box from the Overpass
 * API and converts it to block-space geometry. Raw responses are cached on
 * disk under {@code <game dir>/realworld-cache/osm/} for 7 days so repeated
 * world generation does not hammer the public Overpass servers.
 *
 * <p>Note: relation multipolygons are approximated by treating each member
 * way as its own polygon/line — good enough for lakes, forests and large
 * buildings at Minecraft scale.</p>
 */
final class OsmClient {
	private OsmClient() {}

	private static final String[] ENDPOINTS = {
			"https://overpass-api.de/api/interpreter",
			"https://overpass.kumi.systems/api/interpreter"
	};
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	static OsmDataset fetch(EarthSettings settings, double minLat, double minLon, double maxLat, double maxLon) {
		String bbox = String.format(java.util.Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", minLat, minLon, maxLat, maxLon);
		try {
			String json = loadCached(bbox);
			if (json == null) {
				json = download(bbox);
				if (json != null) saveCached(bbox, json);
			}
			if (json == null) return new OsmDataset();
			return parse(settings, json);
		} catch (Exception e) {
			RealWorldMod.LOGGER.warn("RealWorld: OSM fetch failed ({}), generating without OSM features for this tile", e.getMessage());
			return new OsmDataset();
		}
	}

	private static String download(String bbox) throws Exception {
		String query = "[out:json][timeout:60];\n"
				+ "(\n"
				+ "  way[\"building\"](" + bbox + ");\n"
				+ "  way[\"highway\"](" + bbox + ");\n"
				+ "  way[\"natural\"=\"water\"](" + bbox + ");\n"
				+ "  way[\"waterway\"](" + bbox + ");\n"
				+ "  way[\"landuse\"](" + bbox + ");\n"
				+ "  way[\"leisure\"~\"^(park|pitch|garden|playground)$\"](" + bbox + ");\n"
				+ "  way[\"natural\"~\"^(wood|beach|sand)$\"](" + bbox + ");\n"
				+ "  node[\"natural\"=\"tree\"](" + bbox + ");\n"
				+ "  relation[\"natural\"=\"water\"](" + bbox + ");\n"
				+ "  relation[\"type\"=\"multipolygon\"][\"landuse\"](" + bbox + ");\n"
				+ ");\n"
				+ "out geom tags;";
		String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
		for (String endpoint : ENDPOINTS) {
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
						.timeout(Duration.ofSeconds(90))
						.header("User-Agent", "RealWorld-Fabric-Mod/1.0 (Minecraft Earth import; OSM data (c) OpenStreetMap contributors)")
						.header("Content-Type", "application/x-www-form-urlencoded")
						.POST(HttpRequest.BodyPublishers.ofString(body))
						.build();
				HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) return response.body();
				RealWorldMod.LOGGER.warn("RealWorld: Overpass {} returned HTTP {}", endpoint, response.statusCode());
			} catch (Exception e) {
				RealWorldMod.LOGGER.warn("RealWorld: Overpass {} failed: {}", endpoint, e.getMessage());
			}
		}
		return null;
	}

	// ------------------------------------------------------------- parsing
	private static OsmDataset parse(EarthSettings settings, String json) {
		OsmDataset data = new OsmDataset();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonArray elements = root.getAsJsonArray("elements");
		if (elements == null) return data;
		for (JsonElement el : elements) {
			JsonObject obj = el.getAsJsonObject();
			String type = obj.has("type") ? obj.get("type").getAsString() : "";
			JsonObject tags = obj.has("tags") && obj.get("tags").isJsonObject() ? obj.getAsJsonObject("tags") : new JsonObject();
			switch (type) {
				case "node" -> {
					if (tags.has("natural") && "tree".equals(tags.get("natural").getAsString())
							&& obj.has("lat") && obj.has("lon")) {
						double[] b = GeoMath.latLonToBlock(settings, obj.get("lat").getAsDouble(), obj.get("lon").getAsDouble());
						data.treeBlocks.add(OsmDataset.packBlock((int) Math.floor(b[0]), (int) Math.floor(b[1])));
						data.empty = false;
					}
				}
				case "way" -> parseWay(settings, data, obj, tags);
				case "relation" -> {
					if (obj.has("members")) {
						for (JsonElement m : obj.getAsJsonArray("members")) {
							JsonObject member = m.getAsJsonObject();
							if (member.has("geometry")) {
								// Reuse the relation's tags for each member way.
								JsonObject fake = new JsonObject();
								fake.add("geometry", member.get("geometry"));
								parseWay(settings, data, fake, tags);
							}
						}
					}
				}
				default -> { }
			}
		}
		return data;
	}

	private static void parseWay(EarthSettings settings, OsmDataset data, JsonObject way, JsonObject tags) {
		if (!way.has("geometry") || !way.get("geometry").isJsonArray()) return;
		JsonArray geom = way.getAsJsonArray("geometry");
		if (geom.size() < 2) return;
		java.util.List<double[]> pts = new java.util.ArrayList<>();
		for (JsonElement g : geom) {
			JsonObject p = g.getAsJsonObject();
			pts.add(GeoMath.latLonToBlock(settings, p.get("lat").getAsDouble(), p.get("lon").getAsDouble()));
		}
		boolean closed = pts.size() > 2
				&& Math.abs(pts.get(0)[0] - pts.get(pts.size() - 1)[0]) < 0.01
				&& Math.abs(pts.get(0)[1] - pts.get(pts.size() - 1)[1]) < 0.01;

		if (tags.has("building")) {
			double height = parseHeightMeters(tags);
			data.add(new OsmElement(SurfaceKind.BUILDING, pts, true, height, 0));
			return;
		}
		if (tags.has("highway")) {
			String hw = tags.get("highway").getAsString();
			boolean path = hw.equals("footway") || hw.equals("path") || hw.equals("cycleway")
					|| hw.equals("pedestrian") || hw.equals("steps") || hw.equals("track");
			double width = path ? 2.0 : roadWidthMeters(hw, tags);
			data.add(new OsmElement(path ? SurfaceKind.PATH : SurfaceKind.ROAD, pts, false, 0, width));
			return;
		}
		String natural = tags.has("natural") ? tags.get("natural").getAsString() : "";
		String waterway = tags.has("waterway") ? tags.get("waterway").getAsString() : "";
		String landuse = tags.has("landuse") ? tags.get("landuse").getAsString() : "";
		String leisure = tags.has("leisure") ? tags.get("leisure").getAsString() : "";

		if (natural.equals("water") || landuse.equals("reservoir") || waterway.equals("riverbank")) {
			if (closed) data.add(new OsmElement(SurfaceKind.WATER, pts, true, 0, 0));
			return;
		}
		if (!waterway.isEmpty()) {
			double width = switch (waterway) {
				case "river" -> 9.0;
				case "canal" -> 7.0;
				case "stream" -> 3.0;
				default -> 2.0;
			};
			data.add(new OsmElement(SurfaceKind.RIVER, pts, false, 0, width));
			return;
		}
		if (natural.equals("wood") || landuse.equals("forest")) {
			if (closed) data.add(new OsmElement(SurfaceKind.FOREST, pts, true, 0, 0));
			return;
		}
		if (landuse.equals("farmland") || landuse.equals("orchard") || landuse.equals("vineyard") || landuse.equals("plant_nursery")) {
			if (closed) data.add(new OsmElement(SurfaceKind.FARMLAND, pts, true, 0, 0));
			return;
		}
		if (landuse.equals("meadow") || landuse.equals("grass") || landuse.equals("recreation_ground")
				|| leisure.equals("park") || leisure.equals("pitch") || leisure.equals("garden") || leisure.equals("playground")) {
			if (closed) data.add(new OsmElement(SurfaceKind.PARK, pts, true, 0, 0));
			return;
		}
		if (natural.equals("beach") || natural.equals("sand")) {
			if (closed) data.add(new OsmElement(SurfaceKind.BEACH, pts, true, 0, 0));
		}
	}

	private static double parseHeightMeters(JsonObject tags) {
		if (tags.has("height")) {
			try {
				String h = tags.get("height").getAsString().replace("m", "").trim().split(" ")[0];
				return Double.parseDouble(h);
			} catch (Exception ignored) { }
		}
		if (tags.has("building:levels")) {
			try {
				return Double.parseDouble(tags.get("building:levels").getAsString().trim()) * 3.0 + 3.0;
			} catch (Exception ignored) { }
		}
		return 7.0; // ~2 floors default
	}

	private static double roadWidthMeters(String highway, JsonObject tags) {
		if (tags.has("lanes")) {
			try { return Math.max(3.0, Integer.parseInt(tags.get("lanes").getAsString().trim()) * 3.5); }
			catch (Exception ignored) { }
		}
		return switch (highway) {
			case "motorway", "motorway_link" -> 11.0;
			case "trunk", "primary" -> 9.0;
			case "secondary" -> 7.5;
			case "tertiary" -> 6.5;
			case "unclassified", "residential", "living_street" -> 5.5;
			case "service" -> 4.0;
			default -> 5.0;
		};
	}

	// ------------------------------------------------------------- disk cache
	private static Path cacheFile(String bbox) throws Exception {
		String hash = HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-1").digest(("v1|" + bbox).getBytes(StandardCharsets.UTF_8)));
		Path dir = FabricLoader.getInstance().getGameDir().resolve("realworld-cache").resolve("osm");
		Files.createDirectories(dir);
		return dir.resolve(hash + ".json");
	}

	private static String loadCached(String bbox) {
		try {
			Path file = cacheFile(bbox);
			if (!Files.exists(file)) return null;
			long ageMs = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
			if (ageMs > 7L * 24 * 3600 * 1000) return null; // stale after a week
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null;
		}
	}

	private static void saveCached(String bbox, String json) {
		try {
			Files.writeString(cacheFile(bbox), json, StandardCharsets.UTF_8);
		} catch (Exception ignored) { }
	}
}
