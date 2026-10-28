package pl.peterwolf.vote;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import java.util.concurrent.CompletionException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class VoteMod implements ModInitializer {
    private static VoteApi api;
    private static VoteStations stations;
    private record PendingConsent(String ballotId, int appealId, String token, long expiresAt) {}
    private static final Map<UUID, PendingConsent> pendingConsents = new HashMap<>();

    /** Issued only while showing the privacy disclosure; tied to one player and expires after 2 minutes. */
    static String issueConsentTicket(ServerPlayer player, String ballotId, int appealId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        pendingConsents.put(player.getUUID(),
            new PendingConsent(ballotId, appealId, token, System.currentTimeMillis() + 120_000));
        return token;
    }

    private static void confirm(ServerPlayer player, String token) {
        PendingConsent ticket = pendingConsents.remove(player.getUUID());
        if (ticket == null || ticket.expiresAt() < System.currentTimeMillis() || !ticket.token().equals(token)) {
            player.sendSystemMessage(Component.literal("Potwierdzenie wygasło. Otwórz apel ponownie."));
            return;
        }
        submit(player, ticket.ballotId(), null, true, ticket.appealId());
    }

    @Override public void onInitialize() {
        api = new VoteApi(VoteConfig.load());
        stations = new VoteStations();
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !(player instanceof ServerPlayer sp) || sp.isSpectator()
                    || !level.getBlockState(hit.getBlockPos()).is(Blocks.LECTERN)
                    || !stations.contains(level.dimension().identifier().toString(), hit.getBlockPos()))
                return InteractionResult.PASS;
            open(sp);
            return InteractionResult.SUCCESS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("ballot")
                .executes(ctx -> { open(ctx.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("open").executes(ctx -> {
                    open(ctx.getSource().getPlayerOrException()); return 1;
                }))
                .then(Commands.literal("show")
                    .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                        show(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx,"id")); return 1;
                    })))
                .then(Commands.literal("vote")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("option", StringArgumentType.word()).executes(ctx -> {
                            submit(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx,"id"), StringArgumentType.getString(ctx,"option"), false, null);
                            return 1;
                        }))))
                .then(Commands.literal("sign")
                    .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                        String id = StringArgumentType.getString(ctx,"id");
                        if ("mka-appeal".equals(id)) show(ctx.getSource().getPlayerOrException(), id);
                        else submit(ctx.getSource().getPlayerOrException(), id, null, true, null);
                        return 1;
                    })))
                .then(Commands.literal("confirm")
                    .then(Commands.argument("token", StringArgumentType.word()).executes(ctx -> {
                        confirm(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx,"token"));
                        return 1;
                    })))
                .then(Commands.literal("station")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("add").executes(ctx -> station(ctx.getSource().getPlayerOrException(), true)))
                    .then(Commands.literal("remove").executes(ctx -> station(ctx.getSource().getPlayerOrException(), false)))));
        });
    }

    private static int station(ServerPlayer player, boolean add) {
        if (!(player.pick(5.0, 1.0f, false) instanceof BlockHitResult hit)
                || !player.level().getBlockState(hit.getBlockPos()).is(Blocks.LECTERN)) {
            player.sendSystemMessage(Component.literal("Spójrz na Lectern w odległości do pięciu bloków."));
            return 0;
        }
        String dimension = player.level().dimension().identifier().toString();
        BlockPos pos = hit.getBlockPos();
        boolean changed = add ? stations.add(dimension, pos) : stations.remove(dimension, pos);
        player.sendSystemMessage(Component.literal(changed
            ? (add ? "Dodano pulpit do głosowania." : "Usunięto pulpit.")
            : (add ? "Pulpit jest już zarejestrowany." : "Brak takiego pulpitu.")));
        return changed ? 1 : 0;
    }

    private static void open(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        api.activeBallots().whenComplete((ballots, err) -> server.execute(() -> {
            if (player.isRemoved()) return;
            if (err != null) { failure(player, err); return; }
            VoteDialogs.openList(player, ballots);
        }));
    }

    private static void show(ServerPlayer player, String id) {
        if (!validId(id)) { player.sendSystemMessage(Component.literal("Nieprawidłowe ID.")); return; }
        MinecraftServer server = player.level().getServer();
        api.activeBallots().whenComplete((ballots, err) -> server.execute(() -> {
            if (player.isRemoved()) return;
            if (err != null) { failure(player, err); return; }
            for (var entry : ballots) {
                JsonObject ballot = entry.getAsJsonObject();
                if (id.equals(VoteApi.string(ballot, "id"))) {
                    VoteDialogs.openBallot(player, ballot);
                    return;
                }
            }
            player.sendSystemMessage(Component.literal("Głosowanie jest niedostępne lub zamknięte."));
        }));
    }

    private static boolean validId(String s) {
        return s != null && s.matches("[a-z0-9][a-z0-9_-]{0,63}");
    }

    private static void submit(ServerPlayer player, String ballotId, String optionId, boolean consent, Integer appealId) {
        if (!validId(ballotId) || (optionId != null && !validId(optionId))) {
            player.sendSystemMessage(Component.literal("Nieprawidłowe ID.")); return;
        }
        MinecraftServer server = player.level().getServer();
        api.submit(ballotId, optionId, player.getUUID().toString(), player.getGameProfile().getName(), consent, appealId)
            .whenComplete((response, err) -> server.execute(() -> {
                if (player.isRemoved()) return;
                if (err != null) failure(player, err);
                else player.sendSystemMessage(Component.literal(response).withStyle(ChatFormatting.AQUA));
            }));
    }

    private static void failure(ServerPlayer player, Throwable err) {
        player.sendSystemMessage(Component.literal("Serwis głosowania jest niedostępny.").withStyle(ChatFormatting.RED));
        System.err.println("[VoteInMinecraft] HTTP: " + (err instanceof CompletionException ? err.getCause() : err));
    }
}
