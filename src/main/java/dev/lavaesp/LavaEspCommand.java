package dev.lavaesp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

/**
 * Command surface. {@code /lavaesp} on its own opens the settings screen; everything the screen can
 * do is also reachable without leaving the chat.
 *
 * <pre>
 * /lavaesp                         open the settings screen (same one Mod Menu shows)
 * /lavaesp on | off                the module switch
 * /lavaesp color &lt;AARRGGBB&gt;        "Color"
 * /lavaesp fade &lt;true|false&gt;       "Fade"
 * /lavaesp fadestart &lt;10-100&gt;      "Fade start"
 * /lavaesp rescan                  drop the cache and scan again immediately
 * </pre>
 */
public final class LavaEspCommand {

    private static final String PREFIX = ChatFormatting.AQUA + "[LavaESP] " + ChatFormatting.GRAY;

    private LavaEspCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(LavaEspCommand::build);
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext ctx) {
        dispatcher.register(ClientCommands.literal("lavaesp")
                .executes(c -> openScreen(c.getSource()))
                .then(ClientCommands.literal("on")
                        .executes(c -> setEnabled(c.getSource(), true)))
                .then(ClientCommands.literal("off")
                        .executes(c -> setEnabled(c.getSource(), false)))
                .then(ClientCommands.literal("rescan")
                        .executes(c -> rescan(c.getSource())))
                .then(ClientCommands.literal("color")
                        .then(ClientCommands.argument("argb", StringArgumentType.greedyString())
                                .executes(c -> color(c.getSource(), StringArgumentType.getString(c, "argb")))))
                .then(ClientCommands.literal("fade")
                        .then(ClientCommands.argument("value", BoolArgumentType.bool())
                                .executes(c -> fade(c.getSource(), BoolArgumentType.getBool(c, "value")))))
                .then(ClientCommands.literal("fadestart")
                        .then(ClientCommands.argument("blocks",
                                        DoubleArgumentType.doubleArg(LavaEspConfig.FADE_START_MIN, LavaEspConfig.FADE_START_MAX))
                                .executes(c -> fadeStart(c.getSource(), DoubleArgumentType.getDouble(c, "blocks")))))
        );
    }

    // ------------------------------------------------------------------ actions

    private static int openScreen(FabricClientCommandSource source) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreenAndShow(new LavaEspScreen(null));
        return 1;
    }

    private static int setEnabled(FabricClientCommandSource source, boolean value) {
        LavaEspConfig.enabled = value;
        LavaEspConfig.save();
        if (value) {
            LavaEspMod.get().requestReport();
        } else {
            LavaEspMod.get().invalidate();
        }
        return info(source, "Lava ESP " + (value
                ? ChatFormatting.GREEN + "enabled" : ChatFormatting.RED + "disabled"));
    }

    private static int rescan(FabricClientCommandSource source) {
        LavaEspMod.get().invalidate();
        LavaEspMod.get().requestReport();
        return info(source, "rescanning");
    }

    private static int color(FabricClientCommandSource source, String raw) {
        Integer parsed = LavaEspConfig.tryParseColor(raw);
        if (parsed == null) {
            return error(source, "not a color: " + raw + " (expected AARRGGBB or RRGGBB)");
        }
        LavaEspConfig.color = parsed;
        LavaEspConfig.save();
        return info(source, String.format("Color = #%08X", LavaEspConfig.color));
    }

    private static int fade(FabricClientCommandSource source, boolean value) {
        LavaEspConfig.fade = value;
        LavaEspConfig.save();
        return info(source, "Fade = " + LavaEspConfig.fade);
    }

    private static int fadeStart(FabricClientCommandSource source, double blocks) {
        LavaEspConfig.fadeStart = LavaEspConfig.clamp(blocks);
        LavaEspConfig.save();
        return info(source, "Fade start = " + LavaEspConfig.fadeStart);
    }

    // ------------------------------------------------------------------ helpers

    private static int info(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(PREFIX + message));
        return 1;
    }

    private static int error(FabricClientCommandSource source, String message) {
        source.sendError(Component.literal(PREFIX + ChatFormatting.RED + message));
        return 0;
    }
}
