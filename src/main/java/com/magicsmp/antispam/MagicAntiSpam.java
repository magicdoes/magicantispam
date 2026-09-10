package com.magicsmp.antispam;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MagicAntiSpam extends JavaPlugin implements Listener {

    private final Map<UUID, ChatRecord> records = new ConcurrentHashMap<>();
    private final LegacyComponentSerializer legacy =
            LegacyComponentSerializer.legacyAmpersand();

    private double cooldownSeconds;
    private double repeatWindowSeconds;
    private double similarityThreshold;
    private int minimumSimilarityLength;
    private boolean opBypass;

    private String cooldownMessage;
    private String repeatMessage;
    private String reloadedMessage;

    private boolean soundEnabled;
    private Sound blockedSound;
    private float soundVolume;
    private float soundPitch;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MagicAntiSpam enabled.");
    }

    private void loadSettings() {
        reloadConfig();

        cooldownSeconds = Math.max(0.0, getConfig().getDouble("cooldown-seconds", 2.0));
        repeatWindowSeconds = Math.max(0.0, getConfig().getDouble("repeat-window-seconds", 20.0));
        similarityThreshold = clamp(getConfig().getDouble("similarity-threshold", 0.85), 0.0, 1.0);
        minimumSimilarityLength = Math.max(1, getConfig().getInt("minimum-similarity-length", 4));
        opBypass = getConfig().getBoolean("op-bypass", true);

        cooldownMessage = getConfig().getString(
                "messages.cooldown",
                "&cPlease wait before sending another message."
        );
        repeatMessage = getConfig().getString(
                "messages.repeat",
                "&cPlease do not repeat the same or similar message."
        );
        reloadedMessage = getConfig().getString(
                "messages.reloaded",
                "&aMagicAntiSpam configuration reloaded."
        );

        soundEnabled = getConfig().getBoolean("sound.enabled", true);
        soundVolume = (float) getConfig().getDouble("sound.volume", 1.0);
        soundPitch = (float) getConfig().getDouble("sound.pitch", 1.0);

        String soundName = getConfig().getString("sound.name", "ENTITY_VILLAGER_NO");
        try {
            blockedSound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Invalid sound '" + soundName + "'. Falling back to ENTITY_VILLAGER_NO.");
            blockedSound = Sound.ENTITY_VILLAGER_NO;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        if (player.hasPermission("magicantispam.bypass")) {
            return;
        }

        if (opBypass && player.isOp()) {
            return;
        }

        String plainMessage = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText()
                .serialize(event.message());

        String normalized = normalize(plainMessage);
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();

        ChatRecord previous = records.get(uuid);

        // Check repeat/similarity FIRST, so sending the same message quickly
        // shows the more useful repeat warning instead of the generic cooldown.
        if (previous != null) {
            long sincePreviousMessage = now - previous.messageTimeMillis();

            if (sincePreviousMessage <= secondsToMillis(repeatWindowSeconds)
                    && isRepeatedOrSimilar(previous.normalizedMessage(), normalized)) {
                event.setCancelled(true);
                showBlockedWarning(player, repeatMessage);
                return;
            }

            long sinceLastAcceptedChat = now - previous.acceptedTimeMillis();

            if (sinceLastAcceptedChat < secondsToMillis(cooldownSeconds)) {
                event.setCancelled(true);
                showBlockedWarning(player, cooldownMessage);
                return;
            }
        }

        records.put(uuid, new ChatRecord(normalized, now, now));
    }

    private void showBlockedWarning(Player player, String message) {
        // AsyncChatEvent may run asynchronously, so send UI/sound on the server thread.
        getServer().getScheduler().runTask(this, () -> {
            if (!player.isOnline()) {
                return;
            }

            Component component = legacy.deserialize(message);
            player.sendActionBar(component);

            if (soundEnabled) {
                player.playSound(
                        player.getLocation(),
                        blockedSound,
                        soundVolume,
                        soundPitch
                );
            }
        });
    }

    private boolean isRepeatedOrSimilar(String previous, String current) {
        if (previous.equals(current)) {
            return true;
        }

        if (previous.isEmpty() || current.isEmpty()) {
            return false;
        }

        if (Math.max(previous.length(), current.length()) < minimumSimilarityLength) {
            return false;
        }

        return similarity(previous, current) >= similarityThreshold;
    }

    private String normalize(String input) {
        String value = Normalizer.normalize(input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);

        // Keep only letters and numbers.
        // This makes "hello", "HELLO", "h e l l o", and "hello!!!"
        // compare as the same message.
        return value.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private double similarity(String a, String b) {
        int maxLength = Math.max(a.length(), b.length());

        if (maxLength == 0) {
            return 1.0;
        }

        int distance = levenshteinDistance(a, b);
        return 1.0 - ((double) distance / maxLength);
    }

    private int levenshteinDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;

            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;

                current[j] = Math.min(
                        Math.min(
                                current[j - 1] + 1,
                                previous[j] + 1
                        ),
                        previous[j - 1] + cost
                );
            }

            int[] temp = previous;
            previous = current;
            current = temp;
        }

        return previous[b.length()];
    }

    private long secondsToMillis(double seconds) {
        return (long) (seconds * 1000.0);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            sender.sendMessage(legacy.deserialize(reloadedMessage));
            return true;
        }

        sender.sendMessage("§cUsage: /" + label + " reload");
        return true;
    }

    private record ChatRecord(
            String normalizedMessage,
            long messageTimeMillis,
            long acceptedTimeMillis
    ) {}
}
