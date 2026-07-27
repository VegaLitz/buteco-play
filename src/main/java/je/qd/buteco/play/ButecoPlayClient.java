package je.qd.buteco.play;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class ButecoPlayClient implements ClientModInitializer {
    private static final String SERVER_NAME = "Buteco";
    private static final String DEFAULT_SERVER_ADDRESS = "buteco.qd.je";
    private static final String CONFIG_FILE_NAME = "buteco.txt";

    private static final Identifier PLAY_BUTECO_TEXTURE = Identifier.fromNamespaceAndPath(
            "buteco_play",
            "textures/gui/play_buteco.png"
    );
    // Minecraft still renders the logo inside a compact GUI button, so there is
    // a limit to how sharp it can appear on screen. The bundled texture is a
    // carefully downsampled high-quality version of the original artwork.
    private static final int PLAY_TEXTURE_WIDTH = 168;
    private static final int PLAY_TEXTURE_HEIGHT = 49;

    // The complete top group follows the same horizontal bounds as the
    // Options/Quit row. The logo button occupies the remaining width beside
    // the three compact controls.
    private static final int DEFAULT_BOTTOM_GROUP_WIDTH = 204;
    private static final int PLAY_BUTTON_PADDING = 4;
    private static final int SIDE_BUTTON_GAP = 4;
    private static final int SIDE_COLUMN_GAP = 4;
    private static final int MENU_ROW_GAP = 4;

    /**
     * Wait a few extracted frames before changing the menu. Mod Menu can add its
     * title-screen button from its own callback, so editing immediately during
     * AFTER_INIT can run too early depending on mod initialization order.
     */
    private static final int MAX_EXTRACT_ATTEMPTS = 3;

    @Override
    public void onInitializeClient() {
        // Create config/buteco.txt on first launch. The address is read again
        // whenever the logo button is pressed, so editing it needs no restart.
        ensureServerConfigExists();

        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
            // The vanilla Disconnect button normally sends multiplayer players back
            // to the saved-server list. This client intentionally has no multiplayer
            // menu, so redirect that screen to the customized title screen instead.
            if (screen instanceof JoinMultiplayerScreen) {
                minecraft.execute(() -> {
                    if (minecraft.gui.screen() == screen) {
                        minecraft.gui.setScreen(new TitleScreen());
                    }
                });
                return;
            }

            // Remove Friends controls and keep Online... disabled, including widgets
            // that another mod adds after screen initialization.
            cleanRestrictedWidgets(screen);
            ScreenEvents.afterExtract(screen).register(
                    (extractedScreen, graphics, mouseX, mouseY, tickDelta) ->
                            cleanRestrictedWidgets(extractedScreen)
            );

            // The O key can still try to open the Friends overlay. Close any Friends
            // UI immediately so the feature is inaccessible even without a button.
            if (isFriendsScreen(screen)) {
                closeFriendsScreen(minecraft, screen);
                return;
            }

            if (!(screen instanceof TitleScreen)) {
                return;
            }

            int[] attempts = {0};
            TitleLayout[] layout = {null};

            ScreenEvents.afterExtract(screen).register(
                    (extractedScreen, graphics, mouseX, mouseY, tickDelta) -> {
                        if (layout[0] == null) {
                            attempts[0]++;
                            boolean force = attempts[0] >= MAX_EXTRACT_ATTEMPTS;

                            layout[0] = replaceMainMenuButtons(
                                    minecraft,
                                    extractedScreen,
                                    scaledWidth,
                                    scaledHeight,
                                    force
                            );
                        }

                        if (layout[0] != null) {
                            // Keep the three side controls in their column even if
                            // Mod Menu or another title-screen mod adjusts them late.
                            placeSideButtons(layout[0].playButton(), layout[0].sideButtons());

                            // Realms and other late-added widgets must not cover the
                            // custom logo button.
                            removeWidgetsOverlappingPlayRow(
                                    extractedScreen,
                                    layout[0].playButton()
                            );

                            // Draw the supplied BUTECO artwork over the blank vanilla
                            // button, preserving the normal hover/click background.
                            drawPlayLogo(graphics, layout[0].playButton());

                            // Vanilla tooltips are extracted before this late logo layer,
                            // which made their text appear behind the artwork. Draw our own
                            // tooltip last so it is always visible on top of the logo.
                            drawSideButtonTooltip(
                                    minecraft,
                                    graphics,
                                    layout[0].playButton(),
                                    layout[0].sideButtons(),
                                    mouseX,
                                    mouseY
                            );
                        }
                    }
            );
        });
    }

    /**
     * @return the new title layout once Mod Menu is ready, or {@code null}
     *         when it should wait one more extracted frame.
     */
    private static TitleLayout replaceMainMenuButtons(
            Minecraft minecraft,
            Screen titleScreen,
            int scaledWidth,
            int scaledHeight,
            boolean force
    ) {
        List<AbstractWidget> widgets = Screens.getWidgets(titleScreen);

        AbstractWidget modsButton = widgets.stream()
                .filter(ButecoPlayClient::isModsButton)
                .findFirst()
                .orElse(null);

        // Mod Menu may register its title-screen callback after this mod.
        if (modsButton == null && !force) {
            return null;
        }

        AbstractWidget realmsButton = widgets.stream()
                .filter(ButecoPlayClient::isRealmsButton)
                .findFirst()
                .orElse(null);

        AbstractWidget optionsButton = widgets.stream()
                .filter(ButecoPlayClient::isOptionsButton)
                .findFirst()
                .orElse(null);

        AbstractWidget quitButton = widgets.stream()
                .filter(ButecoPlayClient::isQuitButton)
                .findFirst()
                .orElse(null);

        // Resolve these before removing anything. Icon-only buttons may have an
        // empty visible label, so a same-row fallback is included.
        List<AbstractWidget> sideButtons = findSideButtons(widgets, modsButton);
        normalizeSideButtons(sideButtons);

        // Remove the three vanilla play-mode buttons, an older custom Play button,
        // Friends, and auxiliary widgets occupying the Realms row.
        AbstractWidget finalModsButton = modsButton;
        AbstractWidget finalRealmsButton = realmsButton;
        widgets.removeIf(widget -> isVanillaGameModeButton(widget)
                || isExistingPlayButton(widget)
                || isFriendsWidget(widget)
                || isRealmsRowCompanion(widget, finalRealmsButton, finalModsButton));

        int columnWidth = sideButtons.stream()
                .mapToInt(AbstractWidget::getWidth)
                .max()
                .orElse(20);

        int columnHeight = sideButtons.stream()
                .mapToInt(AbstractWidget::getHeight)
                .sum()
                + SIDE_BUTTON_GAP * Math.max(0, sideButtons.size() - 1);

        // Use the actual bottom-row bounds whenever possible. This makes the
        // left edge of the compact column line up with Options, and the right
        // edge of the logo button line up with Quit Game.
        int groupLeft;
        int groupRight;

        if (optionsButton != null && quitButton != null) {
            groupLeft = Math.min(optionsButton.getX(), quitButton.getX());
            groupRight = Math.max(
                    optionsButton.getX() + optionsButton.getWidth(),
                    quitButton.getX() + quitButton.getWidth()
            );
        } else {
            groupLeft = (scaledWidth - DEFAULT_BOTTOM_GROUP_WIDTH) / 2;
            groupRight = groupLeft + DEFAULT_BOTTOM_GROUP_WIDTH;
        }

        int optionsY = optionsButton != null
                ? optionsButton.getY()
                : scaledHeight / 4 + 100;

        int groupWidth = groupRight - groupLeft;
        groupRight = Math.min(scaledWidth - 10, groupLeft + groupWidth);
        groupLeft = Math.max(10, groupRight - groupWidth);

        int x = groupLeft + columnWidth + SIDE_COLUMN_GAP;
        int width = Math.max(120, groupRight - x);
        int height = Math.max(20, columnHeight);
        int y = Math.max(20, optionsY - MENU_ROW_GAP - height);

        Button playButton = Button.builder(
                        Component.empty(),
                        button -> connectToButeco(minecraft, titleScreen)
                )
                .bounds(x, y, width, height)
                .build();

        widgets.add(playButton);
        placeSideButtons(playButton, sideButtons);
        removeWidgetsOverlappingPlayRow(titleScreen, playButton);

        return new TitleLayout(playButton, List.copyOf(sideButtons));
    }

    /**
     * Finds Accessibility, Language, and Mods. Minecraft and resource packs may
     * use icon-only widgets, so untranslated message checks are backed by the
     * compact-button row immediately surrounding Mod Menu.
     */
    private static List<AbstractWidget> findSideButtons(
            List<AbstractWidget> widgets,
            AbstractWidget modsButton
    ) {
        AbstractWidget accessibilityButton = widgets.stream()
                .filter(ButecoPlayClient::isAccessibilityButton)
                .findFirst()
                .orElse(null);

        AbstractWidget languageButton = widgets.stream()
                .filter(ButecoPlayClient::isLanguageButton)
                .findFirst()
                .orElse(null);

        if (modsButton != null && (accessibilityButton == null || languageButton == null)) {
            int modsCenterY = modsButton.getY() + modsButton.getHeight() / 2;

            List<AbstractWidget> compactRow = widgets.stream()
                    .filter(widget -> widget != modsButton)
                    .filter(widget -> !isFriendsWidget(widget))
                    .filter(widget -> !isRealmsButton(widget))
                    .filter(widget -> widget.getWidth() <= 24 && widget.getHeight() <= 24)
                    .filter(widget -> Math.abs(
                            widget.getY() + widget.getHeight() / 2 - modsCenterY
                    ) <= 3)
                    .sorted(Comparator.comparingInt(AbstractWidget::getX))
                    .toList();

            // The vanilla horizontal order is Language, Accessibility, Mods.
            if (languageButton == null && !compactRow.isEmpty()) {
                languageButton = compactRow.get(0);
            }

            if (accessibilityButton == null) {
                for (AbstractWidget candidate : compactRow) {
                    if (candidate != languageButton) {
                        accessibilityButton = candidate;
                        break;
                    }
                }
            }
        }

        List<AbstractWidget> result = new ArrayList<>(3);

        // Requested vertical order: Mods, Accessibility, Language.
        addUnique(result, modsButton);
        addUnique(result, accessibilityButton);
        addUnique(result, languageButton);

        return result;
    }

    private static void addUnique(
            List<AbstractWidget> widgets,
            AbstractWidget candidate
    ) {
        if (candidate != null && !widgets.contains(candidate)) {
            widgets.add(candidate);
        }
    }

    private static void normalizeSideButtons(List<AbstractWidget> sideButtons) {
        for (AbstractWidget widget : sideButtons) {
            hideWidgetLabel(widget);
            clearWidgetTooltip(widget);
            shrinkWidgetToIcon(widget);
        }
    }

    /**
     * Some compact buttons still keep their long text label internally. With the
     * custom title layout that text can render underneath the logo, so clear it.
     */
    private static void hideWidgetLabel(AbstractWidget widget) {
        try {
            Method setMessage = widget.getClass().getMethod("setMessage", Component.class);
            setMessage.invoke(widget, Component.empty());
            return;
        } catch (ReflectiveOperationException ignored) {
            // Fall through to the field-based fallback below.
        }

        Class<?> current = widget.getClass();
        while (current != null) {
            try {
                Field messageField = current.getDeclaredField("message");
                messageField.setAccessible(true);
                messageField.set(widget, Component.empty());
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (ReflectiveOperationException ignored) {
                return;
            }
        }
    }

    /**
     * The icon buttons normally show hover tooltips such as "Accessibility
     * Settings". Because the supplied logo is drawn over the button row, those
     * deferred tooltips can appear underneath the artwork. Disable the tooltips
     * for these three title-screen icons entirely.
     */
    private static void clearWidgetTooltip(AbstractWidget widget) {
        Class<?> current = widget.getClass();

        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals("setTooltip")
                        || method.getParameterCount() != 1) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    method.invoke(widget, new Object[] {null});
                    return;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try another matching method or the field fallback below.
                }
            }

            current = current.getSuperclass();
        }

        // Fallback for widgets whose tooltip is stored directly rather than
        // exposed through a setter.
        current = widget.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getName().toLowerCase(Locale.ROOT).contains("tooltip")) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    field.set(widget, null);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Continue clearing any other tooltip-related field.
                }
            }

            current = current.getSuperclass();
        }
    }

    /**
     * Resource packs or mods can provide the side buttons as compact-looking icons
     * backed by much wider widgets. Shrinking them to a square prevents the hidden
     * label area from extending under the logo button.
     */
    private static void shrinkWidgetToIcon(AbstractWidget widget) {
        int targetSize = Math.max(20, Math.min(widget.getHeight(), 24));

        setWidgetDimension(widget, "width", targetSize);
        setWidgetDimension(widget, "height", targetSize);
    }

    private static void setWidgetDimension(
            AbstractWidget widget,
            String fieldName,
            int value
    ) {
        Class<?> current = widget.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setInt(widget, value);
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (ReflectiveOperationException ignored) {
                return;
            }
        }
    }

    private static void placeSideButtons(
            Button playButton,
            List<AbstractWidget> sideButtons
    ) {
        if (sideButtons.isEmpty()) {
            return;
        }

        int columnWidth = sideButtons.stream()
                .mapToInt(AbstractWidget::getWidth)
                .max()
                .orElse(20);

        int columnHeight = sideButtons.stream()
                .mapToInt(AbstractWidget::getHeight)
                .sum()
                + SIDE_BUTTON_GAP * Math.max(0, sideButtons.size() - 1);

        int x = playButton.getX() - SIDE_COLUMN_GAP - columnWidth;
        int y = playButton.getY()
                + Math.max(0, (playButton.getHeight() - columnHeight) / 2);

        for (AbstractWidget widget : sideButtons) {
            widget.setX(x + (columnWidth - widget.getWidth()) / 2);
            widget.setY(y);
            y += widget.getHeight() + SIDE_BUTTON_GAP;
        }
    }

    private static void drawPlayLogo(
            GuiGraphicsExtractor graphics,
            Button playButton
    ) {
        int availableWidth = playButton.getWidth() - PLAY_BUTTON_PADDING * 2;
        int availableHeight = playButton.getHeight() - PLAY_BUTTON_PADDING * 2;

        // The texture is already the intended GUI size. Drawing it at 1:1 uses
        // the complete image instead of sampling only its top-left corner.
        if (availableWidth < PLAY_TEXTURE_WIDTH || availableHeight < PLAY_TEXTURE_HEIGHT) {
            return;
        }

        int drawX = playButton.getX()
                + (playButton.getWidth() - PLAY_TEXTURE_WIDTH) / 2;
        int drawY = playButton.getY()
                + (playButton.getHeight() - PLAY_TEXTURE_HEIGHT) / 2;

        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                PLAY_BUTECO_TEXTURE,
                drawX,
                drawY,
                0.0F,
                0.0F,
                PLAY_TEXTURE_WIDTH,
                PLAY_TEXTURE_HEIGHT,
                PLAY_TEXTURE_WIDTH,
                PLAY_TEXTURE_HEIGHT
        );
    }

    private static void drawSideButtonTooltip(
            Minecraft minecraft,
            GuiGraphicsExtractor graphics,
            Button playButton,
            List<AbstractWidget> sideButtons,
            int mouseX,
            int mouseY
    ) {
        for (int index = 0; index < sideButtons.size(); index++) {
            AbstractWidget widget = sideButtons.get(index);

            if (mouseX < widget.getX()
                    || mouseX >= widget.getX() + widget.getWidth()
                    || mouseY < widget.getY()
                    || mouseY >= widget.getY() + widget.getHeight()) {
                continue;
            }

            Component tooltip = switch (index) {
                case 0 -> Component.translatable("modmenu.title");
                case 1 -> Component.translatable("options.accessibility");
                case 2 -> Component.translatable("options.language");
                default -> Component.empty();
            };

            String tooltipText = tooltip.getString();
            if (tooltipText.isBlank()) {
                return;
            }

            int textWidth = minecraft.font.width(tooltip);
            int tooltipWidth = textWidth + 8;
            int tooltipHeight = 17;

            // Keep the tooltip inside the BUTECO button, immediately to the right
            // of the icon column. This intentionally places the text over the logo.
            int tooltipX = playButton.getX() + 6;
            int preferredY = widget.getY()
                    + (widget.getHeight() - tooltipHeight) / 2;
            int tooltipY = Math.max(
                    playButton.getY() + 3,
                    Math.min(
                            preferredY,
                            playButton.getY() + playButton.getHeight() - tooltipHeight - 3
                    )
            );

            int maxRight = playButton.getX() + playButton.getWidth() - 4;
            if (tooltipX + tooltipWidth > maxRight) {
                tooltipWidth = Math.max(12, maxRight - tooltipX);
            }

            // These calls happen after drawPlayLogo(), so the background and text
            // are submitted later and render above the image.
            graphics.fill(
                    tooltipX,
                    tooltipY,
                    tooltipX + tooltipWidth,
                    tooltipY + tooltipHeight,
                    0xE0100010
            );
            graphics.outline(
                    tooltipX,
                    tooltipY,
                    tooltipWidth,
                    tooltipHeight,
                    0xFF8A2BE2
            );
            graphics.text(
                    minecraft.font,
                    tooltip,
                    tooltipX + 4,
                    tooltipY + 4,
                    0xFFFFFFFF,
                    true
            );
            return;
        }
    }

    private static boolean isExistingPlayButton(AbstractWidget widget) {
        String visibleText = widget.getMessage().getString().trim();

        return visibleText.equalsIgnoreCase("PLAY")
                || visibleText.equalsIgnoreCase("Play BUTECO :D")
                || visibleText.equalsIgnoreCase("Play Buteco");
    }

    private static boolean isVanillaGameModeButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        String visibleText = message.getString();

        return message.equals(Component.translatable("menu.singleplayer"))
                || message.equals(Component.translatable("menu.multiplayer"))
                || isRealmsButton(widget)
                || visibleText.equalsIgnoreCase("Singleplayer")
                || visibleText.equalsIgnoreCase("Multiplayer");
    }

    private static boolean isRealmsButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        String visibleText = message.getString();

        return message.equals(Component.translatable("menu.online"))
                || message.equals(Component.translatable("menu.realms"))
                || visibleText.equalsIgnoreCase("Minecraft Realms");
    }

    private static boolean isOptionsButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        String visibleText = message.getString().trim().toLowerCase(Locale.ROOT);

        return message.equals(Component.translatable("menu.options"))
                || visibleText.equals("options...")
                || visibleText.equals("options…");
    }

    private static boolean isQuitButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        String visibleText = message.getString().trim().toLowerCase(Locale.ROOT);

        return message.equals(Component.translatable("menu.quit"))
                || visibleText.equals("quit game")
                || visibleText.equals("quit");
    }

    private static boolean isAccessibilityButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        String visibleText = message.getString().trim().toLowerCase(Locale.ROOT);
        String className = widget.getClass().getSimpleName().toLowerCase(Locale.ROOT);

        return message.equals(Component.translatable("options.accessibility"))
                || visibleText.contains("accessibility")
                || className.contains("accessibility");
    }

    private static boolean isLanguageButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        String visibleText = message.getString().trim().toLowerCase(Locale.ROOT);
        String className = widget.getClass().getSimpleName().toLowerCase(Locale.ROOT);

        return message.equals(Component.translatable("options.language"))
                || visibleText.contains("language")
                || className.contains("language");
    }

    private static boolean isModsButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        String visibleText = message.getString().trim().toLowerCase(Locale.ROOT);

        return message.equals(Component.translatable("modmenu.title"))
                || message.equals(Component.translatable("menu.mods"))
                || visibleText.equals("mods")
                || visibleText.startsWith("mods (");
    }

    /**
     * The Realms news and invitation indicators are separate widgets placed on
     * top of the Realms button. Removing only the main Realms button leaves those
     * icons behind, so remove every other widget whose vertical center occupies
     * that same row. The Mod Menu button is explicitly preserved.
     */
    private static boolean isRealmsRowCompanion(
            AbstractWidget widget,
            AbstractWidget realmsButton,
            AbstractWidget modsButton
    ) {
        if (realmsButton == null || widget == realmsButton || widget == modsButton) {
            return false;
        }

        int rowTop = realmsButton.getY();
        int rowBottom = rowTop + realmsButton.getHeight();
        int widgetCenterY = widget.getY() + widget.getHeight() / 2;

        return widgetCenterY >= rowTop && widgetCenterY < rowBottom;
    }

    /**
     * Removes late-added widgets that overlap the custom logo button while
     * leaving its separate side column and all bottom-row controls untouched.
     */
    private static void removeWidgetsOverlappingPlayRow(
            Screen titleScreen,
            Button playButton
    ) {
        int rowTop = playButton.getY();
        int rowBottom = rowTop + playButton.getHeight();
        int rowLeft = playButton.getX();
        int rowRight = rowLeft + playButton.getWidth();

        Screens.getWidgets(titleScreen).removeIf(widget -> {
            if (widget == playButton) {
                return false;
            }

            int widgetCenterY = widget.getY() + widget.getHeight() / 2;
            int widgetLeft = widget.getX();
            int widgetRight = widgetLeft + widget.getWidth();
            boolean sameRow = widgetCenterY >= rowTop && widgetCenterY < rowBottom;
            boolean overlapsHorizontally = widgetLeft < rowRight && widgetRight > rowLeft;

            return sameRow && overlapsHorizontally;
        });
    }

    private static void cleanRestrictedWidgets(Screen screen) {
        List<AbstractWidget> widgets = Screens.getWidgets(screen);
        widgets.removeIf(widget -> isFriendsWidget(widget)
                || isCreditsAndAttributionButton(widget));

        for (AbstractWidget widget : widgets) {
            if (isOnlineOptionsButton(widget)) {
                widget.active = false;
            }
        }
    }

    private static boolean isOnlineOptionsButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        String visibleText = message.getString().trim().toLowerCase(Locale.ROOT);

        return message.equals(Component.translatable("options.online"))
                || visibleText.equals("online...")
                || visibleText.equals("online…");
    }

    private static boolean isCreditsAndAttributionButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        String visibleText = message.getString().trim().toLowerCase(Locale.ROOT);

        return message.equals(Component.translatable("options.credits_and_attribution"))
                || visibleText.equals("credits & attribution...")
                || visibleText.equals("credits & attribution…")
                || visibleText.equals("credits and attribution...")
                || visibleText.equals("credits and attribution…");
    }

    private static boolean isFriendsWidget(AbstractWidget widget) {
        if (widget instanceof FriendsButton) {
            return true;
        }

        String className = widget.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String message = widget.getMessage().getString().trim().toLowerCase(Locale.ROOT);

        return className.contains("friendsbutton")
                || message.equals("friends")
                || message.startsWith("friends list")
                || message.startsWith("allow requests")
                || message.startsWith("in-game notification")
                || message.startsWith("visibility")
                || message.startsWith("xbox settings");
    }

    private static boolean isFriendsScreen(Screen screen) {
        String className = screen.getClass().getName().toLowerCase(Locale.ROOT);
        String title = screen.getTitle().getString().trim().toLowerCase(Locale.ROOT);

        return className.contains(".screens.friends.")
                || className.endsWith("friendsscreen")
                || className.endsWith("friendsoverlayscreen")
                || title.equals("friends")
                || title.equals("friends list");
    }

    private static void closeFriendsScreen(Minecraft minecraft, Screen screen) {
        minecraft.execute(() -> {
            if (minecraft.gui.screen() != screen) {
                return;
            }

            if (minecraft.level != null) {
                minecraft.gui.setScreen(null);
            } else {
                minecraft.gui.setScreen(new TitleScreen());
            }
        });
    }

    private record TitleLayout(
            Button playButton,
            List<AbstractWidget> sideButtons
    ) {
    }

    private static Path serverConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }

    private static void ensureServerConfigExists() {
        Path configFile = serverConfigPath();

        try {
            Files.createDirectories(configFile.getParent());

            if (Files.notExists(configFile)) {
                Files.writeString(
                        configFile,
                        DEFAULT_SERVER_ADDRESS + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW
                );
            }
        } catch (IOException exception) {
            System.err.println(
                    "[Buteco Play] Could not create " + configFile
                            + "; using " + DEFAULT_SERVER_ADDRESS
            );
            exception.printStackTrace();
        }
    }

    private static String loadServerAddress() {
        Path configFile = serverConfigPath();
        ensureServerConfigExists();

        try {
            for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                String address = line.strip();

                // Allow blank lines and comments, while keeping the common case
                // as simple as a one-line file containing only the server address.
                if (!address.isEmpty() && !address.startsWith("#")) {
                    return address;
                }
            }

            System.err.println(
                    "[Buteco Play] " + configFile
                            + " contains no server address; using "
                            + DEFAULT_SERVER_ADDRESS
            );
        } catch (IOException exception) {
            System.err.println(
                    "[Buteco Play] Could not read " + configFile
                            + "; using " + DEFAULT_SERVER_ADDRESS
            );
            exception.printStackTrace();
        }

        return DEFAULT_SERVER_ADDRESS;
    }

    /**
     * Minecraft 26.2 uses unobfuscated Mojang names. Reflection here keeps the
     * mod tolerant of minor ConnectScreen signature changes while targeting 26.2.
     */
    private static void connectToButeco(Minecraft minecraft, Screen parentScreen) {
        String serverAddressText = loadServerAddress();

        try {
            Class<?> serverAddressClass = Class.forName(
                    "net.minecraft.client.multiplayer.resolver.ServerAddress"
            );
            Object serverAddress = createServerAddress(serverAddressClass, serverAddressText);

            Class<?> serverDataClass = Class.forName(
                    "net.minecraft.client.multiplayer.ServerData"
            );
            Object serverData = createServerData(serverDataClass, serverAddressText);

            Class<?> connectScreenClass = Class.forName(
                    "net.minecraft.client.gui.screens.ConnectScreen"
            );
            Method connectMethod = findConnectMethod(
                    connectScreenClass,
                    serverAddressClass,
                    serverDataClass
            );

            Object[] arguments = buildConnectArguments(
                    connectMethod.getParameterTypes(),
                    minecraft,
                    parentScreen,
                    serverAddressClass,
                    serverAddress,
                    serverDataClass,
                    serverData
            );

            connectMethod.invoke(null, arguments);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.err.println("[Buteco Play] Could not connect to " + serverAddressText);
            exception.printStackTrace();
        }
    }

    private static Object createServerAddress(
            Class<?> serverAddressClass,
            String serverAddressText
    ) throws ReflectiveOperationException {
        for (String methodName : List.of("parseString", "parse")) {
            try {
                Method factory = serverAddressClass.getMethod(methodName, String.class);

                if (Modifier.isStatic(factory.getModifiers())
                        && serverAddressClass.isAssignableFrom(factory.getReturnType())) {
                    return factory.invoke(null, serverAddressText);
                }
            } catch (NoSuchMethodException ignored) {
                // Try the next known factory name.
            }
        }

        Constructor<?> constructor = serverAddressClass.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(serverAddressText);
    }

    private static Object createServerData(
            Class<?> serverDataClass,
            String serverAddressText
    ) throws ReflectiveOperationException {
        Constructor<?>[] constructors = serverDataClass.getDeclaredConstructors();
        Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));

        ReflectiveOperationException lastFailure = null;

        for (Constructor<?> constructor : constructors) {
            Object[] arguments = createConstructorArguments(constructor.getParameterTypes(), serverAddressText);

            try {
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            } catch (ReflectiveOperationException | IllegalArgumentException exception) {
                lastFailure = exception instanceof ReflectiveOperationException reflective
                        ? reflective
                        : new ReflectiveOperationException(exception);
            }
        }

        throw lastFailure != null
                ? lastFailure
                : new NoSuchMethodException("No usable ServerData constructor found");
    }

    private static Object[] createConstructorArguments(
            Class<?>[] parameterTypes,
            String serverAddressText
    ) {
        Object[] arguments = new Object[parameterTypes.length];
        int stringIndex = 0;

        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> parameterType = parameterTypes[index];

            if (parameterType == String.class) {
                arguments[index] = stringIndex++ == 0 ? SERVER_NAME : serverAddressText;
            } else if (parameterType.isEnum()) {
                arguments[index] = enumConstant(parameterType, "OTHER");
            } else if (parameterType == boolean.class || parameterType == Boolean.class) {
                arguments[index] = false;
            } else if (parameterType == int.class || parameterType == Integer.class) {
                arguments[index] = 0;
            } else if (parameterType == long.class || parameterType == Long.class) {
                arguments[index] = 0L;
            } else if (parameterType == float.class || parameterType == Float.class) {
                arguments[index] = 0.0F;
            } else if (parameterType == double.class || parameterType == Double.class) {
                arguments[index] = 0.0D;
            } else if (parameterType == Optional.class) {
                arguments[index] = Optional.empty();
            } else {
                arguments[index] = null;
            }
        }

        return arguments;
    }

    private static Object enumConstant(Class<?> enumType, String preferredName) {
        Object[] constants = enumType.getEnumConstants();

        for (Object constant : constants) {
            if (((Enum<?>) constant).name().equals(preferredName)) {
                return constant;
            }
        }

        return constants.length == 0 ? null : constants[0];
    }

    private static Method findConnectMethod(
            Class<?> connectScreenClass,
            Class<?> serverAddressClass,
            Class<?> serverDataClass
    ) throws NoSuchMethodException {
        return Arrays.stream(connectScreenClass.getMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getName().equals("startConnecting")
                        || method.getName().equals("connect"))
                .filter(method -> Arrays.stream(method.getParameterTypes())
                        .anyMatch(parameterType -> parameterType.isAssignableFrom(serverAddressClass)))
                .filter(method -> Arrays.stream(method.getParameterTypes())
                        .anyMatch(parameterType -> parameterType.isAssignableFrom(serverDataClass)))
                .min(Comparator.comparingInt(Method::getParameterCount))
                .orElseThrow(() -> new NoSuchMethodException(
                        "No compatible ConnectScreen connection method found"
                ));
    }

    private static Object[] buildConnectArguments(
            Class<?>[] parameterTypes,
            Minecraft minecraft,
            Screen parentScreen,
            Class<?> serverAddressClass,
            Object serverAddress,
            Class<?> serverDataClass,
            Object serverData
    ) {
        Object[] arguments = new Object[parameterTypes.length];

        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> parameterType = parameterTypes[index];

            if (parameterType.isInstance(parentScreen)) {
                arguments[index] = parentScreen;
            } else if (parameterType.isInstance(minecraft)) {
                arguments[index] = minecraft;
            } else if (parameterType.isAssignableFrom(serverAddressClass)) {
                arguments[index] = serverAddress;
            } else if (parameterType.isAssignableFrom(serverDataClass)) {
                arguments[index] = serverData;
            } else if (parameterType == boolean.class || parameterType == Boolean.class) {
                arguments[index] = false;
            } else if (parameterType == int.class || parameterType == Integer.class) {
                arguments[index] = 0;
            } else if (parameterType == long.class || parameterType == Long.class) {
                arguments[index] = 0L;
            } else {
                arguments[index] = null;
            }
        }

        return arguments;
    }
}
