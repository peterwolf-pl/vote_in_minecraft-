package pl.peterwolf.vote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.BlockEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import java.util.concurrent.CompletionException;

/** Web API is contacted only by the trusted Minecraft server; no credentials on clients. */
public final class VoteMod implements ModInitializer {
    public static final String MOD_ID = "vote_in_minecraft";
    private static final Identifier ID = Identifier.fromNamespaceAndPath(MOD_ID, "ballot_lectern");
    private static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, ID);
    private static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, ID);
    public static final Block BALLOT_LECTERN = new BallotLecternBlock(
        BlockBehaviour.Properties.ofFullCopy(Blocks.LECTERN).setId(BLOCK_KEY));
    public static final Item BALLOT_ITEM = new BlockItem(BALLOT_LECTERN,
        new Item.Properties().useBlockDescriptionPrefix().setId(ITEM_KEY));
    private static VoteApi api;

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.BLOCK, BLOCK_KEY, BALLOT_LECTERN);
        Registry.register(BuiltInRegistries.ITEM, ITEM_KEY, BALLOT_ITEM);
        api = new VoteApi(VoteConfig.load());
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
            .register((tab, entries) -> entries.accept(BALLOT_ITEM));
        BlockEvents.USE_WITHOUT_ITEM.register((state, level, pos, player, hit) -> {
            if (!state.is(BALLOT_LECTERN)) return null;
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) open(serverPlayer);
            return InteractionResult.SUCCESS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
            Commands.literal("ballot")
                .executes(ctx -> { open(ctx.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("open").executes(ctx -> {
                    open(ctx.getSource().getPlayerOrException()); return 1;
                }))
                .then(Commands.literal("show").then(Commands.argument("id", StringArgumentType.word())
                    .executes(ctx -> {
                        show(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id"));
                        return 1;
                    })))
                .then(Commands.literal("vote").then(Commands.argument("id", StringArgumentType.word())
                    .then(Commands.argument("option", StringArgumentType.word()).executes(ctx -> {
                        submit(ctx.getSource().getPlayerOrException(),
                            StringArgumentType.getString(ctx, "id"),
                            StringArgumentType.getString(ctx, "option"), false);
                        return 1;
                    }))))
                .then(Commands.literal("sign").then(Commands.argument("id", StringArgumentType.word())
                    .executes(ctx -> {
                        submit(ctx.getSource().getPlayerOrException(),
                            StringArgumentType.getString(ctx, "id"), null, true);
                        return 1;
                    })))
        ));
    }
    private static Component button(String title, String command) {
        return Component.literal(title).withStyle(style ->
            style.withColor(ChatFormatting.GREEN).withBold(true).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command)));
    }
    private static void open(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("Ładowanie kart głosowania...")
            .withStyle(ChatFormatting.GRAY));
        MinecraftServer server = player.level().getServer();
        api.activeBallots().whenComplete((ballots, err) -> server.execute(() -> {
            if (player.isRemoved()) return;
            if (err != null) { failure(player, err); return; }
            player.sendSystemMessage(Component.literal("=== GŁOSOWANIA I APELE ===")
                .withStyle(ChatFormatting.GOLD));
            if (ballots.size() == 0) {
                player.sendSystemMessage(Component.literal("Nie ma aktywnych głosowań."));
                return;
            }
            for (var json : ballots) {
                JsonObject b = json.getAsJsonObject();
                player.sendSystemMessage(Component.literal("• " + VoteApi.string(b,"title") + "  ")
                    .append(button("[OTWÓRZ]","/ballot show " + VoteApi.string(b,"id"))));
            }
        }));
    }
    private static void show(ServerPlayer player, String id) {
        MinecraftServer server = player.level().getServer();
        api.activeBallots().whenComplete((ballots, err) -> server.execute(() -> {
            if (player.isRemoved()) return;
            if (err != null) { failure(player, err); return; }
            JsonObject b=find(ballots,id);
            if (b == null) {
                player.sendSystemMessage(Component.literal("Nie znaleziono aktywnej karty."));
                return;
            }
            player.sendSystemMessage(Component.literal("============================")
                .withStyle(ChatFormatting.GRAY));
            player.sendSystemMessage(Component.literal(VoteApi.string(b,"title"))
                .withStyle(ChatFormatting.GOLD,ChatFormatting.BOLD));
            for (String line : VoteApi.string(b,"body").split("\\R",-1))
                player.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(Component.literal("----------------------------")
                .withStyle(ChatFormatting.GRAY));
            if ("petition".equals(VoteApi.string(b,"type"))) {
                if (b.has("publishSignatures") && b.get("publishSignatures").getAsBoolean())
                    player.sendSystemMessage(Component.literal(
                        "UWAGA: Twój nick będzie publicznie widoczny na stronie WWW.")
                        .withStyle(ChatFormatting.YELLOW));
                player.sendSystemMessage(button("[PODPISZ APEL]","/ballot sign "+id));
            } else {
                for (var json : b.getAsJsonArray("options")) {
                    JsonObject o=json.getAsJsonObject();
                    player.sendSystemMessage(button("[GŁOSUJ] ",
                        "/ballot vote " + id + " " + VoteApi.string(o,"id"))
                        .append(Component.literal(VoteApi.string(o,"label"))));
                }
            }
        }));
    }
    private static JsonObject find(JsonArray ballots, String id) {
        for (var json : ballots) {
            JsonObject b=json.getAsJsonObject();
            if (id.equals(VoteApi.string(b,"id"))) return b;
        }
        return null;
    }
    private static void submit(ServerPlayer player, String ballotId, String optionId, boolean consent) {
        if (!ballotId.matches("[a-z0-9][a-z0-9_-]{0,63}") ||
            (optionId != null && !optionId.matches("[a-z0-9][a-z0-9_-]{0,63}"))) {
            player.sendSystemMessage(Component.literal("Nieprawidłowy identyfikator."));
            return;
        }
        MinecraftServer server=player.level().getServer();
        api.submit(ballotId,optionId,player.getUUID().toString(),player.getGameProfile().getName(),consent)
            .whenComplete((response,err) -> server.execute(() -> {
                if (player.isRemoved()) return;
                if (err != null) failure(player,err);
                else player.sendSystemMessage(Component.literal(response).withStyle(ChatFormatting.AQUA));
            }));
    }
    private static void failure(ServerPlayer player, Throwable error) {
        player.sendSystemMessage(Component.literal(
            "Brak połączenia z serwisem głosowania. Spróbuj później.")
            .withStyle(ChatFormatting.RED));
        System.err.println("[VoteInMinecraft] API error: "+
            (error instanceof CompletionException?error.getCause():error));
    }
}
