package com.vanillage.raytraceantixray.tasks;

import java.util.Queue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.vanillage.raytraceantixray.RayTraceAntiXray;
import com.vanillage.raytraceantixray.data.ChunkBlocks;
import com.vanillage.raytraceantixray.data.LongWrapper;
import com.vanillage.raytraceantixray.data.PlayerData;
import com.vanillage.raytraceantixray.data.Result;

import io.netty.channel.Channel;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class UpdateBukkitRunnable extends BukkitRunnable implements Consumer<ScheduledTask> {
    private final RayTraceAntiXray plugin;
    private final Player player;

    public UpdateBukkitRunnable(RayTraceAntiXray plugin) {
        this(plugin, null);
    }

    public UpdateBukkitRunnable(RayTraceAntiXray plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    @Override
    public void run() {
        if (player == null) {
            plugin.getServer().getOnlinePlayers().forEach(this::update);
        } else {
            update(player);
        }
    }

    @Override
    public void accept(ScheduledTask t) {
        run();
    }

    public void update(Player player) {
        PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());
        World world = playerData.getLocations()[0].getWorld();

        if (!player.getWorld().equals(world)) {
            return;
        }

        ConcurrentMap<LongWrapper, ChunkBlocks> chunks = playerData.getChunks();
        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        Environment environment = world.getEnvironment();
        Queue<Result> results = playerData.getResults();
        Result result;

        while ((result = results.poll()) != null) {
            ChunkBlocks chunkBlocks = result.getChunkBlocks();

            // Check if the client still has the chunk loaded and if it wasn't resent in the meantime.
            // Note that even if this check passes, the server could have already unloaded or resent the chunk but the corresponding packet is still in the packet queue.
            // Technically the null check isn't necessary but we don't need to send an update packet because the client will unload the chunk.
            if (chunkBlocks.getChunk() == null || chunks.get(chunkBlocks.getKey()) != chunkBlocks) {
                continue;
            }

            BlockPos block = result.getBlock();

            // Similar to the null check above, this check isn't actually necessary.
            // However, we don't need to send an update packet because the client will unload the chunk.
            // Thus we can avoid loading the chunk just for the update packet.
            if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                continue;
            }

            BlockState blockState;
            BlockEntity blockEntity = null;

            if (result.isVisible()) {
                blockState = serverLevel.getBlockState(block);

                if (blockState.hasBlockEntity()) {
                    blockEntity = serverLevel.getBlockEntity(block);
                }
            } else if (environment == Environment.NETHER) {
                blockState = Blocks.NETHERRACK.defaultBlockState();
            } else if (environment == Environment.THE_END) {
                blockState = Blocks.END_STONE.defaultBlockState();
            } else if (block.getY() < 0) {
                blockState = Blocks.DEEPSLATE.defaultBlockState();
            } else {
                blockState = Blocks.STONE.defaultBlockState();
            }

            // We can't send the packet normally (through the packet queue).
            // We bypass the packet queue since our calculations are based on the packet state (not the server state) as seen by the packet listener.
            // As described above, the packet queue could for example already contain a chunk unload packet.
            // Thus we send our packet immediately before that.
            sendPacketImmediately(player, new ClientboundBlockUpdatePacket(block, blockState));

            if (blockEntity != null) {
                Packet<ClientGamePacketListener> packet = blockEntity.getUpdatePacket();

                if (packet != null) {
                    sendPacketImmediately(player, packet);
                }
            }
        }
    }

    private static boolean sendPacketImmediately(Player player, Object packet) {
        ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;

        if (connection == null || connection.processedDisconnect) {
            return false;
        }

        Channel channel = connection.connection.channel;

        if (channel == null || !channel.isOpen()) {
            return false;
        }

        channel.writeAndFlush(packet);
        return true;
    }
}
