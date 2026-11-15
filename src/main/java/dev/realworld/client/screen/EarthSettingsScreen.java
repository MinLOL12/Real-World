package dev.realworld.client.screen;

import dev.realworld.settings.EarthSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Earth generation settings: 27 settings in 5 tabs (Region & Scale,
 * Terrain, Nature, Water, Structures & Roads). Used both from the
 * create-world screen (via the Earth button) and from the in-game
 * keybind, where it edits the defaults for the next Earth world.
 */
public class EarthSettingsScreen extends Screen {

	public enum Tab {
		REGION("Region & Scale"), TERRAIN("Terrain"), NATURE("Nature"), WATER("Water"), STRUCTURES("Structures & Roads");
		final String label;
		Tab(String label) { this.label = label; }
	}

	private final Screen parent;
	private final Consumer<EarthSettings> onApply;
	private final boolean earthActive;
	private Tab tab = Tab.REGION;

	// ---- working copies of every setting (records are immutable) ----
	// Region
	private double centerLat, centerLon, importSizeKm;
	private int scaleDenominator;
	private EarthSettings.SelectionMode selectionMode;
	private EarthSettings.OutsideRegion outsideRegion;
	private double minLat, maxLat, minLon, maxLon;
	private List<Double> polyLats, polyLons;
	// Terrain
	private EarthSettings.ElevationSource elevationSource;
	private double elevationExaggeration;
	private int seaLevel, smoothing;
	private boolean rockyCliffs;
	// Nature
	private boolean trees, vegetation, beaches, snow;
	private int treeDensity;
	// Water
	private boolean oceans, rivers, lakes, wetlands;
	private int waterDepth;
	// Structures
	private boolean buildings, roads, farmland;
	private double buildingHeightScale, roadWidthScale;
	private EarthSettings.BuildingStyle buildingStyle;

	private record Label(String text, int x, int y) {}
	private final List<Label> labels = new ArrayList<>();
	private final List<String> infoLines = new ArrayList<>();
	private int rowY;
	private TextFieldWidget scaleField;

	public EarthSettingsScreen(Screen parent, EarthSettings initial, boolean earthActive, Consumer<EarthSettings> onApply) {
		super(Text.literal("Earth Generation Settings"));
		this.parent = parent;
		this.onApply = onApply;
		this.earthActive = earthActive;
		load(initial);
	}

	private void load(EarthSettings s) {
		centerLat = s.region().centerLat(); centerLon = s.region().centerLon();
		scaleDenominator = s.region().scaleDenominator();
		selectionMode = s.region().selectionMode();
		importSizeKm = s.region().importSizeKm();
		outsideRegion = s.region().outsideRegion();
		minLat = s.region().minLat(); maxLat = s.region().maxLat();
		minLon = s.region().minLon(); maxLon = s.region().maxLon();
		polyLats = new ArrayList<>(s.region().polygonLats());
		polyLons = new ArrayList<>(s.region().polygonLons());

		elevationSource = s.terrain().elevationSource();
		elevationExaggeration = s.terrain().elevationExaggeration();
		seaLevel = s.terrain().seaLevel(); smoothing = s.terrain().smoothing();
		rockyCliffs = s.terrain().rockyCliffs();

		trees = s.nature().trees(); treeDensity = s.nature().treeDensity();
		vegetation = s.nature().vegetation(); beaches = s.nature().beaches(); snow = s.nature().snow();

		oceans = s.water().oceans(); rivers = s.water().rivers(); lakes = s.water().lakes();
		waterDepth = s.water().waterDepth(); wetlands = s.water().wetlands();

		buildings = s.structures().buildings(); buildingHeightScale = s.structures().buildingHeightScale();
		buildingStyle = s.structures().buildingStyle();
		roads = s.structures().roads(); roadWidthScale = s.structures().roadWidthScale();
		farmland = s.structures().farmland();
	}

