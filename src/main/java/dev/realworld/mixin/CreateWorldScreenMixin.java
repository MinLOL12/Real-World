package dev.realworld.mixin;

import dev.realworld.client.screen.EarthSettingsScreen;
import dev.realworld.config.EarthConfig;
import dev.realworld.settings.EarthSettings;
import dev.realworld.world.EarthChunkGenerator;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Adds an "Earth Settings..." button to the create-world screen.
 *
 * <p>The button is always shown on this screen (never hidden), so it cannot
 * silently go missing. When the Earth world type is selected, Apply writes
 * the settings into the actual generator instance that will be stored in
 * the new world's level.dat; when another world type is selected, Apply
 * instead saves the settings as the defaults for the next Earth world
 * (config/realworld.json + the pending-settings the Earth generator adopts
 * when a world is created).</p>
 *
 * <p>Detection note: on preset switch, vanilla only updates
 * {@code GeneratorOptionsHolder.selectedDimensions()} - the holder's
 * {@code dimensionOptionsRegistry()} is merely the baseline registry used
 * to build the final one, so the selected dimensions must be read from
 * {@code selectedDimensions()} (checking the registry alone always sees
 * the initially-loaded preset and never detects Earth).</p>
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin extends Screen {

	@Shadow
	private WorldCreator worldCreator;

	@Unique
	private ButtonWidget realworld$earthButton;

	protected CreateWorldScreenMixin() {
		super(Text.literal(""));
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void realworld$onInit(CallbackInfo ci) {
		if (realworld$earthButton == null || !this.children().contains(realworld$earthButton)) {
			realworld$earthButton = addDrawableChild(ButtonWidget.builder(
							Text.literal("Earth Settings..."), b -> realworld$openSettings())
					.dimensions(this.width - 176, 6, 168, 20)
					.build());
		}
		realworld$earthButton.visible = true;
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("TAIL"))
	private void realworld$onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		// Defensive: nothing in vanilla should hide it, but never let the
		// button silently disappear again.
		if (realworld$earthButton != null) {
			realworld$earthButton.visible = true;
		}
	}

	@Unique
	private EarthChunkGenerator realworld$earthGenerator() {
		try {
			if (worldCreator == null || worldCreator.getGeneratorOptionsHolder() == null) return null;
			// Primary: the dimensions of the preset the user actually picked.
			DimensionOptionsRegistryHolder selected = worldCreator.getGeneratorOptionsHolder().selectedDimensions();
			if (selected != null) {
				Optional<DimensionOptions> overworld = selected.getOrEmpty(DimensionOptions.OVERWORLD);
				if (overworld.isPresent() && overworld.get().chunkGenerator() instanceof EarthChunkGenerator earth) {
					return earth;
				}
				ChunkGenerator generator = selected.getChunkGenerator();
				if (generator instanceof EarthChunkGenerator earth) {
					return earth;
				}
			}
			// Fallback: the baseline dimension options registry.
			DimensionOptions options = worldCreator.getGeneratorOptionsHolder().dimensionOptionsRegistry().get(DimensionOptions.OVERWORLD);
			if (options != null && options.chunkGenerator() instanceof EarthChunkGenerator earth) {
				return earth;
			}
		} catch (Throwable ignored) {
			// If vanilla internals shift, fall back to defaults mode below.
		}
		return null;
	}

	@Unique
	private void realworld$openSettings() {
		if (client == null) return;
		EarthChunkGenerator generator = realworld$earthGenerator();
		if (generator != null) {
			client.setScreen(new EarthSettingsScreen(this, generator.getSettings(), true, settings -> {
				generator.setSettings(settings);
				EarthConfig.save(settings);
			}));
		} else {
			// Earth preset not selected (or not detected): edit the defaults
			// that the next Earth world will use.
			EarthSettings initial = EarthConfig.current();
			client.setScreen(new EarthSettingsScreen(this, initial, false, EarthConfig::save));
		}
	}
}
