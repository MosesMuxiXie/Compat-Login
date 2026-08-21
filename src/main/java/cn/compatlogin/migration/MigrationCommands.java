package cn.compatlogin.migration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class MigrationCommands {
    private MigrationCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("account")
            .then(literal("migrate")
                .then(literal("confirm")
                    .then(argument("code", StringArgumentType.word())
                        .executes(MigrationCommands::confirm)))
                .then(argument("from", StringArgumentType.word())
                    .requires(MigrationManager::canBegin)
                    .then(argument("to", StringArgumentType.word())
                        .then(literal("begin")
                            .executes(MigrationCommands::begin))))));
    }

    private static int begin(CommandContext<CommandSourceStack> context) {
        return MigrationManager.begin(
            context.getSource(),
            StringArgumentType.getString(context, "from"),
            StringArgumentType.getString(context, "to")
        );
    }

    private static int confirm(CommandContext<CommandSourceStack> context) {
        return MigrationManager.confirm(
            context.getSource(),
            StringArgumentType.getString(context, "code")
        );
    }
}
