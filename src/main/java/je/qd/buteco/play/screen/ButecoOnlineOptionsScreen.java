package je.qd.buteco.play.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Minimal replacement for Minecraft 26.2's Online Options screen. It keeps the
 * vanilla Realms News & Invites widget but intentionally omits every Friends
 * List setting and link.
 */
public final class ButecoOnlineOptionsScreen extends Screen {
    private static final Component TITLE = Component.literal("Online Options");
    private static final Component REALMS_HEADING = Component.literal("Realms");

    private final Screen parent;
    private final AbstractWidget realmsOption;

    public ButecoOnlineOptionsScreen(Screen parent, AbstractWidget realmsOption) {
        super(TITLE);
        this.parent = parent;
        this.realmsOption = realmsOption;
    }

    @Override
    protected void init() {
        if (this.realmsOption != null) {
            this.realmsOption.setX((this.width - this.realmsOption.getWidth()) / 2);
            this.realmsOption.setY(90);
            this.addRenderableWidget(this.realmsOption);
        }

        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                        .bounds(this.width / 2 - 100, this.height - 40, 200, 20)
                        .build()
        );
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float tickProgress
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, tickProgress);

        int titleX = (this.width - this.font.width(TITLE)) / 2;
        graphics.text(this.font, TITLE, titleX, 20, 0xFFFFFFFF, false);

        if (this.realmsOption != null) {
            graphics.text(
                    this.font,
                    REALMS_HEADING,
                    this.realmsOption.getX(),
                    this.realmsOption.getY() - 24,
                    0xFFFFFFFF,
                    false
            );
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft == null) {
            return;
        }

        if (this.parent != null) {
            this.minecraft.gui.setScreen(this.parent);
        } else if (this.minecraft.level != null) {
            this.minecraft.gui.setScreen(null);
        } else {
            this.minecraft.gui.setScreen(new TitleScreen());
        }
    }
}
