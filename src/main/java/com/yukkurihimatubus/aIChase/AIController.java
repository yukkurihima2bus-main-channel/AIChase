package com.yukkurihimatubus.aIChase;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Random;
import java.util.concurrent.CompletableFuture;

public class AIController {

    private final AIChase plugin;
    private final Random random = new Random();
    private final long aiNumber;

    private Zombie entity;
    private Player target;

    private boolean alerted = false;
    private boolean caught = false;
    private boolean removed = false;
    private boolean movementStopped = false;

    private BukkitTask aiTask;
    private BukkitTask fireProtectionTask;

    private Vector wanderDirection;
    private int wanderTicks = 0;

    // ==================================================
    // 設定
    // ==================================================

    private final double detectDistance = 25.0;
    private final double loseDistance = 40.0;
    private final double visionDistance = 30.0;

    private final double chaseSpeed =
            0.10 + random.nextDouble() * 0.20;

    private final double wanderSpeed = 0.20;

    private final double longRangeDistance = 300.0;

    // ==================================================
    // コンストラクタ
    // ==================================================

    public AIController(
            AIChase plugin,
            long aiNumber
    ) {
        this.plugin = plugin;
        this.aiNumber = aiNumber;
    }

    // ==================================================
    // スポーン
    // ==================================================

    public void spawn(Location location) {

        if (location == null
                || location.getWorld() == null) {
            return;
        }

        entity = location.getWorld().spawn(
                location,
                Zombie.class
        );

        NamespacedKey key =
                plugin.getAIKey();

        entity.getPersistentDataContainer().set(
                key,
                PersistentDataType.BYTE,
                (byte) 1
        );

        entity.setCustomName(
                "§cAI " + aiNumber
        );

        entity.setCustomNameVisible(true);

        // Bukkit標準AIは使用しない
        entity.setAI(false);

        entity.setInvulnerable(true);

        entity.setRemoveWhenFarAway(false);
        entity.setPersistent(true);

        entity.setGlowing(false);

        entity.setFireTicks(0);
        entity.setVisualFire(false);

        entity.getEquipment().setHelmet(
                new ItemStack(
                        Material.DIAMOND_HELMET
                )
        );

        entity.getEquipment()
                .setHelmetDropChance(0.0f);

        // ==================================================
        // 炎上防止
        // ==================================================

        fireProtectionTask =
                Bukkit.getScheduler().runTaskTimer(
                        plugin,
                        () -> {

                            if (removed
                                    || entity == null
                                    || entity.isDead()) {
                                return;
                            }

                            entity.setFireTicks(0);
                            entity.setVisualFire(false);
                            entity.setGlowing(false);

                        },
                        1L,
                        1L
                );

        startAI();
    }

    // ==================================================
    // 強制停止
    // ==================================================

    public void stopMovement() {

        movementStopped = true;

        if (aiTask != null) {
            aiTask.cancel();
            aiTask = null;
        }

        target = null;
        alerted = false;
    }

    // ==================================================
    // AI開始
    // ==================================================

    private void startAI() {

        aiTask =
                Bukkit.getScheduler().runTaskTimer(
                        plugin,
                        () -> {

                            if (removed
                                    || entity == null
                                    || entity.isDead()) {
                                return;
                            }

                            if (!plugin.isGameRunning()) {
                                return;
                            }

                            if (movementStopped) {
                                return;
                            }

                            // ==================================================
                            // 追跡中
                            // ==================================================

                            if (target != null
                                    && alerted) {

                                if (!target.isOnline()) {

                                    clearTarget();
                                    return;
                                }

                                double distance =
                                        entity.getLocation()
                                                .distance(
                                                        target.getLocation()
                                                );

                                // 捕獲
                                if (!caught
                                        && distance <= 1) {

                                    catchPlayer();
                                    return;
                                }

                                // 最初の発見AIだけ見失い判定
                                if (!caught
                                        && plugin.getDiscoveryAI() == this) {

                                    if (distance > loseDistance) {

                                        plugin.discoveryLost(this);
                                        return;
                                    }
                                }

                                // 長距離高速移動
                                if (distance >=
                                        longRangeDistance) {

                                    moveToLocation(
                                            target.getLocation(),
                                            1.0
                                    );

                                } else {

                                    moveToTarget();
                                }

                                return;
                            }

                            // ==================================================
                            // 未発見
                            // ==================================================

                            Player nearby =
                                    findNearbyPlayer();

                            if (nearby != null) {

                                detectPlayer(nearby);
                                return;
                            }

                            Player visible =
                                    findVisiblePlayer();

                            if (visible != null) {

                                detectPlayer(visible);
                                return;
                            }

                            // ==================================================
                            // 通常移動
                            // ==================================================

                            wander();

                        },
                        1L,
                        1L
                );
    }

