package cn.compatlogin.migration;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Creates literal chat components across legacy, intermediary and unobfuscated APIs. */
public final class MinecraftTextBridge {
    private static final String COMPONENT_NAMED = "net.minecraft.network.chat.Component";
    private static final String LITERAL_METHOD_NAMED = "literal";
    private static final String COMPONENT_INTERMEDIARY = "net.minecraft.class_2561";
    private static final String MUTABLE_COMPONENT_INTERMEDIARY = "net.minecraft.class_5250";
    private static final String LEGACY_TEXT_COMPONENT_INTERMEDIARY = "net.minecraft.class_2585";
    private static final String LITERAL_METHOD_INTERMEDIARY = "method_43470";

    private MinecraftTextBridge() {
    }

    public static Component literal(String text) {
        MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
        try {
            return createNamed(text);
        } catch (ReflectiveOperationException | LinkageError namedFailure) {
            try {
                return createIntermediary(resolver, text);
            } catch (ReflectiveOperationException | LinkageError intermediaryFailure) {
                try {
                    return createLegacy(resolver, text);
                } catch (ReflectiveOperationException | LinkageError legacyFailure) {
                    legacyFailure.addSuppressed(namedFailure);
                    legacyFailure.addSuppressed(intermediaryFailure);
                    throw new IllegalStateException("Cannot create a Minecraft text component", unwrap(legacyFailure));
                }
            }
        }
    }

    private static Component createNamed(String text) throws ReflectiveOperationException {
        Method literal = load(COMPONENT_NAMED).getMethod(LITERAL_METHOD_NAMED, String.class);
        return (Component) literal.invoke(null, text);
    }

    private static Component createIntermediary(MappingResolver resolver, String text)
        throws ReflectiveOperationException {
        String componentName = resolver.mapClassName("intermediary", COMPONENT_INTERMEDIARY);
        String literalName = resolver.mapMethodName(
            "intermediary",
            COMPONENT_INTERMEDIARY,
            LITERAL_METHOD_INTERMEDIARY,
            "(Ljava/lang/String;)L" + MUTABLE_COMPONENT_INTERMEDIARY.replace('.', '/') + ";"
        );
        Class<?> componentClass = load(componentName);
        Method literal = componentClass.getMethod(literalName, String.class);
        return (Component) literal.invoke(null, text);
    }

    private static Component createLegacy(MappingResolver resolver, String text)
        throws ReflectiveOperationException {
        String textComponentName = resolver.mapClassName("intermediary", LEGACY_TEXT_COMPONENT_INTERMEDIARY);
        Constructor<?> constructor = load(textComponentName).getConstructor(String.class);
        return (Component) constructor.newInstance(text);
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return Class.forName(
            name,
            true,
            contextLoader == null ? MinecraftTextBridge.class.getClassLoader() : contextLoader
        );
    }

    private static Throwable unwrap(Throwable exception) {
        if (exception instanceof InvocationTargetException) {
            Throwable cause = ((InvocationTargetException) exception).getCause();
            if (cause != null) {
                return cause;
            }
        }
        return exception;
    }
}
