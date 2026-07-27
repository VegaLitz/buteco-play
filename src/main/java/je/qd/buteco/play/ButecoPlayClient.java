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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import je.qd.buteco.play.screen.ButecoOnlineOptionsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class ButecoPlayClient implements ClientModInitializer {
    private static final String SERVER_NAME = "Buteco";
    private static final String DEFAULT_SERVER_ADDRESS = "buteco.qd.je";
    private static final String CONFIG_FILE_NAME = "buteco.txt";
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
        // Create config/buteco.txt on first launch. The address is read again
        // whenever PLAY is pressed, so editing the file does not require a restart.
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

            // Minecraft 26.2 adds Friends buttons to the title screen and pause menu.
            // Remove them immediately and after every extracted frame, because some
            // screens and other mods can add their widgets after AFTER_INIT runs.
            removeFriendsWidgets(screen);
            ScreenEvents.afterExtract(screen).register(
                    (extractedScreen, graphics, mouseX, mouseY, tickDelta) ->
                            removeFriendsWidgets(extractedScreen)
            );

            // The O key can still try to open the Friends overlay. Close any Friends
            // UI immediately so the feature is inaccessible even without a button.
            if (isFriendsScreen(screen)) {
                closeFriendsScreen(minecraft, screen);
                return;
            }

            // Replace vanilla Online Options with a minimal screen that keeps only
            // the existing Realms "News & Invites" option. The entire Friends List
            // section is therefore absent rather than merely disabled.
            if (isVanillaOnlineOptionsScreen(screen)) {
                replaceOnlineOptionsScreen(minecraft, screen);
                return;
            }

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

        // Remove the three vanilla play-mode buttons, an older PLAY button,
        // the Friends button, and auxiliary widgets occupying the Realms row.
        AbstractWidget finalModsButton = modsButton;
        AbstractWidget finalRealmsButton = realmsButton;
        widgets.removeIf(widget -> isVanillaGameModeButton(widget)
                || isExistingPlayButton(widget)
                || isFriendsWidget(widget)
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

        int finalPurple = BUTECO_GRADIENT[BUTECO_GRADIENT.length - 1];
        return label.append(
                Component.literal(" :D")
                        .withStyle(style -> style.withColor(finalPurple))
        );
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

    private static void removeFriendsWidgets(Screen screen) {
        Screens.getWidgets(screen).removeIf(ButecoPlayClient::isFriendsWidget);
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

    private static boolean isVanillaOnlineOptionsScreen(Screen screen) {
        if (screen instanceof ButecoOnlineOptionsScreen) {
            return false;
        }

        String className = screen.getClass().getName();
        String title = screen.getTitle().getString().trim();

        return className.equals("net.minecraft.client.gui.screens.options.OnlineOptionsScreen")
                || title.equalsIgnoreCase("Online Options");
    }

    private static void replaceOnlineOptionsScreen(Minecraft minecraft, Screen vanillaScreen) {
        Screen parent = findParentScreen(vanillaScreen);
        AbstractWidget realmsOption = findRealmsOptionWidget(vanillaScreen);

        minecraft.execute(() -> {
            if (minecraft.gui.screen() == vanillaScreen) {
                minecraft.gui.setScreen(new ButecoOnlineOptionsScreen(parent, realmsOption));
            }
        });
    }

    private static Screen findParentScreen(Screen screen) {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Screen.class.isAssignableFrom(field.getType())) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = field.get(screen);

                    if (value instanceof Screen parent && parent != screen) {
                        return parent;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try the next Screen field.
                }
            }
        }

        return null;
    }

    /**
     * OnlineOptionsScreen stores its option controls inside nested layout/list
     * objects. Walk only the GUI object graph and reuse the original Realms
     * widget so its vanilla toggle and saved setting keep working unchanged.
     */
    private static AbstractWidget findRealmsOptionWidget(Screen screen) {
        ArrayDeque<ObjectDepth> queue = new ArrayDeque<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        queue.add(new ObjectDepth(screen, 0));

        while (!queue.isEmpty()) {
            ObjectDepth current = queue.removeFirst();
            Object value = current.value();

            if (value == null || !visited.add(value)) {
                continue;
            }

            if (value instanceof AbstractWidget widget && isRealmsNewsOption(widget)) {
                return widget;
            }

            if (current.depth() >= 6) {
                continue;
            }

            if (value instanceof Iterable<?> iterable) {
                for (Object child : iterable) {
                    queue.addLast(new ObjectDepth(child, current.depth() + 1));
                }
            }

            Class<?> valueClass = value.getClass();
            Package valuePackage = valueClass.getPackage();
            String packageName = valuePackage == null ? "" : valuePackage.getName();

            if (!packageName.startsWith("net.minecraft.client.gui")) {
                continue;
            }

            for (Class<?> type = valueClass; type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }

                    try {
                        field.setAccessible(true);
                        Object child = field.get(value);

                        if (child != null) {
                            queue.addLast(new ObjectDepth(child, current.depth() + 1));
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // Some implementation fields may not be reflectively accessible.
                    }
                }
            }
        }

        System.err.println("[Buteco Play] Could not locate the Realms News & Invites option.");
        return null;
    }

    private static boolean isRealmsNewsOption(AbstractWidget widget) {
        String message = widget.getMessage().getString().trim().toLowerCase(Locale.ROOT);
        return message.contains("news & invites")
                || message.contains("news and invites")
                || (message.contains("news") && message.contains("invites"));
    }

    private record ObjectDepth(Object value, int depth) {
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
