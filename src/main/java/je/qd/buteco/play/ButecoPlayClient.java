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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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
    private static final int BOTTOM_BUTTON_GAP = 4;

    // SkinShuffle draws its preview through a custom title-screen widget. Keep
    // the model slightly smaller and lower so it sits closer to Skin Presets.
    private static final double SKIN_PREVIEW_SCALE_FACTOR = 0.84D;
    private static final int SKIN_PREVIEW_DOWN_OFFSET = 7;

    private static final Map<Screen, SkinPreviewAdjustment> SKIN_PREVIEW_ADJUSTMENTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Object> TUNED_SKIN_PREVIEW_OBJECTS =
            Collections.newSetFromMap(new WeakHashMap<>());

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
                            // SkinShuffle can add Skin Presets after the vanilla title
                            // screen initializes. Re-centre the complete bottom row on
                            // every extracted frame, then keep the logo group aligned
                            // to those exact horizontal bounds.
                            BottomRowLayout bottomRow = centerBottomMenuButtons(
                                    extractedScreen,
                                    scaledWidth
                            );

                            if (bottomRow != null) {
                                positionPlayGroup(
                                        layout[0].playButton(),
                                        layout[0].sideButtons(),
                                        bottomRow
                                );
                            } else {
                                placeSideButtons(
                                        layout[0].playButton(),
                                        layout[0].sideButtons()
                                );
                            }

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
                                    mouseY,
                                    scaledWidth,
                                    scaledHeight
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

        BottomRowLayout bottomRow = centerBottomMenuButtons(titleScreen, scaledWidth);

        if (bottomRow == null) {
            int fallbackLeft;
            int fallbackRight;

            if (optionsButton != null && quitButton != null) {
                fallbackLeft = Math.min(optionsButton.getX(), quitButton.getX());
                fallbackRight = Math.max(
                        optionsButton.getX() + optionsButton.getWidth(),
                        quitButton.getX() + quitButton.getWidth()
                );
            } else {
                fallbackLeft = (scaledWidth - DEFAULT_BOTTOM_GROUP_WIDTH) / 2;
                fallbackRight = fallbackLeft + DEFAULT_BOTTOM_GROUP_WIDTH;
            }

            int fallbackY = optionsButton != null
                    ? optionsButton.getY()
                    : scaledHeight / 4 + 100;
            bottomRow = new BottomRowLayout(fallbackLeft, fallbackRight, fallbackY);
        }

        int columnWidth = sideButtons.stream()
                .mapToInt(AbstractWidget::getWidth)
                .max()
                .orElse(20);

        int columnHeight = sideButtons.stream()
                .mapToInt(AbstractWidget::getHeight)
                .sum()
                + SIDE_BUTTON_GAP * Math.max(0, sideButtons.size() - 1);

        int x = bottomRow.left() + columnWidth + SIDE_COLUMN_GAP;
        int width = Math.max(120, bottomRow.right() - x);
        int height = Math.max(20, columnHeight);
        int y = Math.max(20, bottomRow.y() - MENU_ROW_GAP - height);

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

    /**
     * Places SkinShuffle's Skin Presets, Options, and Quit Game on one centred
     * horizontal row. The original widget widths are preserved.
     */
    private static BottomRowLayout centerBottomMenuButtons(
            Screen titleScreen,
            int scaledWidth
    ) {
        List<AbstractWidget> widgets = Screens.getWidgets(titleScreen);

        AbstractWidget optionsButton = widgets.stream()
                .filter(ButecoPlayClient::isOptionsButton)
                .findFirst()
                .orElse(null);
        AbstractWidget quitButton = widgets.stream()
                .filter(ButecoPlayClient::isQuitButton)
                .findFirst()
                .orElse(null);
        AbstractWidget skinPresetsButton = widgets.stream()
                .filter(ButecoPlayClient::isSkinPresetsButton)
                .findFirst()
                .orElse(null);

        if (optionsButton == null || quitButton == null) {
            return null;
        }

        int oldSkinCentreX = skinPresetsButton == null
                ? 0
                : skinPresetsButton.getX() + skinPresetsButton.getWidth() / 2;

        // Requested order: Skin Presets, Options, Quit Game. Skin Presets sits
        // immediately to the left of Options while the complete row stays centred.
        List<AbstractWidget> bottomButtons = new ArrayList<>(3);
        addUnique(bottomButtons, skinPresetsButton);
        bottomButtons.add(optionsButton);
        bottomButtons.add(quitButton);

        int totalWidth = bottomButtons.stream()
                .mapToInt(AbstractWidget::getWidth)
                .sum()
                + BOTTOM_BUTTON_GAP * Math.max(0, bottomButtons.size() - 1);
        int left = Math.max(10, (scaledWidth - totalWidth) / 2);
        int y = optionsButton.getY();
        int x = left;

        for (AbstractWidget widget : bottomButtons) {
            widget.setX(x);
            widget.setY(y);
            x += widget.getWidth() + BOTTOM_BUTTON_GAP;
        }

        if (skinPresetsButton != null) {
            int newSkinCentreX = skinPresetsButton.getX()
                    + skinPresetsButton.getWidth() / 2;
            rememberSkinPreviewShift(
                    titleScreen,
                    newSkinCentreX - oldSkinCentreX
            );
            tuneSkinShufflePreview(titleScreen, skinPresetsButton);
        }

        // Skin Presets stays as the separate leftmost bottom button. The upper
        // group begins at Options so the Mods/Accessibility/Language column sits
        // directly above Options, while the BUTECO button ends with Quit Game.
        int playGroupLeft = optionsButton.getX();
        int playGroupRight = quitButton.getX() + quitButton.getWidth();
        return new BottomRowLayout(playGroupLeft, playGroupRight, y);
    }

    private static void rememberSkinPreviewShift(Screen screen, int deltaX) {
        SkinPreviewAdjustment current = SKIN_PREVIEW_ADJUSTMENTS.get(screen);

        if (current == null) {
            SKIN_PREVIEW_ADJUSTMENTS.put(
                    screen,
                    new SkinPreviewAdjustment(deltaX, SKIN_PREVIEW_DOWN_OFFSET)
            );
            return;
        }

        // The first non-zero movement is the shift from SkinShuffle's original
        // right-side position to the custom centred row. Keep it for preview
        // objects that SkinShuffle creates a frame or two later.
        if (current.deltaX() == 0 && deltaX != 0) {
            SKIN_PREVIEW_ADJUSTMENTS.put(
                    screen,
                    new SkinPreviewAdjustment(deltaX, current.deltaY())
            );
        }
    }

    /**
     * SkinShuffle has changed its internal preview widget names between releases,
     * so this compatibility layer intentionally avoids a hard dependency. It walks
     * the title screen's SkinShuffle-owned widget state, moves it with the Skin
     * Presets button, lowers it slightly, and reduces its model scale once.
     */
    private static void tuneSkinShufflePreview(
            Screen titleScreen,
            AbstractWidget skinPresetsButton
    ) {
        SkinPreviewAdjustment adjustment = SKIN_PREVIEW_ADJUSTMENTS.get(titleScreen);
        if (adjustment == null) {
            adjustment = new SkinPreviewAdjustment(0, SKIN_PREVIEW_DOWN_OFFSET);
        }

        Set<Object> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        collectSkinShuffleObjects(titleScreen, candidates, 0);
        collectSkinShuffleObjects(skinPresetsButton, candidates, 0);

        for (AbstractWidget widget : Screens.getWidgets(titleScreen)) {
            if (widget != skinPresetsButton && isSkinShuffleOwned(widget)) {
                candidates.add(widget);
                collectSkinShuffleObjects(widget, candidates, 0);
            }
        }

        for (Object candidate : candidates) {
            if (candidate == null
                    || candidate == skinPresetsButton
                    || !isSkinPreviewLike(candidate)) {
                continue;
            }

            if (TUNED_SKIN_PREVIEW_OBJECTS.add(candidate)) {
                tuneSkinPreviewObject(candidate, adjustment);
            }
        }
    }

    private static void collectSkinShuffleObjects(
            Object owner,
            Set<Object> results,
            int depth
    ) {
        if (owner == null || depth > 3) {
            return;
        }

        Class<?> current = owner.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getType().isPrimitive()
                        || field.getType().isEnum()
                        || field.getType() == String.class
                        || field.getType() == Component.class) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);

                    if (value == null || value == owner) {
                        continue;
                    }

                    if (isSkinShuffleOwned(value)) {
                        if (results.add(value)) {
                            collectSkinShuffleObjects(value, results, depth + 1);
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // A compatibility helper must never prevent the title screen
                    // from loading when SkinShuffle changes an internal field.
                }
            }
            current = current.getSuperclass();
        }
    }

    private static boolean isSkinShuffleOwned(Object object) {
        String className = object.getClass().getName().toLowerCase(Locale.ROOT);
        return className.contains("skinshuffle")
                || className.contains("skin_shuffle")
                || className.startsWith("dev.imb11.")
                || className.startsWith("com.mineblock11.");
    }

    private static boolean isSkinPreviewLike(Object object) {
        String className = object.getClass().getName().toLowerCase(Locale.ROOT);
        return isSkinShuffleOwned(object)
                && (className.contains("preview")
                || className.contains("widget")
                || className.contains("render")
                || className.contains("title")
                || className.contains("skinbutton")
                || className.contains("player"));
    }

    private static void tuneSkinPreviewObject(
            Object object,
            SkinPreviewAdjustment adjustment
    ) {
        Class<?> current = object.getClass();

        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                    continue;
                }

                String name = field.getName().toLowerCase(Locale.ROOT);

                try {
                    field.setAccessible(true);

                    if (isScaleField(name)) {
                        scaleNumericField(field, object, SKIN_PREVIEW_SCALE_FACTOR);
                    } else if (isPreviewXField(name)) {
                        offsetNumericField(field, object, adjustment.deltaX());
                    } else if (isPreviewYField(name)) {
                        offsetNumericField(field, object, adjustment.deltaY());
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Ignore incompatible fields; other matching fields can still
                    // provide the requested placement on this SkinShuffle version.
                }
            }
            current = current.getSuperclass();
        }
    }

    private static boolean isScaleField(String name) {
        return name.equals("scale")
                || name.contains("modelscale")
                || name.contains("playerscale")
                || name.contains("previewscale")
                || name.contains("renderscale")
                || name.contains("model_size")
                || name.contains("modelsize");
    }

    private static boolean isPreviewXField(String name) {
        return name.equals("x")
                || name.equals("centerx")
                || name.equals("centrex")
                || name.contains("previewx")
                || name.contains("renderx")
                || name.contains("modelx")
                || name.contains("playerx")
                || name.contains("widgetx");
    }

    private static boolean isPreviewYField(String name) {
        return name.equals("y")
                || name.contains("previewy")
                || name.contains("rendery")
                || name.contains("modely")
                || name.contains("playery")
                || name.contains("widgety")
                || name.contains("yoffset");
    }

    private static void scaleNumericField(
            Field field,
            Object owner,
            double factor
    ) throws IllegalAccessException {
        Class<?> type = field.getType();

        if (type == float.class) {
            field.setFloat(owner, (float) (field.getFloat(owner) * factor));
        } else if (type == double.class) {
            field.setDouble(owner, field.getDouble(owner) * factor);
        } else if (type == int.class) {
            int value = field.getInt(owner);
            if (value > 1) {
                field.setInt(owner, Math.max(1, (int) Math.round(value * factor)));
            }
        }
    }

    private static void offsetNumericField(
            Field field,
            Object owner,
            int offset
    ) throws IllegalAccessException {
        if (offset == 0) {
            return;
        }

        Class<?> type = field.getType();
        if (type == int.class) {
            field.setInt(owner, field.getInt(owner) + offset);
        } else if (type == float.class) {
            field.setFloat(owner, field.getFloat(owner) + offset);
        } else if (type == double.class) {
            field.setDouble(owner, field.getDouble(owner) + offset);
        }
    }

    /**
     * Keeps the compact icon column directly above Options and aligns the
     * BUTECO button's right edge with Quit Game. Skin Presets remains outside
     * this upper group as the separate leftmost bottom button.
     */
    private static void positionPlayGroup(
            Button playButton,
            List<AbstractWidget> sideButtons,
            BottomRowLayout bottomRow
    ) {
        int columnWidth = sideButtons.stream()
                .mapToInt(AbstractWidget::getWidth)
                .max()
                .orElse(20);
        int columnHeight = sideButtons.stream()
                .mapToInt(AbstractWidget::getHeight)
                .sum()
                + SIDE_BUTTON_GAP * Math.max(0, sideButtons.size() - 1);

        int x = bottomRow.left() + columnWidth + SIDE_COLUMN_GAP;
        int width = Math.max(120, bottomRow.right() - x);
        int height = Math.max(20, columnHeight);
        int y = Math.max(20, bottomRow.y() - MENU_ROW_GAP - height);

        playButton.setX(x);
        playButton.setY(y);
        setWidgetDimension(playButton, "width", width);
        setWidgetDimension(playButton, "height", height);
        placeSideButtons(playButton, sideButtons);
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
            int mouseY,
            int scaledWidth,
            int scaledHeight
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

            if (tooltip.getString().isBlank()) {
                return;
            }

            int textWidth = minecraft.font.width(tooltip);
            int tooltipWidth = textWidth + 10;
            int tooltipHeight = 17;

            // Place the tooltip close to the pointer, slightly below and to its
            // right. Flip it when necessary so it remains inside the screen.
            int tooltipX = mouseX + 7;
            int tooltipY = mouseY + 9;

            if (tooltipX + tooltipWidth > scaledWidth - 2) {
                tooltipX = mouseX - tooltipWidth - 7;
            }
            if (tooltipY + tooltipHeight > scaledHeight - 2) {
                tooltipY = mouseY - tooltipHeight - 7;
            }

            tooltipX = Math.max(2, Math.min(tooltipX, scaledWidth - tooltipWidth - 2));
            tooltipY = Math.max(2, Math.min(tooltipY, scaledHeight - tooltipHeight - 2));

            // Minecraft-style tooltip frame: a black outer edge, a white border
            // inset by one pixel, clipped corners, and a dark translucent centre.
            // This is submitted after the logo, so it always renders on top.
            graphics.fill(
                    tooltipX,
                    tooltipY,
                    tooltipX + tooltipWidth,
                    tooltipY + tooltipHeight,
                    0xFF000000
            );
            graphics.fill(
                    tooltipX + 1,
                    tooltipY + 1,
                    tooltipX + tooltipWidth - 1,
                    tooltipY + tooltipHeight - 1,
                    0xFFFFFFFF
            );
            graphics.fill(
                    tooltipX + 2,
                    tooltipY + 2,
                    tooltipX + tooltipWidth - 2,
                    tooltipY + tooltipHeight - 2,
                    0xE0100010
            );

            // Cut the four inner white corners by one pixel for the stepped,
            // block-like border used by the rest of the menu UI.
            graphics.fill(tooltipX + 1, tooltipY + 1, tooltipX + 2, tooltipY + 2, 0xFF000000);
            graphics.fill(
                    tooltipX + tooltipWidth - 2,
                    tooltipY + 1,
                    tooltipX + tooltipWidth - 1,
                    tooltipY + 2,
                    0xFF000000
            );
            graphics.fill(
                    tooltipX + 1,
                    tooltipY + tooltipHeight - 2,
                    tooltipX + 2,
                    tooltipY + tooltipHeight - 1,
                    0xFF000000
            );
            graphics.fill(
                    tooltipX + tooltipWidth - 2,
                    tooltipY + tooltipHeight - 2,
                    tooltipX + tooltipWidth - 1,
                    tooltipY + tooltipHeight - 1,
                    0xFF000000
            );

            graphics.text(
                    minecraft.font,
                    tooltip,
                    tooltipX + 5,
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

    private static boolean isSkinPresetsButton(AbstractWidget widget) {
        String visibleText = widget.getMessage().getString()
                .trim()
                .toLowerCase(Locale.ROOT);
        String className = widget.getClass().getName().toLowerCase(Locale.ROOT);

        return visibleText.equals("skin presets")
                || visibleText.equals("skin preset")
                || visibleText.contains("skin presets")
                || (className.contains("skin") && className.contains("preset"));
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

    private record BottomRowLayout(
            int left,
            int right,
            int y
    ) {
    }

    private record TitleLayout(
            Button playButton,
            List<AbstractWidget> sideButtons
    ) {
    }

    private record SkinPreviewAdjustment(int deltaX, int deltaY) {
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