    // ==================================================
    // プレイヤー発見
    // ==================================================

    private void detectPlayer(
            Player player
    ) {

        if (removed
                || movementStopped) {
            return;
        }

        if (!plugin.setDiscoveryAI(
                this,
                player
        )) {
            return;
        }

        target = player;
        alerted = true;

        player.sendMessage(
                "§c§lAI "
                        + aiNumber
                        + " があなたを発見しました！"
        );

        if (entity != null
                && !entity.isDead()) {

            entity.setCustomName(
                    "§4§lAI " + aiNumber
            );
        }
    }

    // ==================================================
    // 全AIへターゲット共有
    // ==================================================

    public void setTargetFromDiscovery(
            Player player
    ) {

        if (removed
                || movementStopped) {
            return;
        }

        target = player;
        alerted = true;

        if (entity != null
                && !entity.isDead()) {

            entity.setCustomName(
                    "§4§lAI " + aiNumber
            );
        }
    }

    // ==================================================
    // ターゲット解除
    // ==================================================

    public void clearTarget() {

        target = null;
        alerted = false;

        wanderDirection = null;
        wanderTicks = 0;

        if (entity != null
                && !entity.isDead()) {

            entity.setCustomName(
                    "§cAI " + aiNumber
            );
        }
    }

    // ==================================================
    // 指定地点へ移動
    // ==================================================

    private void moveToLocation(
            Location destination,
            double speed
    ) {

        moveTowards(
                destination,
                speed
        );
    }

    // ==================================================
    // ターゲット追跡
    // ==================================================

    private void moveToTarget() {

        if (target == null
                || !target.isOnline()
                || movementStopped) {
            return;
        }

        double distance =
                entity.getLocation()
                        .distance(
                                target.getLocation()
                        );

        if (!caught
                && distance <= 2.5) {

            catchPlayer();
            return;
        }

        moveTowards(
                target.getLocation(),
                chaseSpeed
        );
    }

    // ==================================================
    // 共通移動
    // ==================================================

    private void moveTowards(
            Location destination,
            double speed
    ) {

        if (destination == null
                || entity == null
                || entity.isDead()
                || removed
                || movementStopped) {
            return;
        }

        if (!destination.getWorld().equals(
                entity.getWorld()
        )) {
            return;
        }

        Location current =
                entity.getLocation();

        Vector direction =
                destination.toVector()
                        .subtract(
                                current.toVector()
                        );

        direction.setY(0);

        if (direction.lengthSquared() < 0.01) {
            return;
        }

        direction.normalize();

        double nextX =
                current.getX()
                        + direction.getX() * speed;

        double nextZ =
                current.getZ()
                        + direction.getZ() * speed;

        int blockX =
                (int) Math.floor(nextX);

        int blockZ =
                (int) Math.floor(nextZ);

        int currentY =
                current.getBlockY();

        World world =
                entity.getWorld();

        Material currentBlock =
                world.getBlockAt(
                        blockX,
                        currentY,
                        blockZ
                ).getType();

        // ==================================================
        // 🌊 水に入った場合
        // 水面まで上がって歩く
        // ==================================================

        if (currentBlock == Material.WATER) {

            int waterSurfaceY =
                    findWaterSurfaceY(
                            world,
                            blockX,
                            currentY,
                            blockZ
                    );

            if (waterSurfaceY != Integer.MIN_VALUE) {

                Location next =
                        current.clone();

                next.setX(nextX);
                next.setZ(nextZ);

                // 水面の1ブロック上
                next.setY(
                        waterSurfaceY + 1.0
                );

                next.setDirection(direction);

                entity.teleport(next);

                return;
            }
        }

        var front =
                world.getBlockAt(
                        blockX,
                        currentY,
                        blockZ
                );

        var frontAbove =
                world.getBlockAt(
                        blockX,
                        currentY + 1,
                        blockZ
                );

        var frontTwoAbove =
                world.getBlockAt(
                        blockX,
                        currentY + 2,
                        blockZ
                );

        // ==================================================
        // 🌊 前方が水
        // ==================================================

        if (front.getType() == Material.WATER) {

            int waterSurfaceY =
                    findWaterSurfaceY(
                            world,
                            blockX,
                            currentY,
                            blockZ
                    );

            if (waterSurfaceY != Integer.MIN_VALUE) {

                Location next =
                        current.clone();

                next.setX(nextX);
                next.setZ(nextZ);
                next.setY(
                        waterSurfaceY + 1.0
                );

                next.setDirection(direction);

                entity.teleport(next);

                return;
            }
        }

        // ==================================================
        // 障害物 → 1ブロック上へ
        // ==================================================

        if (!front.isPassable()) {

            if (frontAbove.isPassable()
                    && frontTwoAbove.isPassable()) {

                Location next =
                        current.clone();

                next.setX(nextX);
                next.setZ(nextZ);

                next.setY(
                        current.getY() + 1.0
                );

                next.setDirection(direction);

                entity.teleport(next);

                return;
            }

            return;
        }

        // ==================================================
        // 地面検索
        // ==================================================

        int groundY =
                findGroundY(
                        blockX,
                        currentY,
                        blockZ
                );

        if (groundY != Integer.MIN_VALUE) {

            double difference =
                    groundY - current.getY();

            // 下る
            if (difference < -0.1
                    && difference >= -8.0) {

                Location next =
                        current.clone();

                next.setX(nextX);
                next.setZ(nextZ);

                next.setY(
                        groundY + 1.0
                );

                next.setDirection(direction);

                entity.teleport(next);

                return;
            }

            // 上る
            if (difference > 0.1
                    && difference <= 1.1) {

                Location next =
                        current.clone();

                next.setX(nextX);
                next.setZ(nextZ);

                next.setY(
                        groundY + 1.0
                );

                next.setDirection(direction);

                entity.teleport(next);

                return;
            }
        }

        // ==================================================
        // 通常移動
        // ==================================================

        Location next =
                current.clone();

        next.setX(nextX);
        next.setZ(nextZ);
        next.setY(current.getY());

        next.setDirection(direction);

        entity.teleport(next);
    }

