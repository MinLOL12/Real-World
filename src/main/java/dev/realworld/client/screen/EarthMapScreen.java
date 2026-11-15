package dev.realworld.client.screen;

import dev.realworld.client.map.MapTileManager;
import dev.realworld.geo.GeoMath;
import dev.realworld.settings.EarthSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Interactive OpenStreetMap area picker.
 *
 * <ul>
 *   <li><b>Pan</b> mode: drag to move, scroll to zoom.</li>
 *   <li><b>Box</b> mode: drag a rectangle around the area to import.</li>
 *   <li><b>Lasso</b> mode: drag a freehand loop around the area; the loop is
 *       stored as a polygon and generation only happens inside it.</li>
 * </ul>
 * On confirm, the selection (bounding box + optional lasso polygon) is
 * written back into the Region settings.
 */
public class EarthMapScreen extends Screen {

	public enum Mode { PAN, BOX, LASSO }

	private final Screen parent;
	private final EarthSettings.Region baseRegion;
	private final Consumer<EarthSettings.Region> onConfirm;

	private Mode mode = Mode.BOX;
	private int zoom = 14;
	private double centerPx; // world pixel coords at current zoom
	private double centerPy;

	// Interaction state
	private boolean dragging = false;
	private double dragStartLatLon[];      // box start {lat, lon}
	private double dragCurrentLatLon[];
	private final List<double[]> lassoLatLon = new ArrayList<>(); // {lat, lon}
	private double lastMouseX, lastMouseY;

	// Current selection
	private boolean hasSelection = false;
	private double selMinLat, selMaxLat, selMinLon, selMaxLon;
	private final List<Double> selPolyLats = new ArrayList<>();
	private final List<Double> selPolyLons = new ArrayList<>();

	private ButtonWidget modeButton;

	public EarthMapScreen(Screen parent, EarthSettings.Region region, Consumer<EarthSettings.Region> onConfirm) {
		super(Text.literal("Select area on OpenStreetMap"));
		this.parent = parent;
		this.baseRegion = region;
		this.onConfirm = onConfirm;
		// Restore an existing selection if there is one.
		this.selMinLat = region.minLat(); this.selMaxLat = region.maxLat();
		this.selMinLon = region.minLon(); this.selMaxLon = region.maxLon();
		this.hasSelection = true;
		if (region.hasPolygon()) {
			this.mode = Mode.LASSO;
			for (int i = 0; i < region.polygonLats().size(); i++) {
				selPolyLats.add(region.polygonLats().get(i));
				selPolyLons.add(region.polygonLons().get(i));
			}
		}
		setCenter(region.centerLat(), region.centerLon());
	}

	// ------------------------------------------------------------ slippy map math
	private void setCenter(double lat, double lon) {
		double[] px = latLonToWorldPx(lat, lon, zoom);
		centerPx = px[0]; centerPy = px[1];
	}

