package je.qd.buteco.play.mixin;

import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Disables the separate Realms notifications overlay on the title screen.
 * The diamond and newspaper are rendered by that overlay rather than being
 * ordinary title-screen widgets, so removing widgets alone cannot hide them.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
    @Inject(
            method = "realmsNotificationsEnabled",
            at = @At("HEAD"),
            cancellable = true
    )
    private void butecoPlay$disableRealmsNotifications(
            CallbackInfoReturnable<Boolean> callback
    ) {
        callback.setReturnValue(false);
    }
}
