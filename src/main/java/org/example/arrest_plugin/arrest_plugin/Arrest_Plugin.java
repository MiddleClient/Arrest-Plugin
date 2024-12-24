package org.example.arrest_plugin.arrest_plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Arrest_Plugin extends JavaPlugin implements Listener {
    private final Map<UUID, UUID> leashedPlayers = new HashMap<>(); // Привязка: арестованный -> арестовавший
    private final Map<UUID, Long> cooldown = new HashMap<>(); // Кулдаун для арестующей палочки
    private static final double MAX_DISTANCE = 5.0; // Максимальное расстояние между арестованным и арестовавшим

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("stick").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) {
                if (player.hasPermission("arrest_plugin.command.stick")) {
                    ItemStack stick = createMagicStick();
                    player.getInventory().addItem(stick);
                    player.sendMessage(ChatColor.GREEN + "Волшебная палочка выдана!");
                } else {
                    player.sendMessage(ChatColor.RED + "У вас нет прав на получение волшебной палочки.");
                }
                return true;
            }
            sender.sendMessage(ChatColor.RED + "Эту команду может использовать только игрок.");
            return false;
        });
    }

    @Override
    public void onDisable() {
        leashedPlayers.clear();
    }

    private ItemStack createMagicStick() {
        ItemStack stick = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = stick.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Арестующая палочка");
            stick.setItemMeta(meta);
        }
        return stick;
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player target)) return;

        Player attacker = event.getPlayer();
        ItemStack itemInHand = attacker.getInventory().getItemInMainHand();

        if (itemInHand.getType() == Material.BLAZE_ROD &&
                itemInHand.getItemMeta() != null &&
                ChatColor.stripColor(itemInHand.getItemMeta().getDisplayName()).equals("Арестующая палочка")) {

            UUID targetUUID = target.getUniqueId();
            UUID attackerUUID = attacker.getUniqueId();

            // Проверка на cooldown (защита от быстрого повторного удара)
            long lastUse = cooldown.getOrDefault(attackerUUID, 0L);
            long currentTime = System.currentTimeMillis();

            if (currentTime - lastUse < 1000) {
                return; // Просто выходим, если cooldown еще не прошел
            }

            cooldown.put(attackerUUID, currentTime); // Обновить время последнего использования

            // Если у игрока уже есть эффект слепоты
            if (target.hasPotionEffect(PotionEffectType.BLINDNESS)) {
                // Убираем слепоту и отвязываем игрока
                target.removePotionEffect(PotionEffectType.BLINDNESS);
                leashedPlayers.remove(targetUUID);

                attacker.sendMessage(ChatColor.GREEN + "Игрок освобожден.");
                target.sendMessage(ChatColor.RED + "Вы больше не арестованы.");
            } else {
                // Привязываем игрока и накладываем эффект слепоты
                leashedPlayers.put(targetUUID, attackerUUID);
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1)); // Слепота навсегда
                attacker.sendMessage(ChatColor.YELLOW + "Игрок арестован.");
                target.sendMessage(ChatColor.RED + "Вы арестованы!");

                // Принудительное перемещение игрока за арестовавшим
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!leashedPlayers.containsKey(targetUUID)) {
                            cancel();
                            return;
                        }
                        if (attacker.isOnline() && target.isOnline()) {
                            double distance = attacker.getLocation().distance(target.getLocation());
                            if (distance > MAX_DISTANCE) {
                                target.teleport(attacker.getLocation().add(1, 0, 1));
                            }
                        } else {
                            leashedPlayers.remove(targetUUID);
                            cancel();
                        }
                    }
                }.runTaskTimer(this, 0, 10); // Проверять каждую секунду (10 тиков)
            }
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getInventory().getResult() != null) {
            ItemStack result = event.getInventory().getResult();

            // Проверяем, если крафтится огненный порошок
            if (result.getType() == Material.BLAZE_POWDER) {
                // Проверяем, есть ли палочка в ингредиентах
                for (ItemStack item : event.getInventory().getMatrix()) {
                    if (item != null && item.getType() == Material.BLAZE_ROD) {
                        if (item.hasItemMeta() && ChatColor.stripColor(item.getItemMeta().getDisplayName()).equals("Арестующая палочка")) {
                            // Запрещаем крафт
                            event.getInventory().setResult(null);
                            break;
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quitter = event.getPlayer();
        UUID quitterUUID = quitter.getUniqueId();

        // Если арестованный игрок выходит
        if (leashedPlayers.containsKey(quitterUUID)) {
            Player attacker = Bukkit.getPlayer(leashedPlayers.get(quitterUUID));
            leashedPlayers.remove(quitterUUID);
            quitter.removePotionEffect(PotionEffectType.BLINDNESS); // Убираем слепоту
            if (attacker != null) {
                attacker.sendMessage(ChatColor.YELLOW + "Арест снят, так как арестованный игрок вышел с сервера.");
            }
        }

        // Если арестовавший игрок выходит
        if (leashedPlayers.containsValue(quitterUUID)) {
            for (Map.Entry<UUID, UUID> entry : leashedPlayers.entrySet()) {
                if (entry.getValue().equals(quitterUUID)) {
                    Player target = Bukkit.getPlayer(entry.getKey());
                    if (target != null) {
                        target.removePotionEffect(PotionEffectType.BLINDNESS); // Убираем слепоту
                        target.sendMessage(ChatColor.YELLOW + "Арест снят, так как ваш арестовавший вышел с сервера.");
                    }
                    leashedPlayers.remove(entry.getKey());
                    break;
                }
            }
        }
    }
}
