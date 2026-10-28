package pl.peterwolf.vote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class VoteDialogs {
    private VoteDialogs() {}

    private static JsonObject base(String type, String title) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:" + type);
        o.addProperty("title", trim(title, 140));
        o.addProperty("pause", false);
        o.addProperty("after_action", "close");
        return o;
    }

    static void openList(ServerPlayer p, JsonArray ballots) {
        if (ballots == null || ballots.isEmpty()) {
            p.sendSystemMessage(Component.literal("Brak aktywnych głosowań."));
            return;
        }
        JsonObject d = base("multi_action", "Głosowania i apele");
        d.addProperty("columns", 1);
        JsonArray actions = new JsonArray();
        int n = 0;
        for (var el : ballots) {
            if (n++ >= 20) break;
            JsonObject b = el.getAsJsonObject();
            String id = VoteApi.string(b, "id");
            if (!valid(id)) continue;
            String prefix = "petition".equals(VoteApi.string(b,"type")) ? "APEL: " : "GŁOSUJ: ";
            actions.add(button(prefix + trim(VoteApi.string(b,"title"), 100), command("ballot show " + id)));
        }
        if (actions.isEmpty()) return;
        d.add("actions", actions);
        show(p, d);
    }

    static void openBallot(ServerPlayer p, JsonObject b) {
        String id = VoteApi.string(b,"id");
        if (!valid(id)) return;
        String type = VoteApi.string(b, "type");
        JsonObject d = base("multi_action", VoteApi.string(b,"title"));
        d.addProperty("columns", 1);
        boolean publicNick = b.has("publishSignatures") && b.get("publishSignatures").getAsBoolean();
        // Keep room for the legally important privacy disclosure, even for long appeals.
        String text = trim(VoteApi.string(b,"body"), 2800);
        boolean officialAppeal = b.has("appealId") && b.get("appealId").isJsonPrimitive();
        int appealId = officialAppeal ? b.get("appealId").getAsInt() : 0;
        if ("petition".equals(type)) {
            text += officialAppeal
                ? "\n\nPełną treść apelu przeczytasz na mkal.pl/apel. Podpis trafi do apelu MKA. Imię i nazwisko: Twój pełny UUID. Miasto: MINECRAFT. E-mail nie jest wymagany. UUID będzie publicznie widoczny (Twój UUID: " + p.getUUID().toString() + "). Wymagana jest Twoja zgoda na przetwarzanie i publikację UUID."
                : (publicNick ? "\n\nPodpis spowoduje publikację nicku Minecraft na WWW."
                : "\n\nTwój nick nie zostanie opublikowany.");
        } else text += "\n\nJeden gracz może oddać tylko jeden głos.";
        JsonObject message = new JsonObject();
        message.addProperty("type", "minecraft:plain_message");
        message.addProperty("contents", trim(text, 3500));
        message.addProperty("width", 300);
        d.add("body", message);
        JsonArray buttons = new JsonArray();
        if ("petition".equals(type)) {
            buttons.add(button("Podpisz apel", confirm("Potwierdź podpis",
                officialAppeal
                    ? "Zgadzam się na publiczną publikację UUID (imienia i nazwiska) i miasta MINECRAFT, bez e-maila."
                    : (publicNick ? "Zgadzam się na publikację nicku." : "Czy podpisujesz ten apel?"),
                officialAppeal ? "ballot confirm " + VoteMod.issueConsentTicket(p, id, appealId) : "ballot sign " + id)));
        } else if ("vote".equals(type) && b.has("options") && b.get("options").isJsonArray()) {
            int n = 0;
            for (var option : b.getAsJsonArray("options")) {
                if (n++ >= 20) break;
                JsonObject item = option.getAsJsonObject();
                String key = VoteApi.string(item,"id");
                if (!valid(key)) continue;
                String label = trim(VoteApi.string(item,"label"), 120);
                buttons.add(button(label, confirm("Potwierdź głos", "Wybrana opcja: " + label,
                    "ballot vote " + id + " " + key)));
            }
        }
        if (buttons.isEmpty()) {
            p.sendSystemMessage(Component.literal("Brak opcji na karcie."));
            return;
        }
        d.add("actions", buttons);
        show(p, d);
    }

    private static JsonObject confirm(String title, String details, String cmd) {
        JsonObject d = base("confirmation", title);
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "minecraft:plain_message");
        msg.addProperty("contents", trim(details, 200));
        d.add("body", msg);
        d.add("yes", button("Potwierdzam", command(cmd)));
        d.add("no", button("Anuluj", null));
        JsonObject action = new JsonObject();
        action.addProperty("type", "show_dialog");
        action.add("dialog", d);
        return action;
    }

    private static JsonObject button(String label, JsonObject action) {
        JsonObject b = new JsonObject();
        b.addProperty("label", trim(label, 128));
        if (action != null) b.add("action", action);
        return b;
    }

    private static JsonObject command(String cmd) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "run_command");
        action.addProperty("command", cmd);
        return action;
    }

    private static boolean valid(String value) {
        return value.matches("[a-z0-9][a-z0-9_-]{0,63}");
    }

    private static String trim(String s, int limit) {
        return s.length() <= limit ? s : s.substring(0, limit-1) + "…";
    }

    private static void show(ServerPlayer p, JsonObject dialog) {
        MinecraftServer server = p.level().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
            "dialog show " + p.getGameProfile().getName() + " " + dialog.toString());
    }
}