	public EarthSettings buildSettings() {
		return new EarthSettings(
				new EarthSettings.Region(centerLat, centerLon, Math.max(1, scaleDenominator), selectionMode,
						importSizeKm, outsideRegion, minLat, maxLat, minLon, maxLon,
						List.copyOf(polyLats), List.copyOf(polyLons)),
				new EarthSettings.Terrain(elevationSource, elevationExaggeration, seaLevel, smoothing, rockyCliffs),
				new EarthSettings.Nature(trees, treeDensity, vegetation, beaches, snow),
				new EarthSettings.Water(oceans, rivers, lakes, waterDepth, wetlands),
				new EarthSettings.Structures(buildings, buildingHeightScale, buildingStyle, roads, roadWidthScale, farmland));
	}

	// ------------------------------------------------------------ layout
	@Override
	protected void init() {
		labels.clear();
		infoLines.clear();
		int contentLeft = width / 2 - 205;
		int tabW = 82;
		int totalW = tabW * Tab.values().length + 4 * (Tab.values().length - 1);
		int tx = width / 2 - totalW / 2;
		for (Tab t : Tab.values()) {
			ButtonWidget b = addDrawableChild(ButtonWidget.builder(Text.literal(t.label), btn -> {
				tab = t; rebuild();
			}).dimensions(tx, 24, tabW, 20).build());
			b.active = (t != tab);
			tx += tabW + 4;
		}

		if (!earthActive) {
			infoLines.add("Earth world type is not selected here, so Apply saves these as the defaults for your next Earth world.");
		}
		rowY = 56;
		switch (tab) {
			case REGION -> buildRegionTab(contentLeft);
			case TERRAIN -> buildTerrainTab(contentLeft);
			case NATURE -> buildNatureTab(contentLeft);
			case WATER -> buildWaterTab(contentLeft);
			case STRUCTURES -> buildStructuresTab(contentLeft);
		}

		int footerY = height - 28;
		addDrawableChild(ButtonWidget.builder(Text.literal("Map: Select Area..."), b -> openMap())
				.dimensions(contentLeft, footerY, 130, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Reset Defaults"), b -> { load(EarthSettings.DEFAULT); rebuild(); })
				.dimensions(contentLeft + 134, footerY, 110, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Apply"), b -> {
			onApply.accept(buildSettings());
			if (client != null) client.setScreen(parent);
		}).dimensions(width / 2 + 45, footerY, 80, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> {
			if (client != null) client.setScreen(parent);
		}).dimensions(width / 2 + 129, footerY, 76, 20).build());
	}

	private void rebuild() {
		clearChildren();
		init();
	}

	private void openMap() {
		if (client == null) return;
		EarthSettings.Region current = buildSettings().region();
		client.setScreen(new EarthMapScreen(this, current, region -> {
			centerLat = region.centerLat(); centerLon = region.centerLon();
			selectionMode = region.selectionMode();
			minLat = region.minLat(); maxLat = region.maxLat();
			minLon = region.minLon(); maxLon = region.maxLon();
			polyLats = new ArrayList<>(region.polygonLats());
			polyLons = new ArrayList<>(region.polygonLons());
			tab = Tab.REGION;
			rebuild();
		}));
	}

