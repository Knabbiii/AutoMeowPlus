package org.knabbiii.automeow.client;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.Method;

import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.text.TextColor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.text.Style;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;


import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class AutomeowClient implements ClientModInitializer {
    // Tunables
    public static volatile int MY_MESSAGES_REQUIRED = 3;      // you must send 3 msgs between auto-replies
    public static volatile long QUIET_AFTER_SEND_MS = 3500;   // mute echoes after we send (and after you type meow)
    private static final int PASTEL_PINK = 0xFFC0CB; // soft pastel pink (#ffc0cb)
    public static final AtomicBoolean CHROMA_WANTED = new AtomicBoolean(false); // user toggle for chroma
    private static final int AARON_CHROMA_SENTINEL = 0xAA5500;
    public static final java.util.concurrent.atomic.AtomicBoolean PLAY_SOUND = new java.util.concurrent.atomic.AtomicBoolean(true);
    public static final java.util.concurrent.atomic.AtomicBoolean HEARTS_EFFECT = new java.util.concurrent.atomic.AtomicBoolean(true);

    // Meow tracking
    private static final AtomicInteger meowCount = new AtomicInteger(0);
    private static final int[] ACHIEVEMENT_MILESTONES = {10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000};
    private static Path MEOW_COUNT_PATH;

    // Modrinth
    private static final String MODRINTH_SLUG = "automeow+"; // your Modrinth project slug
    private static final int    UPDATE_HTTP_TIMEOUT_SEC = 6;

    // State
    public static final AtomicBoolean ENABLED = new AtomicBoolean(true);
    private static final AtomicInteger myMsgsSinceReply = new AtomicInteger(MY_MESSAGES_REQUIRED); // start "ready"
    private static final AtomicLong quietUntil = new AtomicLong(0);
    private static final AtomicBoolean skipNextOwnIncrement = new AtomicBoolean(false);
    private static volatile String lastUsedReply = ""; // track last reply to avoid repeats


    // Config state
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path CONFIG_PATH;

    private enum HpChannel { ALL, GUILD, PARTY, COOP }

    // Toggles

    private static class Config {
        boolean enabled = true;
        boolean chroma = false;
        boolean playSound = true;
        boolean heartsEffect = true;
        float baseVolume    = 0.8f;
        float basePitch     = 1.0f;
        float volumeJitter  = 0.15f;
        float pitchJitter   = 0.10f;
        int messagesRequired = 3;
        long quietAfterSendMs = 3500;
        java.util.List<String> customReplies = null;
        int meowCount = 0;
    }

    private static final java.util.regex.Pattern LEADING_WORD =
            java.util.regex.Pattern.compile("^\\s*([A-Za-z]+(?:[-\\p{Pd}][A-Za-z]+)?)");

    private static HpChannel detectHpChan(String raw) {
        if (raw == null) return HpChannel.ALL;
        String s = raw.replaceAll("§.", "");
        s = s.replaceAll("\\p{Pd}", "-");
        var m = LEADING_WORD.matcher(s);
        if (!m.find()) return HpChannel.ALL;
        String norm = m.group(1).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        switch (norm) {
            case "party":   return HpChannel.PARTY;
            case "guild":   return HpChannel.GUILD;
            case "coop":   return HpChannel.COOP;
            default:        return HpChannel.ALL;
        }
    }

    private static Config CONFIG = new Config();

    // Compare dotted numbers like "1.9.2" vs "1.10"
    private static int compareVersion(String a, String b) {
        String[] aa = a.split("\\D+"); // check non digits
        String[] bb = b.split("\\D+");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            int x = i < aa.length ? parseOrZero(aa[i]) : 0;
            int y = i < bb.length ? parseOrZero(bb[i]) : 0;
            if (x != y) return Integer.compare(x, y); // >0 if a>b | <0 if a<b
        }
        return 0;
    }
    private static int parseOrZero(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String currentModVersion() {
        return FabricLoader.getInstance()
                .getModContainer("automeow")              // your mod id from fabric.mod.json
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0");
    }

    private static float clampf(float v, float min, float max) { // I HATE MATH MY FRIEND HELPED ME WITH THE MATH :C
        return Math.max(min, Math.min(max, v));
    }
    private static float jitterAround(float base, float jitterFraction, net.minecraft.util.math.random.Random r) {
        float j = (r.nextFloat() * 2f - 1f) * jitterFraction;
        return base * (1f + j);
    }

    private static boolean arrContainsString(JsonObject obj, String field, String wanted) {
        if (!obj.has(field) || !obj.get(field).isJsonArray()) return false;
        for (var el : obj.getAsJsonArray(field)) {
            if (el.isJsonPrimitive() && wanted.equalsIgnoreCase(el.getAsString())) return true;
        }
        return false;
    }

    private static void checkForUpdateAsync() {
        final String currentModVer = currentModVersion();
        final String currentMcVer = MinecraftClient.getInstance().getGameVersion();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(UPDATE_HTTP_TIMEOUT_SEC))
                .build();

        // “All versions” endpoint, we’ll pick the newest stable one.
        String url = "https://api.modrinth.com/v2/project/" + MODRINTH_SLUG + "/version";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "AutoMeow/" + currentModVer + " (MC " + currentMcVer + "; Modrinth update check)")
                .timeout(java.time.Duration.ofSeconds(UPDATE_HTTP_TIMEOUT_SEC))
                .build();

        client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .exceptionally(ex -> null)
                .thenAccept(body -> {
                    if (body == null) return;
                    try {
                        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
                        String bestVer = null;
                        String bestUrl = null;
                        java.time.Instant bestDate = java.time.Instant.EPOCH;

                        for (int i = 0; i < arr.size(); i++) {
                            JsonObject v = arr.get(i).getAsJsonObject();
                            // Prefer stable releases
                            String type = v.has("version_type") ? v.get("version_type").getAsString() : "release";
                            if (!"release".equalsIgnoreCase(type)) continue;

                            if (!arrContainsString(v, "game_versions", currentMcVer)) continue;

                            String ver = v.get("version_number").getAsString();
                            java.time.Instant published = java.time.Instant.parse(v.get("date_published").getAsString());

                            if (bestVer == null || published.isAfter(bestDate)) {
                                bestVer = ver;
                                bestDate = published;
                                bestUrl = "https://modrinth.com/mod/" + MODRINTH_SLUG + "/version/" + ver;
                            }
                        }
                        if (bestVer == null) return;
                        if (compareVersion(bestVer, currentModVer) > 0) {
                            final String latest = bestVer;
                            final String dlUrl  = bestUrl;

                            // Clientside hyperlink
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc != null) {
                                Runnable  showUpdate = () -> mc.execute(() -> {
                                    var link = Text.literal("Download update")
                                            .setStyle(
                                                    Style.EMPTY
                                                            .withColor(Formatting.BLUE)
                                                            .withUnderline(true)
                                                            .withClickEvent(new ClickEvent.OpenUrl(URI.create(dlUrl)))           // ← open URL
                                                            .withHoverEvent(new HoverEvent.ShowText(Text.literal(dlUrl))) // ← tooltip
                                            );

                                    var msg = badge()
                                            .append(Text.literal(" Update available ").formatted(Formatting.YELLOW))
                                            .append(Text.literal("(MC " + currentMcVer + ", v" + currentModVer + " → v" + latest + ") ").formatted(Formatting.GRAY))
                                            .append(link);

                                    mc.inGameHud.getChatHud().addMessage(msg); // local only
                                });
                                java.util.concurrent.CompletableFuture
                                        .delayedExecutor(15, java.util.concurrent.TimeUnit.SECONDS)
                                        .execute(showUpdate);
                            }
                        }
                    } catch (Throwable ignored) { /* swallow quietly */ }
                });
    }



    // Load on startup
    private static void loadConfig() {
        try {
            Path dir = MinecraftClient.getInstance().runDirectory.toPath().resolve("config");
            Files.createDirectories(dir);
            CONFIG_PATH = dir.resolve("automeow.json");

            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH);
                Config loaded = GSON.fromJson(json, Config.class);
                if (loaded != null) CONFIG = loaded;
            } else {
                saveConfig(); // write defaults
            }

            ENABLED.set(CONFIG.enabled);
            CHROMA_WANTED.set(CONFIG.chroma);
            PLAY_SOUND.set(CONFIG.playSound);
            HEARTS_EFFECT.set(CONFIG.heartsEffect);
            MY_MESSAGES_REQUIRED = Math.max(0, CONFIG.messagesRequired);
            QUIET_AFTER_SEND_MS = Math.max(0, CONFIG.quietAfterSendMs);
            meowCount.set(Math.max(0, CONFIG.meowCount));

            // Load custom replies if available
            if (CONFIG.customReplies != null && !CONFIG.customReplies.isEmpty()) {
                REPLY_VARIATIONS = new java.util.ArrayList<>(CONFIG.customReplies);
            }

        } catch (Exception ignored) {
        }
    }

    public static void saveConfig() {
        try {
            if (CONFIG_PATH == null) {
                Path dir = MinecraftClient.getInstance().runDirectory.toPath().resolve("config");
                Files.createDirectories(dir);
                CONFIG_PATH = dir.resolve("automeow.json");
            }
            CONFIG.enabled = ENABLED.get();
            CONFIG.chroma = CHROMA_WANTED.get();
            CONFIG.playSound = PLAY_SOUND.get();
            CONFIG.heartsEffect = HEARTS_EFFECT.get();
            CONFIG.messagesRequired = MY_MESSAGES_REQUIRED;
            CONFIG.quietAfterSendMs = QUIET_AFTER_SEND_MS;
            CONFIG.customReplies = new java.util.ArrayList<>(REPLY_VARIATIONS);
            CONFIG.meowCount = meowCount.get();
            Files.writeString(
                    CONFIG_PATH, GSON.toJson(CONFIG),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception ignored) {
        }
    }

    private static ClientWorld lastWorld = null;

    // Match whole word "meow" (not case-sensitive)
    private static final Pattern MEOW = Pattern.compile("\\bmeow\\b", Pattern.CASE_INSENSITIVE);

    // can see meows in chats other than all chat hopefully
    private static final Pattern ROUNDED_CHAT_CMD =
            Pattern.compile("^(?:/?)(?:pc|partychat|gc|guildchat|cc|coopchat)\\s+(.+)$",
                    Pattern.CASE_INSENSITIVE);

    private static boolean hasAaronMod() {
        FabricLoader fl = FabricLoader.getInstance();
        return fl.isModLoaded("aaron-mod") || fl.isModLoaded("azureaaron"); // cover both ids
    }


    public static boolean aaronChromaAvailable() {
        if (!hasAaronMod()) return false;
        try {
            Class<?> c = Class.forName("net.azureaaron.mod.features.ChromaText");
            Method m = c.getMethod("chromaColourAvailable");
            Object res = m.invoke(null);
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // [AutoMeow] Prefix
    private static MutableText badge() {
        boolean chroma = CHROMA_WANTED.get() && aaronChromaAvailable();

        MutableText name = Text.literal("AutoMeow")
                .styled(s -> s.withBold(false)
                        // Aaron-mods chroma shader, if not installed then default to pastel pink
                        .withColor(TextColor.fromRgb(chroma ? AARON_CHROMA_SENTINEL : PASTEL_PINK)));

        return Text.literal("[").formatted(Formatting.GRAY)
                .append(name)
                .append(Text.literal("]").formatted(Formatting.GRAY))
                .append(Text.literal(" "));
    }

    private static MutableText statusLine(boolean enabled, int have, int need) {
        MutableText state = Text.literal(enabled ? "ON" : "OFF")
                .formatted(enabled ? Formatting.GREEN : Formatting.RED);
        return badge().append(state);
    }

    // Plays a cat meow sound and spawns heart particles around the given player (client-side only).
    private static void triggerCatCueAt(PlayerEntity target) {
        final MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || target == null) return;

        if (PLAY_SOUND.get()) {
            var r = mc.world.getRandom();
            float vol = clampf(jitterAround(CONFIG.baseVolume, CONFIG.volumeJitter, r), 0.0f, 2.0f);
            float pitch = clampf(jitterAround(CONFIG.baseVolume, CONFIG.pitchJitter, r), 0.5f, 2.0f);
            mc.world.playSound(target, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.ENTITY_CAT_AMBIENT, SoundCategory.PLAYERS, vol, pitch);
        }

        if (HEARTS_EFFECT.get()) {
            var r = mc.world.getRandom();
            for (int i = 0; i < 6; i++) {
                double dx = (r.nextDouble() - 0.5) * 0.6;
                double dz = (r.nextDouble() - 0.5) * 0.6;
                double dy = 1.6 + r.nextDouble() * 0.4;

                mc.particleManager.addParticle(
                        ParticleTypes.HEART,
                        target.getX() + dx, target.getY() + dy, target.getZ() + dz,
                        0.0, 0.02, 0.0
                );
            }
        }
    }

    // Increment meow count and check for achievement milestones
    private static void bumpMeowCount() {
        int count = meowCount.incrementAndGet();
        saveConfig();
        
        // Check if we hit an achievement milestone
        for (int milestone : ACHIEVEMENT_MILESTONES) {
            if (count == milestone) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        mc.inGameHud.getChatHud().addMessage(
                            badge()
                                .append(Text.literal("You have meowed ").formatted(Formatting.WHITE))
                                .append(Text.literal(String.valueOf(count)).formatted(Formatting.AQUA))
                                .append(Text.literal(" times :3").formatted(Formatting.WHITE))
                        );
                    });
                }
                break;
            }
        }
    }

    // Try to resolve who sent this chat line.
    // 1) If the event gives us a sender, use it.
    // 2) Otherwise, find any world player whose name appears in the raw chat text,
    // prefer the nearest one to reduce false positives.
    private static PlayerEntity resolveSender(MinecraftClient mc, GameProfile sender, String raw) {
        if (mc.world == null) return null;

        if (sender != null) {
            PlayerEntity p = mc.world.getPlayerByUuid(sender.id());
            if (p != null) return p;
        }
        if (raw == null || raw.isEmpty()) return null;

        String line = raw.replaceAll("§.", "").toLowerCase(java.util.Locale.ROOT);
        PlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (PlayerEntity p : mc.world.getPlayers()) {
            String name = p.getName().getString();
            if (name == null) continue;

            if (line.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                double d = (mc.player != null) ? p.squaredDistanceTo(mc.player) : 0.0;
                if (best == null || d < bestDist) {
                    best = p;
                    bestDist = d;
                }
            }
        }
        return best;
    }


    // Allow replies that contain "mer" anywhere (case-insensitive), for detecting cat sounds
    private static final Pattern CAT_SOUND = Pattern.compile(
            "(m+e+o+w|mer|m+r+r+p+|m+r+o+w+|ny+a+~*)",
            Pattern.CASE_INSENSITIVE
    );

    // Default cute reply variations
    private static final String[] DEFAULT_VARIATIONS = {
        "mroww",
        "purr",
        "meowwwwww",
        "meow :3",
        "mrow",
        "mrow :3",
        "purrr :3",
        "nya :3",
        "mraow",
        "mreow",
        "mew mew",
        "meowmeow",
        "^._.^"
    };

    // Active reply variations (can be modified at runtime)
    private static volatile java.util.List<String> REPLY_VARIATIONS = new java.util.ArrayList<>(java.util.Arrays.asList(DEFAULT_VARIATIONS));

    // Get a random reply that's different from the last one
    private static String getRandomReply() {
        if (REPLY_VARIATIONS.isEmpty()) return "meow";
        if (REPLY_VARIATIONS.size() == 1) return REPLY_VARIATIONS.get(0);
        
        java.util.Random rand = new java.util.Random();
        String chosen;
        do {
            chosen = REPLY_VARIATIONS.get(rand.nextInt(REPLY_VARIATIONS.size()));
        } while (chosen.equals(lastUsedReply) && REPLY_VARIATIONS.size() > 1);
        
        lastUsedReply = chosen;
        return chosen;
    }

    @Override
    public void onInitializeClient() {
        loadConfig();
        checkForUpdateAsync();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveConfig());
        registerEventHandlers();
        registerCommands();
    }

    private static void registerEventHandlers() {
        // Count outgoing messages YOU type; start a quiet window if you typed "meow"
        ClientSendMessageEvents.CHAT.register(msg -> {
            if (msg == null) return;
            if (MEOW.matcher(msg).find()) {
                long now = System.currentTimeMillis();
                quietUntil.set(now + QUIET_AFTER_SEND_MS);
                myMsgsSinceReply.set(0); // after meow, require 3 of OWN msgs before next autoreply
            }
            if (!skipNextOwnIncrement.getAndSet(false)) {
                myMsgsSinceReply.incrementAndGet();
            }
            // If you typed a cat-sound, play local cue for yourself (independent of auto-reply logic)
            if (CAT_SOUND.matcher(msg).find()) {
                MinecraftClient mcc = MinecraftClient.getInstance();
                if (mcc.player != null) {
                    mcc.execute(() -> triggerCatCueAt(mcc.player));
                }
            }
        });
        ClientSendMessageEvents.COMMAND.register(cmd -> {
            if (cmd == null) return;

            String raw = cmd.startsWith("/") ? cmd.substring(1) : cmd;
            var m = ROUNDED_CHAT_CMD.matcher(raw);
            if (!m.find()) return;

            String payload = m.group(1).trim();
            if (payload.isEmpty()) return;

            if (MEOW.matcher(payload).find()) {
                long now = System.currentTimeMillis();
                quietUntil.set(now + QUIET_AFTER_SEND_MS);
                myMsgsSinceReply.set(0);
                }
            if (!skipNextOwnIncrement.getAndSet(false)) {
                myMsgsSinceReply.incrementAndGet();
            }

            if (CAT_SOUND.matcher(payload).find()) {
                MinecraftClient mcc = MinecraftClient.getInstance();
                if (mcc.player != null) {
                    mcc.execute(() -> triggerCatCueAt(mcc.player));
                }
            }
        });

        // React to incoming chat
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, ts) -> handleIncoming(message, sender)
        );
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> handleIncoming(message, null) // GAME/system has no sender
        );

        // Reset counter on lobby/world change
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != lastWorld) {
                lastWorld = client.world;
                myMsgsSinceReply.set(MY_MESSAGES_REQUIRED); // first meow in new lobby replies instantly
            }
        });
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> 
            dispatcher.register(buildAutomeowCommand())
        );
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildAutomeowCommand() {
        return literal("automeow")
                .executes(ctx -> {
                    boolean on = ENABLED.get();
                    ctx.getSource().sendFeedback(statusLine(on, myMsgsSinceReply.get(), MY_MESSAGES_REQUIRED));
                    return on ? 1 : 0;
                })
                .then(buildToggleCommands())
                .then(buildOnCommand())
                .then(buildOffCommand())
                .then(buildChromaCommand())
                .then(buildHeartsCommand())
                .then(buildSoundCommand())
                .then(buildMessagesCommand())
                .then(buildCooldownCommand())
                .then(buildAddReplyCommand())
                .then(buildRemoveReplyCommand())
                .then(buildListRepliesCommand())
                .then(buildResetRepliesCommand())
                .then(buildStatsCommands());
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildToggleCommands() {
        return literal("toggle").executes(ctx -> {
            boolean newValue = !ENABLED.get();
            ENABLED.set(newValue);
            saveConfig();
            ctx.getSource().sendFeedback(statusLine(newValue, myMsgsSinceReply.get(), MY_MESSAGES_REQUIRED));
            return newValue ? 1 : 0;
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildOnCommand() {
        return literal("on").executes(ctx -> {
            ENABLED.set(true);
            saveConfig();
            ctx.getSource().sendFeedback(statusLine(true, myMsgsSinceReply.get(), MY_MESSAGES_REQUIRED));
            return 1;
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildOffCommand() {
        return literal("off").executes(ctx -> {
            ENABLED.set(false);
            saveConfig();
            ctx.getSource().sendFeedback(statusLine(false, myMsgsSinceReply.get(), MY_MESSAGES_REQUIRED));
            return 1;
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildChromaCommand() {
        return literal("chroma").executes(ctx -> {
            if (!hasAaronMod()) {
                ctx.getSource().sendFeedback(badge()
                        .append(Text.literal("Aaron-mod not found").formatted(Formatting.RED)));
                return 0;
            }
            if (!aaronChromaAvailable()) {
                ctx.getSource().sendFeedback(badge()
                        .append(Text.literal("Chroma pack is disabled").formatted(Formatting.YELLOW)));
                return 0;
            }
            boolean newValue = !CHROMA_WANTED.get();
            CHROMA_WANTED.set(newValue);
            saveConfig();
            ctx.getSource().sendFeedback(badge()
                    .append(Text.literal("Chroma " + (newValue ? "ON" : "OFF"))
                            .formatted(newValue ? Formatting.GREEN : Formatting.RED)));
            return newValue ? 1 : 0;
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildHeartsCommand() {
        return literal("hearts")
                .executes(ctx -> {
                    boolean newValue = !HEARTS_EFFECT.get();
                    HEARTS_EFFECT.set(newValue);
                    saveConfig();
                    ctx.getSource().sendFeedback(
                            badge().append(Text.literal("Hearts " + (newValue ? "ON" : "OFF"))
                                    .formatted(newValue ? Formatting.GREEN : Formatting.RED)));
                    return newValue ? 1 : 0;
                })
                .then(literal("on").executes(ctx -> {
                    HEARTS_EFFECT.set(true);
                    saveConfig();
                    ctx.getSource().sendFeedback(badge().append(Text.literal("Hearts ON").formatted(Formatting.GREEN)));
                    return 1;
                }))
                .then(literal("off").executes(ctx -> {
                    HEARTS_EFFECT.set(false);
                    saveConfig();
                    ctx.getSource().sendFeedback(badge().append(Text.literal("Hearts OFF").formatted(Formatting.RED)));
                    return 1;
                }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildSoundCommand() {
        return literal("sound")
                .executes(ctx -> {
                    boolean newValue = !PLAY_SOUND.get();
                    PLAY_SOUND.set(newValue);
                    saveConfig();
                    ctx.getSource().sendFeedback(
                            badge().append(Text.literal("Cat Sound " + (newValue ? "ON" : "OFF"))
                                    .formatted(newValue ? Formatting.GREEN : Formatting.RED)));
                    return newValue ? 1 : 0;
                })
                .then(literal("on").executes(ctx -> {
                    PLAY_SOUND.set(true);
                    saveConfig();
                    ctx.getSource().sendFeedback(badge().append(Text.literal("Cat Sound ON").formatted(Formatting.GREEN)));
                    return 1;
                }))
                .then(literal("off").executes(ctx -> {
                    PLAY_SOUND.set(false);
                    saveConfig();
                    ctx.getSource().sendFeedback(badge().append(Text.literal("Cat Sound OFF").formatted(Formatting.RED)));
                    return 1;
                }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildMessagesCommand() {
        return literal("messages")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(
                            badge().append(Text.literal("Message requirement: " + MY_MESSAGES_REQUIRED + " messages").formatted(Formatting.GRAY))
                    );
                    return MY_MESSAGES_REQUIRED;
                })
                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                        .argument("amount", IntegerArgumentType.integer(0, 10))
                        .executes(ctx -> {
                            int newValue = IntegerArgumentType.getInteger(ctx, "amount");
                            MY_MESSAGES_REQUIRED = newValue;
                            myMsgsSinceReply.set(newValue);
                            saveConfig();
                            String msg = newValue == 0 
                                ? "Message requirement disabled"
                                : "Message requirement set to " + newValue + " messages";
                            ctx.getSource().sendFeedback(
                                    badge().append(Text.literal(msg).formatted(Formatting.GREEN))
                            );
                            return newValue;
                        })
                );
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildCooldownCommand() {
        return literal("cooldown")
                .executes(ctx -> {
                    float seconds = QUIET_AFTER_SEND_MS / 1000f;
                    ctx.getSource().sendFeedback(
                            badge().append(Text.literal("Time cooldown: " + seconds + " seconds").formatted(Formatting.GRAY))
                    );
                    return (int) QUIET_AFTER_SEND_MS;
                })
                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                        .argument("seconds", IntegerArgumentType.integer(0, 30))
                        .executes(ctx -> {
                            int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                            QUIET_AFTER_SEND_MS = seconds * 1000L;
                            saveConfig();
                            String msg = seconds == 0 
                                ? "Time cooldown disabled (warning: may cause echo loops!)"
                                : "Time cooldown set to " + seconds + " seconds";
                            ctx.getSource().sendFeedback(
                                    badge().append(Text.literal(msg).formatted(seconds == 0 ? Formatting.YELLOW : Formatting.GREEN))
                            );
                            return seconds;
                        })
                );
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildAddReplyCommand() {
        return literal("addreply")
                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                        .argument("reply", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String newReply = StringArgumentType.getString(ctx, "reply");
                            if (newReply.isEmpty() || newReply.length() > 50) {
                                ctx.getSource().sendFeedback(
                                        badge().append(Text.literal("Reply must be 1-50 characters").formatted(Formatting.RED))
                                );
                                return 0;
                            }
                            if (REPLY_VARIATIONS.contains(newReply)) {
                                ctx.getSource().sendFeedback(
                                        badge().append(Text.literal("Reply already exists").formatted(Formatting.YELLOW))
                                );
                                return 0;
                            }
                            REPLY_VARIATIONS.add(newReply);
                            saveConfig();
                            ctx.getSource().sendFeedback(
                                    badge().append(Text.literal("Added reply: \"" + newReply + "\"").formatted(Formatting.GREEN))
                            );
                            return 1;
                        })
                );
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildRemoveReplyCommand() {
        return literal("removereply")
                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                        .argument("reply", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String replyToRemove = StringArgumentType.getString(ctx, "reply");
                            if (REPLY_VARIATIONS.size() <= 1) {
                                ctx.getSource().sendFeedback(
                                        badge().append(Text.literal("Cannot remove last reply").formatted(Formatting.RED))
                                );
                                return 0;
                            }
                            if (!REPLY_VARIATIONS.remove(replyToRemove)) {
                                ctx.getSource().sendFeedback(
                                        badge().append(Text.literal("Reply not found").formatted(Formatting.RED))
                                );
                                return 0;
                            }
                            saveConfig();
                            ctx.getSource().sendFeedback(
                                    badge().append(Text.literal("Removed reply: \"" + replyToRemove + "\"").formatted(Formatting.GREEN))
                            );
                            return 1;
                        })
                );
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildListRepliesCommand() {
        return literal("listreplies").executes(ctx -> {
            ctx.getSource().sendFeedback(
                    badge().append(Text.literal("All replies (" + REPLY_VARIATIONS.size() + "):").formatted(Formatting.AQUA))
            );
            for (int i = 0; i < REPLY_VARIATIONS.size(); i++) {
                ctx.getSource().sendFeedback(
                        Text.literal("  " + (i + 1) + ". ").formatted(Formatting.GRAY)
                                .append(Text.literal(REPLY_VARIATIONS.get(i)).formatted(Formatting.WHITE))
                );
            }
            return REPLY_VARIATIONS.size();
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildResetRepliesCommand() {
        return literal("resetreplies").executes(ctx -> {
            REPLY_VARIATIONS = new java.util.ArrayList<>(java.util.Arrays.asList(DEFAULT_VARIATIONS));
            saveConfig();
            ctx.getSource().sendFeedback(
                    badge().append(Text.literal("Reset to " + DEFAULT_VARIATIONS.length + " default replies").formatted(Formatting.GREEN))
            );
            return 1;
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> buildStatsCommands() {
        return literal("meows").executes(ctx -> {
            int count = meowCount.get();
            ctx.getSource().sendFeedback(
                    badge()
                            .append(Text.literal("You have meowed ").formatted(Formatting.WHITE))
                            .append(Text.literal(String.valueOf(count)).formatted(Formatting.AQUA))
                            .append(Text.literal(" times :3").formatted(Formatting.WHITE))
            );
            return count;
        });
    }

    private static void handleIncoming(Text message, GameProfile sender) {
        if (!ENABLED.get()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        String raw = message.getString();
        if (raw == null) return;

        // play SFX at play who meows & self
        if (CAT_SOUND.matcher(raw).find()) {
            PlayerEntity src = resolveSender(mc, sender, raw);
            if (src != null) {
                UUID me = mc.getSession().getUuidOrNull();
                if (me == null || !me.equals(src.getUuid())) {
                    mc.execute(() -> triggerCatCueAt(src));
                }
            }
        }

        long now = System.currentTimeMillis();
        if (now < quietUntil.get()) return;
        if (!MEOW.matcher(raw).find()) return;

        // ignore our own lines (when CHAT provides a sender)
        UUID me = mc.getSession().getUuidOrNull();
        if (sender != null && me != null && me.equals(sender.id())) return;

        if (myMsgsSinceReply.get() < MY_MESSAGES_REQUIRED) return;

        mc.execute(() -> {
            if (mc.player != null && mc.player.networkHandler != null) {
                skipNextOwnIncrement.set(true);

                String replyText = getRandomReply();
                String toSend = switch (detectHpChan(raw)) {
                    case GUILD -> "/gc " + replyText;
                    case PARTY -> "/pc " + replyText;
                    case COOP  -> "/cc " + replyText;
                    default -> replyText;
                };
                mc.player.networkHandler.sendChatMessage(toSend);
                triggerCatCueAt(mc.player);
                bumpMeowCount();
                quietUntil.set(System.currentTimeMillis() + QUIET_AFTER_SEND_MS);
                myMsgsSinceReply.set(0);
            }
        });
    }
}