    // ==================================================
    // 🌊 水面検索
    // ==================================================

    private static int findWaterSurfaceY(
            World world,
            int x,
            int startY,
            int z
    ) {

        int maxY =
                world.getMaxHeight() - 1;

        int minY =
                world.getMinHeight();

        int searchStart =
                Math.min(
                        startY + 8,
                        maxY
                );

        for (
                int y = searchStart;
                y >= minY;
                y--
        ) {

            Material block =
                    world.getBlockAt(
                            x,
                            y,
                            z
                    ).getType();

            Material above =
                    world.getBlockAt(
                            x,
                            y + 1,
                            z
                    ).getType();

            if (block == Material.WATER
                    && above != Material.WATER) {

                return y;
            }
        }

        return Integer.MIN_VALUE;
    }

    // ==================================================
    // 地面検索
    // ==================================================

    private int findGroundY(
            int x,
            int startY,
            int z
    ) {

        World world =
                entity.getWorld();

        for (
                int y = startY;
                y >= startY - 8;
                y--
        ) {

            Material material =
                    world.getBlockAt(
                            x,
                            y,
                            z
                    ).getType();

            var block =
                    world.getBlockAt(
                            x,
                            y,
                            z
                    );

            var above =
                    world.getBlockAt(
                            x,
                            y + 1,
                            z
                    );

            // 水・溶岩は地面扱いしない
            if (material == Material.WATER
                    || material == Material.LAVA) {

                continue;
            }

            if (!block.isPassable()
                    && above.isPassable()) {

                return y;
            }
        }

        return Integer.MIN_VALUE;
    }

    // ==================================================
    // 捕獲
    // ==================================================

    private void catchPlayer() {

        if (caught
                || removed) {
            return;
        }

        Player caughtPlayer = target;

        if (caughtPlayer == null
                || !caughtPlayer.isOnline()) {
            return;
        }

        caught = true;

        caughtPlayer.sendMessage(
                "§c§lAI "
                        + aiNumber
                        + " につかまった！"
        );

        caughtPlayer.playSound(
                caughtPlayer.getLocation(),
                Sound.BLOCK_GLASS_BREAK,
                10.0f,
                0.25f
        );

        caughtPlayer.sendMessage(
                "§cGAMEを終了します..."
        );

        Bukkit.getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {

                            if (plugin.isGameRunning()) {
                                plugin.stopAllAI();
                            }

                        },
                        20L
                );