	public static double[] latLonToWorldPx(double lat, double lon, int zoom) {
		double n = Math.scalb(256.0, zoom);
		double x = (lon + 180.0) / 360.0 * n;
		double latRad = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, lat)));
		double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
		return new double[]{x, y};
	}

	public static double[] worldPxToLatLon(double px, double py, int zoom) {
		double n = Math.scalb(256.0, zoom);
		double lon = px / n * 360.0 - 180.0;
		double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * py / n))));
		return new double[]{lat, lon};
	}

	private double topLeftX() { return centerPx - width / 2.0; }
	private double topLeftY() { return centerPy - height / 2.0; }

	private double[] screenToLatLon(double sx, double sy) {
		return worldPxToLatLon(topLeftX() + sx, topLeftY() + sy, zoom);
	}

	private double[] latLonToScreen(double lat, double lon) {
		double[] px = latLonToWorldPx(lat, lon, zoom);
		return new double[]{px[0] - topLeftX(), px[1] - topLeftY()};
	}

	// ------------------------------------------------------------ widgets
	@Override
	protected void init() {
		int barY = 6;
		modeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mode: " + mode), b -> {
			mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
			b.setMessage(Text.literal("Mode: " + mode));
		}).dimensions(8, barY, 110, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Zoom +"), b -> zoomBy(1))
				.dimensions(122, barY, 58, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Zoom -"), b -> zoomBy(-1))
				.dimensions(184, barY, 58, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Clear selection"), b -> {
			hasSelection = false;
			selPolyLats.clear(); selPolyLons.clear();
			lassoLatLon.clear();
		}).dimensions(width - 248, barY, 110, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Use selection"), b -> confirm())
				.dimensions(width - 134, barY, 126, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
				.dimensions(width - 134, height - 26, 126, 20).build());
	}

	private void zoomBy(int dz) {
		int newZoom = Math.max(3, Math.min(18, zoom + dz));
		if (newZoom == zoom) return;
		double[] ll = worldPxToLatLon(centerPx, centerPy, zoom);
		zoom = newZoom;
		setCenter(ll[0], ll[1]);
	}

	private void confirm() {
		if (hasSelection) {
			List<Double> polyLats = mode == Mode.LASSO ? List.copyOf(selPolyLats) : baseRegion.polygonLats();
			List<Double> polyLons = mode == Mode.LASSO ? List.copyOf(selPolyLons) : baseRegion.polygonLons();
			EarthSettings.SelectionMode selMode = mode == Mode.LASSO && polyLats.size() >= 3
					? EarthSettings.SelectionMode.LASSO : EarthSettings.SelectionMode.BOX;
			if (selMode == EarthSettings.SelectionMode.BOX) { polyLats = List.of(); polyLons = List.of(); }
			EarthSettings.Region region = new EarthSettings.Region(
					(selMinLat + selMaxLat) / 2.0, (selMinLon + selMaxLon) / 2.0,
					baseRegion.scaleDenominator(), selMode, baseRegion.importSizeKm(), baseRegion.outsideRegion(),
					selMinLat, selMaxLat, selMinLon, selMaxLon, polyLats, polyLons);
			onConfirm.accept(region);
		}
		close();
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(parent);
	}

	// ------------------------------------------------------------ input
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) return true;
		if (button != 0 || mouseY < 28) return false;
		dragging = true;
		lastMouseX = mouseX; lastMouseY = mouseY;
		double[] ll = screenToLatLon(mouseX, mouseY);
		if (mode == Mode.BOX) {
			dragStartLatLon = ll; dragCurrentLatLon = ll;
		} else if (mode == Mode.LASSO) {
			lassoLatLon.clear();
			lassoLatLon.add(ll);
		}
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (!dragging) return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
		if (mode == Mode.PAN) {
			centerPx -= deltaX;
			centerPy -= deltaY;
		} else if (mode == Mode.BOX) {
			dragCurrentLatLon = screenToLatLon(mouseX, mouseY);
		} else if (mode == Mode.LASSO) {
			double[] ll = screenToLatLon(mouseX, mouseY);
			if (!lassoLatLon.isEmpty()) {
				double[] lastPx = latLonToScreen(lassoLatLon.get(lassoLatLon.size() - 1)[0], lassoLatLon.get(lassoLatLon.size() - 1)[1]);
				if (Math.hypot(lastPx[0] - mouseX, lastPx[1] - mouseY) > 6) lassoLatLon.add(ll);
			} else {
				lassoLatLon.add(ll);
			}
		}
		lastMouseX = mouseX; lastMouseY = mouseY;
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (dragging && button == 0) {
			dragging = false;
			if (mode == Mode.BOX && dragStartLatLon != null && dragCurrentLatLon != null) {
				selMinLat = Math.min(dragStartLatLon[0], dragCurrentLatLon[0]);
				selMaxLat = Math.max(dragStartLatLon[0], dragCurrentLatLon[0]);
				selMinLon = Math.min(dragStartLatLon[1], dragCurrentLatLon[1]);
				selMaxLon = Math.max(dragStartLatLon[1], dragCurrentLatLon[1]);
				selPolyLats.clear(); selPolyLons.clear();
				hasSelection = selMaxLat - selMinLat > 0.00005 && selMaxLon - selMinLon > 0.00005;
				dragStartLatLon = null; dragCurrentLatLon = null;
			} else if (mode == Mode.LASSO && lassoLatLon.size() >= 3) {
				selPolyLats.clear(); selPolyLons.clear();
				double mnLat = 90, mxLat = -90, mnLon = 180, mxLon = -180;
				// Downsample to at most ~200 vertices for compact world files.
				int step = Math.max(1, lassoLatLon.size() / 200);
				for (int i = 0; i < lassoLatLon.size(); i += step) {
					double[] p = lassoLatLon.get(i);
					selPolyLats.add(p[0]); selPolyLons.add(p[1]);
					mnLat = Math.min(mnLat, p[0]); mxLat = Math.max(mxLat, p[0]);
					mnLon = Math.min(mnLon, p[1]); mxLon = Math.max(mxLon, p[1]);
				}
				selMinLat = mnLat; selMaxLat = mxLat; selMinLon = mnLon; selMaxLon = mxLon;
				hasSelection = true;
			}
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		zoomBy(amount > 0 ? 1 : -1);
		return true;
	}

	// ------------------------------------------------------------ render
	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, 0xFF1A2B1A);
		renderMap(context);
		renderSelection(context);
		super.render(context, mouseX, mouseY, delta);

		double[] centre = worldPxToLatLon(centerPx, centerPy, zoom);
		String centreText = String.format(Locale.ROOT, "Centre %.5f, %.5f  |  Zoom %d  |  Mode: %s",
				centre[0], centre[1], zoom, mode);
		context.drawTextWithShadow(textRenderer, centreText, 8, height - 36, 0xFFFFFF);
		String hint = switch (mode) {
			case PAN -> "Drag to pan, scroll to zoom. Switch mode to Box or Lasso to select an area.";
			case BOX -> "Drag on the map to draw the import rectangle.";
			case LASSO -> "Drag a freehand loop around the area to import, then release.";
		};
		context.drawTextWithShadow(textRenderer, hint, 8, height - 24, 0xBFD8BF);
		if (hasSelection) {
			double widthKm = (selMaxLon - selMinLon) * GeoMath.metersPerDegLon((selMinLat + selMaxLat) / 2) / 1000.0;
			double heightKm = (selMaxLat - selMinLat) * GeoMath.METERS_PER_DEG_LAT / 1000.0;
			int scale = Math.max(1, baseRegion.scaleDenominator());
			String sel = String.format(Locale.ROOT, "Selected %.2f km x %.2f km  (~%d x %d blocks at 1:%d)",
					widthKm, heightKm, (int) (widthKm * 1000 / scale), (int) (heightKm * 1000 / scale), scale);
			context.drawTextWithShadow(textRenderer, sel, 8, 32, 0x7CFC90);
		}
		String attribution = "(c) OpenStreetMap contributors";
		context.drawTextWithShadow(textRenderer, attribution, width - textRenderer.getWidth(attribution) - 8, height - 36, 0xCCCCCC);
	}

	private void renderMap(DrawContext context) {
		int tileMinX = (int) Math.floor(topLeftX() / 256.0);
		int tileMinY = (int) Math.floor(topLeftY() / 256.0);
		int tileMaxX = (int) Math.floor((topLeftX() + width) / 256.0);
		int tileMaxY = (int) Math.floor((topLeftY() + height) / 256.0);
		for (int ty = tileMinY; ty <= tileMaxY; ty++) {
			for (int tx = tileMinX; tx <= tileMaxX; tx++) {
				int sx = (int) Math.round(tx * 256.0 - topLeftX());
				int sy = (int) Math.round(ty * 256.0 - topLeftY());
				Identifier id = MapTileManager.getTile(zoom, tx, ty);
				if (id != null) {
					context.drawTexture(id, sx, sy, 0, 0, 256, 256, 256, 256);
				} else {
					context.fill(sx, sy, sx + 256, sy + 256, ((tx + ty) & 1) == 0 ? 0xFF223322 : 0xFF1E2E1E);
				}
			}
		}
		// Centre crosshair
		context.fill(width / 2 - 6, height / 2, width / 2 + 7, height / 2 + 1, 0xFFFF4444);
		context.fill(width / 2, height / 2 - 6, width / 2 + 1, height / 2 + 7, 0xFFFF4444);
	}

	private void renderSelection(DrawContext context) {
		// Live box drag
		if (mode == Mode.BOX && dragging && dragStartLatLon != null && dragCurrentLatLon != null) {
			drawBox(context, dragStartLatLon, dragCurrentLatLon);
			return;
		}
		if (!hasSelection) {
			if (mode == Mode.LASSO) drawPolyline(context, lassoLatLon, true);
			return;
		}
		if (!selPolyLats.isEmpty()) {
			List<double[]> pts = new ArrayList<>();
			for (int i = 0; i < selPolyLats.size(); i++) pts.add(new double[]{selPolyLats.get(i), selPolyLons.get(i)});
			drawPolyline(context, pts, true);
		} else {
			double[] a = latLonToScreen(selMaxLat, selMinLon);
			double[] b = latLonToScreen(selMinLat, selMaxLon);
			context.fill((int) a[0], (int) a[1], (int) b[0], (int) b[1], 0x4033AAFF);
			drawRectBorder(context, (int) a[0], (int) a[1], (int) b[0], (int) b[1], 0xFF7CFC90);
		}
		if (mode == Mode.LASSO && dragging) drawPolyline(context, lassoLatLon, false);
	}

	private void drawBox(DrawContext context, double[] aLatLon, double[] bLatLon) {
		double[] a = latLonToScreen(aLatLon[0], aLatLon[1]);
		double[] b = latLonToScreen(bLatLon[0], bLatLon[1]);
		int x1 = (int) Math.min(a[0], b[0]), x2 = (int) Math.max(a[0], b[0]);
		int y1 = (int) Math.min(a[1], b[1]), y2 = (int) Math.max(a[1], b[1]);
		context.fill(x1, y1, x2, y2, 0x4033AAFF);
		drawRectBorder(context, x1, y1, x2, y2, 0xFFFFFFFF);
	}

	private void drawRectBorder(DrawContext context, int x1, int y1, int x2, int y2, int color) {
		context.fill(x1, y1, x2, y1 + 1, color);
		context.fill(x1, y2 - 1, x2, y2, color);
		context.fill(x1, y1, x1 + 1, y2, color);
		context.fill(x2 - 1, y1, x2, y2, color);
	}

	private void drawPolyline(DrawContext context, List<double[]> latLonPts, boolean close) {
		if (latLonPts.size() < 2) return;
		int n = latLonPts.size();
		int segments = close ? n : n - 1;
		for (int i = 0; i < segments; i++) {
			double[] a = latLonToScreen(latLonPts.get(i)[0], latLonPts.get(i)[1]);
			double[] b = latLonToScreen(latLonPts.get((i + 1) % n)[0], latLonPts.get((i + 1) % n)[1]);
			drawSegment(context, (int) a[0], (int) a[1], (int) b[0], (int) b[1], 0xFF7CFC90);
		}
	}

	private void drawSegment(DrawContext context, int x1, int y1, int x2, int y2, int color) {
		int steps = Math.max(1, Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)));
		for (int i = 0; i <= steps; i++) {
			int x = x1 + (x2 - x1) * i / steps;
			int y = y1 + (y2 - y1) * i / steps;
			context.fill(x - 1, y - 1, x + 2, y + 2, color);
		}
	}
}