	// ------------------------------------------------------------ tabs
	private void buildRegionTab(int left) {
		doubleField(left, "Centre latitude", centerLat, v -> centerLat = v);
		doubleField(left, "Centre longitude", centerLon, v -> centerLon = v);
		scaleField = intField(left, "Scale denominator (1 = 1:1)", scaleDenominator, v -> scaleDenominator = Math.max(1, v));
		// quick-scale preset buttons
		labels.add(new Label("Quick scale:", left, rowY + 6));
		int[] presets = {1, 10, 100, 1000};
		int bx = widgetX();
		for (int p : presets) {
			addDrawableChild(ButtonWidget.builder(Text.literal("1:" + p), b -> {
				scaleDenominator = p;
				if (scaleField != null) scaleField.setText(Integer.toString(p));
			}).dimensions(bx, rowY, 62, 20).build());
			bx += 66;
		}
		rowY += 24;
		enumField(left, "Selection mode", selectionMode.name(), () -> {
			selectionMode = selectionMode.next();
			return selectionMode.name();
		});
		doubleField(left, "Import size (km, fallback)", importSizeKm, v -> importSizeKm = Math.max(0.1, v));
		enumField(left, "Outside selected area", outsideRegion.name(), () -> {
			outsideRegion = outsideRegion.next();
			return outsideRegion.name();
		});
		double widthKm = (maxLon - minLon) * 111.320 * Math.cos(Math.toRadians(centerLat));
		double heightKm = (maxLat - minLat) * 110.540;
		infoLines.add(String.format(Locale.ROOT, "Selection: lat %.4f..%.4f, lon %.4f..%.4f (%.1f x %.1f km, ~%d x %d blocks at 1:%d)",
				minLat, maxLat, minLon, maxLon, widthKm, heightKm,
				(int) (widthKm * 1000 / Math.max(1, scaleDenominator)), (int) (heightKm * 1000 / Math.max(1, scaleDenominator)),
				Math.max(1, scaleDenominator)));
		if (!polyLats.isEmpty()) infoLines.add("Lasso polygon active with " + polyLats.size() + " points - generation stays inside it.");
		infoLines.add("Use 'Map: Select Area...' to pick the area visually on OpenStreetMap (Box or Lasso).");
	}

	private void buildTerrainTab(int left) {
		enumField(left, "Elevation source", elevationSource.name(), () -> {
			elevationSource = elevationSource.next();
			return elevationSource.name();
		});
		doubleField(left, "Elevation exaggeration", elevationExaggeration, v -> elevationExaggeration = Math.max(0.0, v));
		intField(left, "Sea level (Y)", seaLevel, v -> seaLevel = v);
		intCycleField(left, "Terrain smoothing", smoothing, 0, 4, v -> smoothing = v);
		boolField(left, "Rocky cliffs on steep slopes", rockyCliffs, v -> rockyCliffs = v);
		infoLines.add("AUTO_REMOTE downloads real elevation (Open-Meteo). PROCEDURAL is fully offline.");
		infoLines.add("Tip: at large scales (1:1000+) raise exaggeration so hills stay visible.");
	}

	private void buildNatureTab(int left) {
		boolField(left, "Trees (OSM forests + tree nodes)", trees, v -> trees = v);
		intCycleField(left, "Tree density %", treeDensity, 0, 100, 5, v -> treeDensity = v);
		boolField(left, "Vegetation (grass, ferns, flowers)", vegetation, v -> vegetation = v);
		boolField(left, "Sandy beaches near water", beaches, v -> beaches = v);
		boolField(left, "Snow on high / cold ground", snow, v -> snow = v);
	}

	private void buildWaterTab(int left) {
		boolField(left, "Oceans / seas below sea level", oceans, v -> oceans = v);
		boolField(left, "Rivers & streams (OSM waterways)", rivers, v -> rivers = v);
		boolField(left, "Lakes (OSM water polygons)", lakes, v -> lakes = v);
		intField(left, "Water depth (blocks)", waterDepth, v -> waterDepth = Math.max(1, v));
		boolField(left, "Wetlands (lily pads on calm water)", wetlands, v -> wetlands = v);
	}

	private void buildStructuresTab(int left) {
		boolField(left, "Buildings (OSM footprints)", buildings, v -> buildings = v);
		doubleField(left, "Building height scale", buildingHeightScale, v -> buildingHeightScale = Math.max(0.0, v));
		enumField(left, "Building style", buildingStyle.name(), () -> {
			buildingStyle = buildingStyle.next();
			return buildingStyle.name();
		});
		boolField(left, "Roads & paths (OSM highways)", roads, v -> roads = v);
		doubleField(left, "Road width scale", roadWidthScale, v -> roadWidthScale = Math.max(0.1, v));
		boolField(left, "Farmland with crops", farmland, v -> farmland = v);
		infoLines.add("Building heights come from OSM building:levels / height tags when present.");
	}

