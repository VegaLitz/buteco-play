package je.qd.buteco.play;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class ButecoPlayClient implements ClientModInitializer {
    private static final String SERVER_NAME = "Buteco";
    private static final String SERVER_ADDRESS = "buteco.qd.je";
    private static final String PLAY_TEXT = "Play BUTECO :D";
    private static final int[] BUTECO_GRADIENT = {
            0xE9C7FF,
            0xDDA5FF,
            0xCF7BFF,
            0xB95CF6,
            0x9F3DE1,
            0x7E22CE
    };
    private static final Component PLAY_LABEL = createPlayLabel();

    /**
     * Wait a few extracted frames before changing the menu. Mod Menu can add its
     * title-screen button from its own callback, so editing immediately during
     * AFTER_INIT can run too early depending on mod initialization order.
     */
    private static final int MAX_EXTRACT_ATTEMPTS = 3;

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof TitleScreen)) {
                return;
            }

            int[] attempts = {0};
            Button[] playButton = {null};

            ScreenEvents.afterExtract(screen).register(
                    (extractedScreen, graphics, mouseX, mouseY, tickDelta) -> {
                        if (playButton[0] == null) {
                            attempts[0]++;
                            boolean force = attempts[0] >= MAX_EXTRACT_ATTEMPTS;

                            playButton[0] = replaceMainMenuButtons(
                                    minecraft,
                                    extractedScreen,
                                    scaledWidth,
                                    scaledHeight,
                                    force
                            );
                        }

                        if (playButton[0] != null) {
                            // Realms can add its news/invitation widgets after the main
                            // title-screen widgets. Keep cleaning only the PLAY row so
                            // those late icons cannot remain on top of the button.
                            removeWidgetsOverlappingPlayRow(extractedScreen, playButton[0]);
                        }
                    }
            );
        });
    }

    /**
     * @return the new Play button once the menu was changed, or {@code null}
     *         when it should wait one more extracted frame for Mod Menu.
     */
    private static Button replaceMainMenuButtons(
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

        // Remove the three vanilla play-mode buttons, an older PLAY button, and
        // every auxiliary widget occupying the Realms row (news/invite icons).
        AbstractWidget finalModsButton = modsButton;
        AbstractWidget finalRealmsButton = realmsButton;
        widgets.removeIf(widget -> isVanillaGameModeButton(widget)
                || isExistingPlayButton(widget)
                || isRealmsRowCompanion(widget, finalRealmsButton, finalModsButton));

        int width;
        int height;
        int x;
        int y;

        if (modsButton != null) {
            width = modsButton.getWidth();
            height = modsButton.getHeight();
            x = modsButton.getX();
            y = modsButton.getY() - height - 4;
        } else if (realmsButton != null) {
            // Fallback when Mod Menu is not installed or has its button disabled.
            width = realmsButton.getWidth();
            height = realmsButton.getHeight();
            x = realmsButton.getX();
            y = realmsButton.getY();
        } else {
            width = 300;
            height = 20;
            x = (scaledWidth - width) / 2;
            y = scaledHeight / 4 + 48;
        }

        Button playButton = Button.builder(
                        PLAY_LABEL,
                        button -> connectToButeco(minecraft, titleScreen)
                )
                .bounds(x, y, width, height)
                .build();

        widgets.add(playButton);
        removeWidgetsOverlappingPlayRow(titleScreen, playButton);
        return playButton;
    }

    private static Component createPlayLabel() {
        MutableComponent label = Component.literal("Play ");
        String buteco = "BUTECO";

        for (int index = 0; index < buteco.length(); index++) {
            int color = BUTECO_GRADIENT[index];
            label.append(
                    Component.literal(String.valueOf(buteco.charAt(index)))
                            .withStyle(style -> style.withColor(color))
            );
        }

        return label.append(Component.literal(" :D"));
    }

    private static boolean isExistingPlayButton(AbstractWidget widget) {
        String visibleText = widget.getMessage().getString().trim();
        return visibleText.equalsIgnoreCase("PLAY")
                || visibleText.equalsIgnoreCase(PLAY_TEXT);
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
     * Removes late-added Realms icons that overlap the new Play button while
     * leaving the Mods row and all bottom-row controls untouched.
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

    /**
     * Minecraft 26.1 switched to unobfuscated Mojang names. Reflection here keeps the
     * mod tolerant of minor ConnectScreen signature changes while still targeting 26.1.2.
     */
    private static void connectToButeco(Minecraft minecraft, Screen parentScreen) {
        try {
            Class<?> serverAddressClass = Class.forName(
                    "net.minecraft.client.multiplayer.resolver.ServerAddress"
            );
            Object serverAddress = createServerAddress(serverAddressClass);

            Class<?> serverDataClass = Class.forName(
                    "net.minecraft.client.multiplayer.ServerData"
            );
            Object serverData = createServerData(serverDataClass);

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
            System.err.println("[Buteco Play] Could not connect to " + SERVER_ADDRESS);
            exception.printStackTrace();
        }
    }

    private static Object createServerAddress(Class<?> serverAddressClass)
            throws ReflectiveOperationException {
        for (String methodName : List.of("parseString", "parse")) {
            try {
                Method factory = serverAddressClass.getMethod(methodName, String.class);

                if (Modifier.isStatic(factory.getModifiers())
                        && serverAddressClass.isAssignableFrom(factory.getReturnType())) {
                    return factory.invoke(null, SERVER_ADDRESS);
                }
            } catch (NoSuchMethodException ignored) {
                // Try the next known factory name.
            }
        }

        Constructor<?> constructor = serverAddressClass.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(SERVER_ADDRESS);
    }

    private static Object createServerData(Class<?> serverDataClass)
            throws ReflectiveOperationException {
        Constructor<?>[] constructors = serverDataClass.getDeclaredConstructors();
        Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));

        ReflectiveOperationException lastFailure = null;

        for (Constructor<?> constructor : constructors) {
            Object[] arguments = createConstructorArguments(constructor.getParameterTypes());

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

    private static Object[] createConstructorArguments(Class<?>[] parameterTypes) {
        Object[] arguments = new Object[parameterTypes.length];
        int stringIndex = 0;

        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> parameterType = parameterTypes[index];

            if (parameterType == String.class) {
                arguments[index] = stringIndex++ == 0 ? SERVER_NAME : SERVER_ADDRESS;
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
