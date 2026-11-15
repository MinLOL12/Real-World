package dev.realworld.mixin;

import dev.realworld.client.screen.EarthSettingsScreen;
import dev.realworld.config.EarthConfig;
import dev.realworld.world.EarthChunkGenerator;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.world.dimension.LevelStem;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds an "Earth Settings..." button to the create-world screen. The button
 * is only visible while the selected world preset uses the RealWorld Earth
 * chunk generator (e.g. the "Earth" world type added by this mod). Applying
 * the settings mutates the generator instance that will be written into the
 * new world's level.dat, so the choices are permanently stored with the
 * world; they are also saved to config/realworld.json as the defaults for
 * next time.
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
		realworld$earthButton = addDrawableChild(ButtonWidget.builder(
						Text.literal("Earth Settings..."), b -> realworld$openSettings())
				.dimensions(this.width - 176, 6, 168, 20)
				.build());
		realworld$refreshButton();
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("TAIL"))
	private void realworld$onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		realworld$refreshButton();
	}

	@Unique
	private EarthChunkGenerator realworld$earthGenerator() {
		try {
			if (worldCreator == null || worldCreator.getGeneratorOptions() == null) return null;
			LevelStem stem = worldCreator.getGeneratorOptions().getDimensions().get(LevelStem.OVERWORLD);
			if (stem == null) return null;
			ChunkGenerator generator = stem.generator();
			if (generator instanceof EarthChunkGenerator earth) return earth;
		} catch (Throwable ignored) {
			// If vanilla internals shift, the button simply stays hidden.
		}
		return null;
	}

	@Unique
	private void realworld$refreshButton() {
		if (realworld$earthButton != null) {
			realworld$earthButton.visible = realworld$earthGenerator() != null;
		}
	}

	@Unique
	private void realworld$openSettings() {
		EarthChunkGenerator generator = realworld$earthGenerator();
		if (generator == null || client == null) return;
		client.setScreen(new EarthSettingsScreen(this, generator.getSettings(), settings -> {
			generator.setSettings(settings);
			EarthConfig.save(settings);
		}));
	}
}
