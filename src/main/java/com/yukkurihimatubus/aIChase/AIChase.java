package com.yukkurihimatubus.aIChase;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class AIChase extends JavaPlugin implements Listener {

    private final List<AIController> ais = new ArrayList<>();

    private AIController discoveryAI;

    // ==================================================
    // GAME状態
    // ==================================================

    private boolean gameRunning = false;

    /*
     * /aistartを実行してからSTART!まではfalse
     * START!が出た瞬間true
     */
    private boolean gameStarted = false;

    private boolean gameOverStarted = false;

    private NamespacedKey aiKey;

    // ==================================================
    // ゲーム時間設定
    // ==================================================

    // ★ここだけ変更すればゲーム時間を変更できます
    private static final long GAME_MINUTES = 30;

    private static final long GAME_TIME_TICKS =
            GAME_MINUTES * 60L * 20L;

    private BossBar gameTimerBossBar;

    private BukkitTask gameTimerTask;

    // ==================================================
    // 起動
    // ==================================================

    @Override
    public void onEnable() {

        aiKey = new NamespacedKey(
                this,
                "aichase_ai"
        );

        getServer()
                .getPluginManager()
                .registerEvents(this, this);

        getLogger().info(
                "AIChase が起動しました！"
        );
    }

    // ==================================================
    // 停止
    // ==================================================

    @Override
    public void onDisable() {

        stopGame();

        getLogger().info(
                "AIChase が停止しました！"
        );
    }

    // ==================================================
    // AIへのダメージ無効化
    // ==================================================

    @EventHandler
    public void onDamage(EntityDamageEvent event) {

        Entity entity = event.getEntity();

        if (!(entity instanceof Zombie zombie)) {
            return;
        }

        if (zombie
                .getPersistentDataContainer()
                .has(
                        aiKey,
                        PersistentDataType.BYTE
                )) {

            event.setCancelled(true);
        }
    }

    // ==================================================
    // チャンクロード時の古いAI削除
    // ==================================================

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {

        if (gameRunning) {
            return;
        }

        for (Entity entity :
                event.getChunk().getEntities()) {

            if (!(entity instanceof Zombie zombie)) {
                continue;
            }

            if (zombie
                    .getPersistentDataContainer()
                    .has(
                            aiKey,
                            PersistentDataType.BYTE
                    )) {

                zombie.remove();
            }
        }
    }

    // ==================================================
    // コマンド
    // ==================================================

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (!(sender instanceof Player player)) {

            sender.sendMessage(
                    "プレイヤーのみ使用できます。"
            );

            return true;
        }

        // ==================================================
        // /aistart
        // ==================================================

        if (command.getName()
                .equalsIgnoreCase("aistart")) {

            if (gameRunning) {

                player.sendMessage(
                        ChatColor.RED
                                + "GAMEはすでに開始されています！"
                );

                return true;
            }

            long amount = 1L;

            if (args.length >= 1) {
                try {
                    amount = Long.parseLong(args[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage(
                            ChatColor.RED
                                    + "人数は数字で指定してください！"
                    );
                    return true;
                }
            }

            if (amount < 1L || amount > 1_000_000_000_000_000L) {
                player.sendMessage(
                        ChatColor.RED
                                + "人数は1～1000000000000000です！"
                );
                return true;
            }

            startGame(
                    player,
                    amount
            );

            return true;
        }

        // ==================================================
        // /aistop
        // ==================================================

        if (command.getName()
                .equalsIgnoreCase("aistop")) {

            if (!gameRunning) {

                player.sendMessage(
                        ChatColor.GREEN
                                + "GAMEは開始されていません。"
                );

                return true;
            }

            Bukkit.broadcastMessage(
                    ChatColor.RED
                            + "GAMEが終了しました"
            );

            stopGame();

            return true;
        }

        return false;
    }

    // ==================================================
    // 古いAI削除
    // ==================================================

    private void removeOldAI() {

        for (AIController ai :
                new ArrayList<>(ais)) {

            if (ai != null) {
                ai.remove();
            }
        }

        ais.clear();

        discoveryAI = null;

        for (World world :
                Bukkit.getWorlds()) {

            for (Zombie zombie :
                    world.getEntitiesByClass(
                            Zombie.class
                    )) {

                if (zombie
                        .getPersistentDataContainer()
                        .has(
                                aiKey,
                                PersistentDataType.BYTE
                        )) {

                    zombie.remove();
                }
            }
        }
    }

    // ==================================================
    // GAME開始準備
    // ==================================================

    private void startGame(
            Player player,
            long amount
    ) {

        removeOldAI();

        stopGameTimer();

        gameRunning = true;

        // ★まだSTART!していない
        gameStarted = false;

        gameOverStarted = false;

        discoveryAI = null;

        player.sendMessage(
                ChatColor.YELLOW
                        + "GAMEが開始されるまで..."
        );

        // ==================================================
        // AI生成
        // ==================================================

        spawnAIsGradually(
                player,
                amount,
                1
        );

        // ==================================================
        // 3 → 2 → 1 → START!
        // ==================================================

        Bukkit.getScheduler()
                .runTaskLater(
                        this,
                        () -> countdown(
                                player,
                                3
                        ),
                        20L
                );
    }

    // ==================================================
    // 3 → 2 → 1 → START!
    // ==================================================

    private void countdown(
            Player player,
            int number
    ) {

        if (!gameRunning
                || !player.isOnline()) {

            return;
        }

        if (number > 0) {

            Bukkit.broadcastMessage(
                    ChatColor.RED
                            + "" + number
            );

            Bukkit.getScheduler()
                    .runTaskLater(
                            this,
                            () -> countdown(
                                    player,
                                    number - 1
                            ),
                            20L
                    );

            return;
        }

        // ==================================================
        // START!
        // ==================================================

        Bukkit.broadcastMessage(
                ChatColor.GREEN
                        + "START!"
        );

        // ==================================================
        // ★ここで初めてゲーム開始
        // ==================================================

        gameStarted = true;

        // ==================================================
        // ★START!と完全に同時に30分タイマー開始
        // ==================================================

        startGameTimer();
    }

    // ==================================================
    // 30分ゲームタイマー
    // ==================================================

    private void startGameTimer() {

        stopGameTimer();

        String startTime =
                String.format(
                        "%02d:00",
                        GAME_MINUTES
                );

        gameTimerBossBar =
                Bukkit.createBossBar(
                        "§e§l残り時間 " + startTime,
                        BarColor.YELLOW,
                        BarStyle.SOLID
                );

        gameTimerBossBar.setProgress(1.0);

        gameTimerBossBar.setVisible(true);

        // 現在オンラインの全プレイヤーに表示
        for (Player player :
                Bukkit.getOnlinePlayers()) {

            gameTimerBossBar.addPlayer(player);
        }

        final long[] remainingTicks = {
                GAME_TIME_TICKS
        };

        gameTimerTask =
                Bukkit.getScheduler()
                        .runTaskTimer(
                                this,
                                () -> {

                                    // GAMEが終了したら停止
                                    if (!gameRunning
                                            || !gameStarted) {

                                        stopGameTimer();
                                        return;
                                    }

                                    // 1秒減らす
                                    remainingTicks[0] -= 20L;

                                    // ==================================================
                                    // 時間切れ
                                    // ==================================================

                                    if (remainingTicks[0] <= 0) {

                                        remainingTicks[0] = 0;

                                        if (gameTimerBossBar != null) {

                                            gameTimerBossBar.setTitle(
                                                    "§e§l残り時間 00:00"
                                            );

                                            gameTimerBossBar.setProgress(
                                                    0.0
                                            );
                                        }

                                        // ★30分逃げ切り成功
                                        gameWinAll();

                                        return;
                                    }

                                    // ==================================================
                                    // 残り時間計算
                                    // ==================================================

                                    long totalSeconds =
                                            remainingTicks[0] / 20L;

                                    long minutes =
                                            totalSeconds / 60L;

                                    long seconds =
                                            totalSeconds % 60L;

                                    String time =
                                            String.format(
                                                    "%02d:%02d",
                                                    minutes,
                                                    seconds
                                            );

                                    // ==================================================
                                    // ボスバー更新
                                    // ==================================================

                                    if (gameTimerBossBar != null) {

                                        gameTimerBossBar.setTitle(
                                                "§e§l残り時間 "
                                                        + time
                                        );

                                        gameTimerBossBar.setProgress(
                                                (double) remainingTicks[0]
                                                        / GAME_TIME_TICKS
                                        );
                                    }

                                },
                                20L,
                                20L
                        );
    }

    // ==================================================
    // 30分タイマー停止
    // ==================================================

    private void stopGameTimer() {

        if (gameTimerTask != null) {

            gameTimerTask.cancel();

            gameTimerTask = null;
        }

        if (gameTimerBossBar != null) {

            gameTimerBossBar.removeAll();

            gameTimerBossBar.setVisible(false);

            gameTimerBossBar = null;
        }
    }

    // ==================================================
    // AIを少しずつ生成
    // ==================================================

    private void spawnAIsGradually(
            Player player,
            long amount,
            long number
    ) {

        if (!gameRunning
                || !player.isOnline()) {

            return;
        }

        if (number > amount) {
            return;
        }

        long end = Math.min(
                number + 9L,
                amount
        );

        for (long i = number;
             i <= end;
             i++) {

            spawnOneAI(
                    player,
                    i
            );
        }

        if (end < amount) {

            Bukkit.getScheduler()
                    .runTaskLater(
                            this,
                            () -> spawnAIsGradually(
                                    player,
                                    amount,
                                    end + 1
                            ),
                            1L
                    );
        }
    }

    // ==================================================
    // AI 1体生成
    // ==================================================

    private void spawnOneAI(
            Player player,
            long number
    ) {

        CompletableFuture<Location> future;

        if (number <= 10) {

            future =
                    AIController
                            .findNearSpawnLocationAsync(
                                    player
                            );

        } else {

            future =
                    AIController
                            .findSpawnLocationAsync(
                                    player
                            );
        }

        future.thenAccept(location -> {

            if (location == null) {
                return;
            }

            Bukkit.getScheduler()
                    .runTask(
                            this,
                            () -> {

                                if (!gameRunning
                                        || !player.isOnline()) {

                                    return;
                                }

                                AIController ai =
                                        new AIController(
                                                this,
                                                number
                                        );

                                ais.add(ai);

                                Location spawnLocation = location;

                                int blockY = spawnLocation.getBlockY();

                                while (
                                        blockY > spawnLocation.getWorld().getMinHeight()
                                                && spawnLocation.getWorld()
                                                .getBlockAt(
                                                        spawnLocation.getBlockX(),
                                                        blockY - 1,
                                                        spawnLocation.getBlockZ()
                                                )
                                                .isPassable()
                                ) {
                                    blockY--;
                                }

                                spawnLocation.setY(blockY);

                                ai.spawn(spawnLocation);
                            }
                    );
        });
    }

    // ==================================================
    // 最初の発見者
    // ==================================================

    public boolean setDiscoveryAI(
            AIController ai,
            Player player
    ) {

        // ★START前はAIが発見できない
        if (!gameRunning
                || !gameStarted) {

            return false;
        }

        if (discoveryAI != null) {
            return false;
        }

        discoveryAI = ai;

        // ==================================================
        // 1体が発見
        // ↓
        // 全AIに同じプレイヤーを共有
        // ==================================================

        for (AIController other :
                new ArrayList<>(ais)) {

            if (other == null) {
                continue;
            }

            if (other.getEntity() == null) {
                continue;
            }

            if (other.getEntity().isDead()) {
                continue;
            }

            other.setTargetFromDiscovery(
                    player
            );
        }

        return true;
    }

    // ==================================================
    // 見失った
    // ==================================================

    public void discoveryLost(
            AIController ai
    ) {

        if (discoveryAI != ai) {
            return;
        }

        Player player =
                ai.getTarget();

        if (player != null
                && player.isOnline()) {

            player.sendMessage(
                    ChatColor.YELLOW
                            + "AI "
                            + ai.getAiNumber()
                            + " があなたを見失いました！"
            );
        }

        discoveryAI = null;

        // 全AIのターゲット解除
        for (AIController other :
                new ArrayList<>(ais)) {

            if (other != null) {
                other.clearTarget();
            }
        }
    }

    // ==================================================
    // 全AI停止
    // ==================================================

    public void stopAllAI() {

        for (AIController ai :
                new ArrayList<>(ais)) {

            if (ai != null) {
                ai.stopMovement();
            }
        }
    }

    // ==================================================
    // GAME OVER
    // AIに捕まった場合だけ
    //
    // ★GAME OVER用ボスバーは存在しない
    // ==================================================

    public void gameOver() {

        if (gameOverStarted) {
            return;
        }

        gameOverStarted = true;

        gameRunning = false;

        gameStarted = false;

        stopGameTimer();

        Bukkit.broadcastMessage(
                ChatColor.RED
                        + "§lGAME OVER"
        );

        for (Player player :
                Bukkit.getOnlinePlayers()) {

            player.sendTitle(
                    "§c§lGAME OVER",
                    "",
                    0,
                    40,
                    10
            );
        }

        Bukkit.getScheduler()
                .runTaskLater(
                        this,
                        this::stopGame,
                        60L
                );
    }

    // ==================================================
    // 30分逃げ切り
    // ==================================================

    private void gameWinAll() {

        if (!gameRunning
                || !gameStarted) {

            return;
        }

        gameRunning = false;

        gameStarted = false;

        stopGameTimer();

        Bukkit.broadcastMessage(
                ChatColor.GREEN
                        + "§lYOU WIN!"
        );

        for (Player player :
                Bukkit.getOnlinePlayers()) {

            player.sendTitle(
                    "§a§lYOU WIN!",
                    "§f逃げ切りました！",
                    10,
                    60,
                    20
            );

            player.playSound(
                    player.getLocation(),
                    org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,
                    2.0f,
                    1.0f
            );
        }

        Bukkit.getScheduler()
                .runTaskLater(
                        this,
                        this::stopGame,
                        60L
                );
    }

    // ==================================================
    // YOU WIN
    // ==================================================

    public void gameWin(
            Player player
    ) {

        if (!gameRunning) {
            return;
        }

        if (player == null
                || !player.isOnline()) {

            stopGame();
            return;
        }

        gameRunning = false;

        gameStarted = false;

        stopGameTimer();

        player.sendTitle(
                "§a§lYOU WIN!",
                "",
                10,
                60,
                20
        );

        player.sendMessage(
                ChatColor.GREEN
                        + "§lYOU WIN!"
        );

        player.playSound(
                player.getLocation(),
                org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,
                2.0f,
                1.0f
        );

        Bukkit.getScheduler()
                .runTaskLater(
                        this,
                        this::stopGame,
                        20L
                );
    }

    // ==================================================
    // GAME終了
    // ==================================================

    public void stopGame() {

        gameRunning = false;

        gameStarted = false;

        stopGameTimer();

        discoveryAI = null;

        for (AIController ai :
                new ArrayList<>(ais)) {

            if (ai != null) {
                ai.remove();
            }
        }

        ais.clear();

        for (World world :
                Bukkit.getWorlds()) {

            for (Zombie zombie :
                    world.getEntitiesByClass(
                            Zombie.class
                    )) {

                if (zombie
                        .getPersistentDataContainer()
                        .has(
                                aiKey,
                                PersistentDataType.BYTE
                        )) {

                    zombie.remove();
                }
            }
        }
    }

    // ==================================================
    // Getter
    // ==================================================

    public List<AIController> getAIs() {
        return ais;
    }

    public AIController getDiscoveryAI() {
        return discoveryAI;
    }

    /*
     * AIController側から見る
     * 「現在ゲーム中か？」
     *
     * START!前はfalse
     */
    public boolean isGameRunning() {
        return gameRunning && gameStarted;
    }

    public NamespacedKey getAIKey() {
        return aiKey;
    }
}