        Bukkit.getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {

                            if (caughtPlayer.isOnline()) {

                                caughtPlayer.sendTitle(
                                        "§c§lGAME OVER",
                                        "",
                                        0,
                                        40,
                                        10
                                );
                            }

                            plugin.gameOver();

                        },
                        140L
                );
    }

    // ==================================================
    // 近距離検索
    // ==================================================

    private Player findNearbyPlayer() {

        if (entity == null
                || removed) {
            return null;
        }

        Location location =
                entity.getLocation();

        for (Player player :
                Bukkit.getOnlinePlayers()) {

            if (!player.getWorld().equals(
                    entity.getWorld()
            )) {
                continue;
            }

            if (location.distance(
                    player.getLocation()
            ) <= detectDistance) {

                return player;
            }
        }

        return null;
    }

    // ==================================================
    // 視界検索
    // ==================================================

    private Player findVisiblePlayer() {

        if (entity == null
                || removed) {
            return null;
        }

        Location eye =
                entity.getEyeLocation();

        for (Player player :
                Bukkit.getOnlinePlayers()) {

            if (!player.getWorld().equals(
                    entity.getWorld()
            )) {
                continue;
            }

            Location playerEye =
                    player.getEyeLocation();

            if (eye.distance(playerEye)
                    > visionDistance) {
                continue;
            }

            if (hasLineOfSight(
                    eye,
                    playerEye
            )) {

                return player;
            }
        }

        return null;
    }

    // ==================================================
    // 通常移動
    // ==================================================

    private void wander() {

        if (entity == null
                || removed
                || movementStopped) {
            return;
        }

        if (wanderDirection == null
                || wanderTicks <= 0) {

            double angle =
                    random.nextDouble()
                            * Math.PI
                            * 2;

            wanderDirection =
                    new Vector(
                            Math.cos(angle),
                            0,
                            Math.sin(angle)
                    ).normalize();

            wanderTicks =
                    40 + random.nextInt(81);
        }

        Location current =
                entity.getLocation();

        Location destination =
                current.clone()
                        .add(
                                wanderDirection
                                        .clone()
                                        .multiply(5)
                        );

        moveTowards(
                destination,
                wanderSpeed
        );

        wanderTicks--;
    }

    // ==================================================
    // 視線判定
    // ==================================================

    private boolean hasLineOfSight(
            Location from,
            Location to
    ) {

        Vector direction =
                to.toVector()
                        .subtract(
                                from.toVector()
                        );

        double distance =
                direction.length();

        if (distance <= 0.01) {
            return true;
        }

        direction.normalize();

        for (
                double i = 0;
                i < distance;
                i += 0.5
        ) {

            Location check =
                    from.clone()
                            .add(
                                    direction.clone()
                                            .multiply(i)
                            );

            if (!check.getBlock().isPassable()) {
                return false;
            }
        }

        return true;
    }

    // ==================================================
    // 50～100ブロック
    // ==================================================

    // ==================================================
