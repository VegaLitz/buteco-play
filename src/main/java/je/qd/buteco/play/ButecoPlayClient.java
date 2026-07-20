package je.qd.buteco.play;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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

public final class ButecoPlayClient implements ClientModInitializer {
    private static final String SERVER_NAME = "Buteco";
    private static final String SERVER_ADDRESS = "buteco.qd.je";
    private static final Component PLAY_LABEL = Component.literal("PLAY");

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof TitleScreen) {
                replaceMainMenuButtons(minecraft, screen, scaledWidth, scaledHeight);
            }
        });
    }

    private static void replaceMainMenuButtons(
            Minecraft minecraft,
            Screen titleScreen,
            int scaledWidth,
            int scaledHeight
    ) {
        List<AbstractWidget> widgets = Screens.getWidgets(titleScreen);

        AbstractWidget modsButton = widgets.stream()
                .filter(ButecoPlayClient::isModsButton)
                .findFirst()
                .orElse(null);

        widgets.removeIf(widget -> isVanillaGameModeButton(widget) || PLAY_LABEL.equals(widget.getMessage()));

        int width = modsButton != null ? modsButton.getWidth() : 200;
        int height = modsButton != null ? modsButton.getHeight() : 20;
        int x = modsButton != null ? modsButton.getX() : (scaledWidth - width) / 2;
        int y = modsButton != null
                ? modsButton.getY() - height - 4
                : scaledHeight / 4 + 48;

        Button playButton = Button.builder(
                        PLAY_LABEL,
                        button -> connectToButeco(minecraft, titleScreen)
                )
                .bounds(x, y, width, height)
                .build();

        widgets.add(playButton);
    }

    private static boolean isVanillaGameModeButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        String visibleText = message.getString();

        return message.equals(Component.translatable("menu.singleplayer"))
                || message.equals(Component.translatable("menu.multiplayer"))
                || message.equals(Component.translatable("menu.online"))
                || message.equals(Component.translatable("menu.realms"))
                || visibleText.equalsIgnoreCase("Singleplayer")
                || visibleText.equalsIgnoreCase("Multiplayer")
                || visibleText.equalsIgnoreCase("Minecraft Realms");
    }

    private static boolean isModsButton(AbstractWidget widget) {
        Component message = widget.getMessage();
        String visibleText = message.getString();

        return message.equals(Component.translatable("modmenu.title"))
                || message.equals(Component.translatable("menu.mods"))
                || visibleText.equalsIgnoreCase("Mods");
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
