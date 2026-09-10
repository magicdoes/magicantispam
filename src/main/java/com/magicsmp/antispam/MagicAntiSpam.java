package com.magicsmp.antispam;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

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

    private final PlainTextComponentSerializer plain =
            PlainTextComponentSerializer.plainText();

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

        getServer()
                .getPluginManager()
                .registerEvents(this, this);

        getLogger().info("MagicAntiSpam enabled!");
    }

    @Override
    public void onDisable() {
        records.clear();

        getLogger().info("MagicAntiSpam disabled.");
    }

    private void loadSettings() {

        reloadConfig();

        cooldownSeconds =
                Math.max(
                        0.0,
                        getConfig().getDouble(
                                "cooldown-seconds",
                                2.0
                        )
                );

        repeatWindowSeconds =
                Math.max(
                        0.0,
                        getConfig().getDouble(
                                "repeat-window-seconds",
                                20.0
                        )
                );

        similarityThreshold =
                clamp(
                        getConfig().getDouble(
                                "similarity-threshold",
                                0.85
                        ),
                        0.0,
                        1.0
                );

        minimumSimilarityLength =
                Math.max(
                        1,
                        getConfig().getInt(
                                "minimum-similarity-length",
                                4
                        )
                );

        opBypass =
                getConfig().getBoolean(
                        "op-bypass",
                        true
                );

        cooldownMessage =
                getConfig().getString(
                        "messages.cooldown",
                        "&cPlease wait before sending another message."
                );

        repeatMessage =
                getConfig().getString(
                        "messages.repeat",
                        "&cPlease do not repeat the same or similar message."
                );

        reloadedMessage =
                getConfig().getString(
                        "messages.reloaded",
                        "&aMagicAntiSpam configuration reloaded."
                );

        soundEnabled =
                getConfig().getBoolean(
                        "sound.enabled",
                        true
                );

        soundVolume =
                (float) getConfig().getDouble(
                        "sound.volume",
                        1.0
                );

        soundPitch =
                (float) getConfig().getDouble(
                        "sound.pitch",
                        1.0
                );

        String soundName =
                getConfig().getString(
                        "sound.name",
                        "ENTITY_VILLAGER_NO"
                );

        try {

            blockedSound =
                    Sound.valueOf(
                            soundName.toUpperCase(Locale.ROOT)
                    );

        } catch (IllegalArgumentException exception) {

            getLogger().warning(
                    "Invalid sound '" +
                            soundName +
                            "'. Using ENTITY_VILLAGER_NO."
            );

            blockedSound =
                    Sound.ENTITY_VILLAGER_NO;
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onChat(AsyncChatEvent event) {

        Player player = event.getPlayer();

        // Permission bypass
        if (player.hasPermission(
                "magicantispam.bypass"
        )) {
            return;
        }

        // OP bypass
        if (opBypass && player.isOp()) {
            return;
        }

        String message =
                plain.serialize(
                        event.message()
                );

        String normalized =
                normalize(message);

        UUID uuid =
                player.getUniqueId();

        long now =
                System.currentTimeMillis();

        ChatRecord previous =
                records.get(uuid);

        if (previous != null) {

            long sincePreviousMessage =
                    now -
                    previous.messageTimeMillis();

            /*
             * Check repeated/similar messages BEFORE
             * checking the normal chat cooldown.
             *
             * This means:
             *
             * hello
             * hello
             *
             * gives:
             *
             * "Please do not repeat the same or similar message."
             *
             * rather than the generic cooldown warning.
             */

            if (
                    sincePreviousMessage <=
                            secondsToMillis(
                                    repeatWindowSeconds
                            )
                    &&
                    isRepeatedOrSimilar(
                            previous.normalizedMessage(),
                            normalized
                    )
            ) {

                event.setCancelled(true);

                showBlockedWarning(
                        player,
                        repeatMessage
                );

                return;
            }

            long sinceAcceptedChat =
                    now -
                    previous.acceptedTimeMillis();

            /*
             * Normal chat cooldown
             */

            if (
                    sinceAcceptedChat <
                            secondsToMillis(
                                    cooldownSeconds
                            )
            ) {

                event.setCancelled(true);

                showBlockedWarning(
                        player,
                        cooldownMessage
                );

                return;
            }
        }

        /*
         * Message was allowed.
         *
         * Store it for the next anti-spam check.
         */

        records.put(
                uuid,
                new ChatRecord(
                        normalized,
                        now,
                        now
                )
        );
    }

    private void showBlockedWarning(
            Player player,
            String message
    ) {

        /*
         * AsyncChatEvent can execute asynchronously.
         *
         * Action bar and sound are therefore sent
         * back through the server thread.
         */

        getServer()
                .getScheduler()
                .runTask(
                        this,
                        () -> {

                            if (!player.isOnline()) {
                                return;
                            }

                            Component component =
                                    legacy.deserialize(
                                            message
                                    );

                            /*
                             * THIS displays the warning above
                             * the player's hotbar/inventory.
                             */

                            player.sendActionBar(
                                    component
                            );

                            /*
                             * Villager NO sound
                             */

                            if (soundEnabled) {

                                player.playSound(
                                        player.getLocation(),
                                        blockedSound,
                                        soundVolume,
                                        soundPitch
                                );
                            }
                        }
                );
    }

    private boolean isRepeatedOrSimilar(
            String previous,
            String current
    ) {

        /*
         * Exact duplicate
         */

        if (previous.equals(current)) {
            return true;
        }

        if (
                previous.isEmpty()
                ||
                current.isEmpty()
        ) {
            return false;
        }

        /*
         * Don't similarity-check extremely short
         * messages because it can cause false positives.
         */

        if (
                Math.max(
                        previous.length(),
                        current.length()
                )
                <
                minimumSimilarityLength
        ) {
            return false;
        }

        return similarity(
                previous,
                current
        )
                >=
                similarityThreshold;
    }

    private String normalize(
            String input
    ) {

        String value =
                Normalizer
                        .normalize(
                                input,
                                Normalizer.Form.NFKC
                        )
                        .toLowerCase(
                                Locale.ROOT
                        );

        /*
         * Remove spaces and punctuation.
         *
         * These become equivalent:
         *
         * hello
         * HELLO
         * hello!!!
         * h e l l o
         * h-e-l-l-o
         */

        return value.replaceAll(
                "[^\\p{L}\\p{N}]",
                ""
        );
    }

    private double similarity(
            String first,
            String second
    ) {

        int maxLength =
                Math.max(
                        first.length(),
                        second.length()
                );

        if (maxLength == 0) {
            return 1.0;
        }

        int distance =
                levenshteinDistance(
                        first,
                        second
                );

        return 1.0 -
                (
                        (double) distance
                        /
                        maxLength
                );
    }

    private int levenshteinDistance(
            String first,
            String second
    ) {

        int[] previous =
                new int[
                        second.length() + 1
                        ];

        int[] current =
                new int[
                        second.length() + 1
                        ];

        for (
                int j = 0;
                j <= second.length();
                j++
        ) {
            previous[j] = j;
        }

        for (
                int i = 1;
                i <= first.length();
                i++
        ) {

            current[0] = i;

            for (
                    int j = 1;
                    j <= second.length();
                    j++
            ) {

                int cost =
                        first.charAt(i - 1)
                                ==
                                second.charAt(j - 1)
                                ? 0
                                : 1;

                current[j] =
                        Math.min(
                                Math.min(
                                        current[j - 1] + 1,
                                        previous[j] + 1
                                ),
                                previous[j - 1] + cost
                        );
            }

            int[] temporary =
                    previous;

            previous =
                    current;

            current =
                    temporary;
        }

        return previous[
                second.length()
                ];
    }

    private long secondsToMillis(
            double seconds
    ) {

        return (long)
                (
                        seconds
                        *
                        1000.0
                );
    }

    private double clamp(
            double value,
            double minimum,
            double maximum
    ) {

        return Math.max(
                minimum,
                Math.min(
                        maximum,
                        value
                )
        );
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (
                args.length == 1
                &&
                args[0].equalsIgnoreCase(
                        "reload"
                )
        ) {

            loadSettings();

            sender.sendMessage(
                    legacy.deserialize(
                            reloadedMessage
                    )
            );

            return true;
        }

        sender.sendMessage(
                "§cUsage: /" +
                        label +
                        " reload"
        );

        return true;
    }

    private record ChatRecord(
            String normalizedMessage,
            long messageTimeMillis,
            long acceptedTimeMillis
    ) {
    }
}