	// ------------------------------------------------------------ field helpers
	private int widgetX() { return width / 2 - 55; }
	private static final int WIDGET_W = 260;

	private interface StringAction { String run(); }

	private void enumField(int left, String label, String initial, StringAction cycle) {
		labels.add(new Label(label, left, rowY + 6));
		addDrawableChild(ButtonWidget.builder(Text.literal(initial), b -> b.setMessage(Text.literal(cycle.run())))
				.dimensions(widgetX(), rowY, WIDGET_W, 20).build());
		rowY += 24;
	}

	private void boolField(int left, String label, boolean initial, Consumer<Boolean> setter) {
		labels.add(new Label(label, left, rowY + 6));
		addDrawableChild(ButtonWidget.builder(Text.literal(initial ? "ON" : "OFF"), b -> {
			boolean now = b.getMessage().getString().equals("ON");
			setter.accept(!now);
			b.setMessage(Text.literal(!now ? "ON" : "OFF"));
		}).dimensions(widgetX(), rowY, WIDGET_W, 20).build());
		rowY += 24;
	}

	private void intCycleField(int left, String label, int initial, int min, int max, Consumer<Integer> setter) {
		intCycleField(left, label, initial, min, max, 1, setter);
	}

	private void intCycleField(int left, String label, int initial, int min, int max, int step, Consumer<Integer> setter) {
		labels.add(new Label(label, left, rowY + 6));
		int[] value = {initial};
		addDrawableChild(ButtonWidget.builder(Text.literal(Integer.toString(initial)), b -> {
			value[0] += step;
			if (value[0] > max) value[0] = min;
			setter.accept(value[0]);
			b.setMessage(Text.literal(Integer.toString(value[0])));
		}).dimensions(widgetX(), rowY, WIDGET_W, 20).build());
		rowY += 24;
	}

	private TextFieldWidget doubleField(int left, String label, double initial, Consumer<Double> setter) {
		labels.add(new Label(label, left, rowY + 6));
		TextFieldWidget field = new TextFieldWidget(textRenderer, widgetX(), rowY, WIDGET_W, 20, Text.literal(label));
		field.setText(String.format(Locale.ROOT, "%.5f", initial));
		field.setChangedListener(text -> {
			try { setter.accept(Double.parseDouble(text.trim())); } catch (Exception ignored) { }
		});
		addDrawableChild(field);
		rowY += 24;
		return field;
	}

	private TextFieldWidget intField(int left, String label, int initial, Consumer<Integer> setter) {
		labels.add(new Label(label, left, rowY + 6));
		TextFieldWidget field = new TextFieldWidget(textRenderer, widgetX(), rowY, WIDGET_W, 20, Text.literal(label));
		field.setText(Integer.toString(initial));
		field.setChangedListener(text -> {
			try { setter.accept(Integer.parseInt(text.trim())); } catch (Exception ignored) { }
		});
		addDrawableChild(field);
		rowY += 24;
		return field;
	}

	// ------------------------------------------------------------ render
	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
		for (Label label : labels) {
			context.drawTextWithShadow(textRenderer, label.text(), label.x(), label.y(), 0xE8E8E8);
		}
		int y = height - 64;
		for (String line : infoLines) {
			context.drawCenteredTextWithShadow(textRenderer, line, width / 2, y, 0x9DB89D);
			y += 11;
		}
		String footer = "Data: (c) OpenStreetMap contributors | Elevation: Open-Meteo | Settings are saved into the world";
		context.drawCenteredTextWithShadow(textRenderer, footer, width / 2, height - 40, 0x777777);
	}
}