// 50～200ブロックの範囲でスポーン
// ==================================================

    public static CompletableFuture<Location>
    findSpawnLocationAsync(
            Player player
    ) {

        CompletableFuture<Location> future =
                new CompletableFuture<>();

        World world =
                player.getWorld();

        Location center =
                player.getLocation();

        findSpawnAttempt(
                player,
                world,
                center,
                50.0,   // 最小距離
                200.0,  // 最大距離
                1,
                future
        );

        return future;
    }

    // ==================================================
    // 最初の10体
    // ==================================================

    public static CompletableFuture<Location>
    findNearSpawnLocationAsync(
            Player player
    ) {

        CompletableFuture<Location> future =
                new CompletableFuture<>();

        World world =
                player.getWorld();

        Location center =
                player.getLocation();

        findSpawnAttempt(
                player,
                world,
                center,
                1,
                1,
                0,
                future
        );

        return future;
    }

    // ==================================================
    // スポーン地点探索
    // ==================================================

    private static void findSpawnAttempt(
            Player player,
            World world,
            Location center,
            double minDistance,
            double maxDistance,
            int attempt,
            CompletableFuture<Location> future
    ) {

        if (attempt >= 30) {

            future.complete(null);
            return;
        }

        Random random =
                new Random();

        double distance =
                minDistance
                        + random.nextDouble()
                        * (maxDistance - minDistance);

        double angle =
                random.nextDouble()
                        * Math.PI
                        * 2;

        double x =
                center.getX()
                        + Math.cos(angle)
                        * distance;

        double z =
                center.getZ()
                        + Math.sin(angle)
                        * distance;

        int chunkX =
                ((int) Math.floor(x)) >> 4;

        int chunkZ =
                ((int) Math.floor(z)) >> 4;

        // ==================================================
        // 既にロード済み
        // ==================================================

        if (world.isChunkLoaded(
                chunkX,
                chunkZ
        )) {

            Location location =
                    createSurfaceLocation(
                            world,
                            x,
                            z
                    );

            if (location != null) {

                future.complete(location);
                return;
            }

            findSpawnAttempt(
                    player,
                    world,
                    center,
                    minDistance,
                    maxDistance,
                    attempt + 1,
                    future
            );

            return;
        }

        // ==================================================
        // 非同期チャンクロード
        // ==================================================

        world.getChunkAtAsync(
                chunkX,
                chunkZ,
                true
        ).thenAccept(chunk -> {

            Bukkit.getScheduler().runTask(
                    JavaPlugin.getProvidingPlugin(
                            AIController.class
                    ),
                    () -> {

                        if (future.isDone()) {
                            return;
                        }

                        Location location =
                                createSurfaceLocation(
                                        world,
                                        x,
                                        z
                                );

                        if (location != null) {

                            future.complete(location);

                        } else {

                            findSpawnAttempt(
                                    player,
                                    world,
                                    center,
                                    minDistance,
                                    maxDistance,
                                    attempt + 1,
                                    future
                            );
                        }
                    }
            );

        }).exceptionally(error -> {

            if (!future.isDone()) {

                Bukkit.getScheduler().runTask(
                        JavaPlugin.getProvidingPlugin(
                                AIController.class
                        ),
                        () -> findSpawnAttempt(
                                player,
                                world,
                                center,
                                minDistance,
                                maxDistance,
                                attempt + 1,
                                future
                        )
                );
            }

            return null;
        });
    }

    // ==================================================
    // 地上の安全な位置
    // ==================================================

    private static Location createSurfaceLocation(
            World world,
            double x,
            double z
    ) {

        int blockX =
                (int) Math.floor(x);

        int blockZ =
                (int) Math.floor(z);

        int y =
                world.getHighestBlockYAt(
                        blockX,
                        blockZ
                );

        // ==================================================
        // 地面
        // ==================================================

        Material ground =
                world.getBlockAt(
                        blockX,
                        y,
                        blockZ
                ).getType();

        Material feet =
                world.getBlockAt(
                        blockX,
                        y + 1,
                        blockZ
                ).getType();

        Material head =
                world.getBlockAt(
                        blockX,
                        y + 2,
                        blockZ
                ).getType();

        // ==================================================
        // 🌊 水・溶岩を完全に除外
        // ==================================================

        if (ground == Material.WATER
                || ground == Material.LAVA
                || feet == Material.WATER
                || feet == Material.LAVA
                || head == Material.WATER
                || head == Material.LAVA) {

            return null;
        }

        Location location =
                new Location(
                        world,
                        x,
                        y + 1,
                        z
                );

        // 足元
        if (!location.getBlock().isPassable()) {
            return null;
        }

        // 頭
        if (!location.clone()
                .add(0, 1, 0)
                .getBlock()
                .isPassable()) {
            return null;
        }

        // ==================================================
        // 念のため水・溶岩確認
        // ==================================================

        Material feetMaterial =
                location.getBlock().getType();

        Material headMaterial =
                location.clone()
                        .add(0, 1, 0)
                        .getBlock()
                        .getType();

        if (feetMaterial == Material.WATER
                || feetMaterial == Material.LAVA
                || headMaterial == Material.WATER
                || headMaterial == Material.LAVA) {

            return null;
        }

        return location;
    }

    // ==================================================
    // 削除
    // ==================================================

    public void remove() {

        if (removed) {
            return;
        }

        removed = true;

        target = null;
        alerted = false;
        caught = true;
        movementStopped = true;

        if (aiTask != null) {

            aiTask.cancel();
            aiTask = null;
        }

        if (fireProtectionTask != null) {

            fireProtectionTask.cancel();
            fireProtectionTask = null;
        }

        if (entity != null) {

            if (!entity.isDead()) {
                entity.remove();
            }

            entity = null;
        }
    }

    // ==================================================
    // Getter
    // ==================================================

    public Zombie getEntity() {
        return entity;
    }

    public Player getTarget() {
        return target;
    }

    public double getChaseSpeed() {
        return chaseSpeed;
    }

    public long getAiNumber() {
        return aiNumber;
    }
}