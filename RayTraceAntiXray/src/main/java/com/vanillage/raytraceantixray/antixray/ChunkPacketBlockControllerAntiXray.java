package com.vanillage.raytraceantixray.antixray;

import com.destroystokyo.paper.PaperWorldConfig;
import com.destroystokyo.paper.antixray.BitStorageReader;
import com.destroystokyo.paper.antixray.BitStorageWriter;
import com.destroystokyo.paper.antixray.ChunkPacketBlockController;
import com.destroystokyo.paper.antixray.ChunkPacketBlockControllerAntiXray.EngineMode;
import com.destroystokyo.paper.antixray.ChunkPacketInfo;
import com.vanillage.raytraceantixray.RayTraceAntiXray;
import com.vanillage.raytraceantixray.data.ChunkBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.bukkit.Bukkit;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

public final class ChunkPacketBlockControllerAntiXray extends ChunkPacketBlockController {

    private final RayTraceAntiXray plugin;
    private final Executor executor;
    private final EngineMode engineMode;
    private final int maxBlockHeight;
    private final int updateRadius;
    private final boolean usePermission;
    private final int maxRayTraceBlockCountPerChunk;
    private final BlockState[] presetBlockStates;
    private final BlockState[] presetBlockStatesFull;
    private final BlockState[] presetBlockStatesStone;
    private final BlockState[] presetBlockStatesNetherrack;
    private final BlockState[] presetBlockStatesEndStone;
    private final int[] presetBlockStateBitsGlobal;
    private final int[] presetBlockStateBitsStoneGlobal;
    private final int[] presetBlockStateBitsNetherrackGlobal;
    private final int[] presetBlockStateBitsEndStoneGlobal;
    public final boolean[] solidGlobal = new boolean[Block.BLOCK_STATE_REGISTRY.size()];
    private final boolean[] obfuscateGlobal = new boolean[Block.BLOCK_STATE_REGISTRY.size()];
    private final boolean[] traceGlobal;
    private final LevelChunkSection[] emptyNearbyChunkSections = {LevelChunk.EMPTY_SECTION, LevelChunk.EMPTY_SECTION, LevelChunk.EMPTY_SECTION, LevelChunk.EMPTY_SECTION};
    private final int maxBlockHeightUpdatePosition;

    public ChunkPacketBlockControllerAntiXray(RayTraceAntiXray plugin, int maxRayTraceBlockCountPerChunk, Iterable<? extends String> toTrace, Level level, Executor executor) {
        this.plugin = plugin;
        this.executor = executor;
        PaperWorldConfig paperWorldConfig = level.paperConfig;
        engineMode = paperWorldConfig.engineMode;
        maxBlockHeight = paperWorldConfig.maxBlockHeight >> 4 << 4;
        updateRadius = paperWorldConfig.updateRadius;
        usePermission = paperWorldConfig.usePermission;
        this.maxRayTraceBlockCountPerChunk = maxRayTraceBlockCountPerChunk;
        List<String> toObfuscate;

        if (engineMode == EngineMode.HIDE) {
            toObfuscate = paperWorldConfig.hiddenBlocks;
            presetBlockStates = null;
            presetBlockStatesFull = null;
            presetBlockStatesStone = new BlockState[]{Blocks.STONE.defaultBlockState()};
            presetBlockStatesNetherrack = new BlockState[]{Blocks.NETHERRACK.defaultBlockState()};
            presetBlockStatesEndStone = new BlockState[]{Blocks.END_STONE.defaultBlockState()};
            presetBlockStateBitsGlobal = null;
            presetBlockStateBitsStoneGlobal = new int[]{LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(Blocks.STONE.defaultBlockState())};
            presetBlockStateBitsNetherrackGlobal = new int[]{LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(Blocks.NETHERRACK.defaultBlockState())};
            presetBlockStateBitsEndStoneGlobal = new int[]{LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(Blocks.END_STONE.defaultBlockState())};
        } else {
            toObfuscate = new ArrayList<>(paperWorldConfig.replacementBlocks);
            List<BlockState> presetBlockStateList = new LinkedList<>();

            for (String id : paperWorldConfig.hiddenBlocks) {
                Block block = Registry.BLOCK.getOptional(new ResourceLocation(id)).orElse(null);

                if (block != null && !(block instanceof EntityBlock)) {
                    toObfuscate.add(id);
                    presetBlockStateList.add(block.defaultBlockState());
                }
            }

            // The doc of the LinkedHashSet(Collection<? extends E>) constructor doesn't specify that the insertion order is the predictable iteration order of the specified Collection, although it is in the implementation
            Set<BlockState> presetBlockStateSet = new LinkedHashSet<>();
            // Therefore addAll(Collection<? extends E>) is used, which guarantees this order in the doc
            presetBlockStateSet.addAll(presetBlockStateList);
            presetBlockStates = presetBlockStateSet.isEmpty() ? new BlockState[]{Blocks.DIAMOND_ORE.defaultBlockState()} : presetBlockStateSet.toArray(new BlockState[0]);
            presetBlockStatesFull = presetBlockStateSet.isEmpty() ? new BlockState[]{Blocks.DIAMOND_ORE.defaultBlockState()} : presetBlockStateList.toArray(new BlockState[0]);
            presetBlockStatesStone = null;
            presetBlockStatesNetherrack = null;
            presetBlockStatesEndStone = null;
            presetBlockStateBitsGlobal = new int[presetBlockStatesFull.length];

            for (int i = 0; i < presetBlockStatesFull.length; i++) {
                presetBlockStateBitsGlobal[i] = LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(presetBlockStatesFull[i]);
            }

            presetBlockStateBitsStoneGlobal = null;
            presetBlockStateBitsNetherrackGlobal = null;
            presetBlockStateBitsEndStoneGlobal = null;
        }

        for (String id : toObfuscate) {
            Block block = Registry.BLOCK.getOptional(new ResourceLocation(id)).orElse(null);

            // Don't obfuscate air because air causes unnecessary block updates and causes block updates to fail in the void
            if (block != null && !block.defaultBlockState().isAir()) {
                // Replace all block states of a specified block
                for (BlockState blockState : block.getStateDefinition().getPossibleStates()) {
                    obfuscateGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(blockState)] = true;
                }
            }
        }

        if (toTrace == null) {
            traceGlobal = obfuscateGlobal;
        } else {
            traceGlobal = new boolean[Block.BLOCK_STATE_REGISTRY.size()];

            for (String id : toTrace) {
                Block block = Registry.BLOCK.getOptional(new ResourceLocation(id)).orElse(null);

                // Don't obfuscate air because air causes unnecessary block updates and causes block updates to fail in the void
                if (block != null && !block.defaultBlockState().isAir()) {
                    // Replace all block states of a specified block
                    for (BlockState blockState : block.getStateDefinition().getPossibleStates()) {
                        traceGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(blockState)] = true;
                        obfuscateGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(blockState)] = true;
                    }
                }
            }
        }

        EmptyLevelChunk emptyChunk = new EmptyLevelChunk(level, new ChunkPos(0, 0));
        BlockPos zeroPos = new BlockPos(0, 0, 0);

        for (int i = 0; i < solidGlobal.length; i++) {
            BlockState blockState = LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.valueFor(i);

            if (blockState != null) {
                solidGlobal[i] = blockState.isRedstoneConductor(emptyChunk, zeroPos)
                    && blockState.getBlock() != Blocks.SPAWNER && blockState.getBlock() != Blocks.BARRIER && blockState.getBlock() != Blocks.SHULKER_BOX && blockState.getBlock() != Blocks.SLIME_BLOCK || paperWorldConfig.lavaObscures && blockState == Blocks.LAVA.defaultBlockState();
                // Comparing blockState == Blocks.LAVA.defaultBlockState() instead of blockState.getBlock() == Blocks.LAVA ensures that only "stationary lava" is used
                // shulker box checks TE.
            }
        }

        maxBlockHeightUpdatePosition = maxBlockHeight + updateRadius - 1;
    }

    private int getPresetBlockStatesFullLength() {
        return engineMode == EngineMode.HIDE ? 1 : presetBlockStatesFull.length;
    }

    @Override
    public BlockState[] getPresetBlockStates(Level level, ChunkAccess chunk, LevelChunkSection chunkSection, boolean initializeBlocks) {
        // Return the block states to be added to the paletted containers so that they can be used for obfuscation
        if (chunkSection.bottomBlockY() < maxBlockHeight) {
            if (engineMode == EngineMode.HIDE) {
                switch (level.getWorld().getEnvironment()) {
                    case NETHER:
                        return presetBlockStatesNetherrack;
                    case THE_END:
                        return presetBlockStatesEndStone;
                    default:
                        return presetBlockStatesStone;
                }
            }

            return presetBlockStates;
        }

        return null;
    }

    @Override
    public boolean shouldModify(ServerPlayer player, LevelChunk chunk) {
        return !usePermission || !player.getBukkitEntity().hasPermission("paper.antixray.bypass");
    }

    @Override
    public ChunkPacketInfoAntiXray getChunkPacketInfo(ClientboundLevelChunkPacket chunkPacket, LevelChunk chunk) {
        // Return a new instance to collect data and objects in the right state while creating the chunk packet for thread safe access later
        return new ChunkPacketInfoAntiXray(chunkPacket, chunk, this);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void modifyBlocks(ClientboundLevelChunkPacket chunkPacket, ChunkPacketInfo<BlockState> chunkPacketInfo) {
        if (!(chunkPacketInfo instanceof ChunkPacketInfoAntiXray)) {
            chunkPacket.setReady(true);
            return;
        }

        if (!Bukkit.isPrimaryThread()) {
            // Plugins?
            MinecraftServer.getServer().scheduleOnMain(() -> modifyBlocks(chunkPacket, chunkPacketInfo));
            return;
        }

        LevelChunk chunk = chunkPacketInfo.getChunk();
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        Level level = chunk.level;
        ((ChunkPacketInfoAntiXray) chunkPacketInfo).setNearbyChunks(level.getChunkIfLoaded(x - 1, z), level.getChunkIfLoaded(x + 1, z), level.getChunkIfLoaded(x, z - 1), level.getChunkIfLoaded(x, z + 1));
        executor.execute((Runnable) chunkPacketInfo);
    }

    // Actually these fields should be variables inside the obfuscate method but in sync mode or with SingleThreadExecutor in async mode it's okay (even without ThreadLocal)
    // If an ExecutorService with multiple threads is used, ThreadLocal must be used here
    private final ThreadLocal<int[]> presetBlockStateBits = ThreadLocal.withInitial(() -> new int[getPresetBlockStatesFullLength()]);
    private static final ThreadLocal<boolean[]> solid = ThreadLocal.withInitial(() -> new boolean[Block.BLOCK_STATE_REGISTRY.size()]);
    private static final ThreadLocal<boolean[]> obfuscate = ThreadLocal.withInitial(() -> new boolean[Block.BLOCK_STATE_REGISTRY.size()]);
    private static final ThreadLocal<boolean[]> trace = ThreadLocal.withInitial(() -> new boolean[Block.BLOCK_STATE_REGISTRY.size()]);
    // These boolean arrays represent chunk layers, true means don't obfuscate, false means obfuscate
    private static final ThreadLocal<boolean[][]> current = ThreadLocal.withInitial(() -> new boolean[16][16]);
    private static final ThreadLocal<boolean[][]> next = ThreadLocal.withInitial(() -> new boolean[16][16]);
    private static final ThreadLocal<boolean[][]> nextNext = ThreadLocal.withInitial(() -> new boolean[16][16]);
    private static final ThreadLocal<boolean[][]> traceCache = ThreadLocal.withInitial(() -> new boolean[16][16]);

    public void obfuscate(ChunkPacketInfoAntiXray chunkPacketInfoAntiXray) {
        int[] presetBlockStateBits = this.presetBlockStateBits.get();
        boolean[] solid = ChunkPacketBlockControllerAntiXray.solid.get();
        boolean[] obfuscate = ChunkPacketBlockControllerAntiXray.obfuscate.get();
        boolean[] trace = traceGlobal == obfuscateGlobal ? obfuscate : ChunkPacketBlockControllerAntiXray.trace.get();
        boolean[][] current = ChunkPacketBlockControllerAntiXray.current.get();
        boolean[][] next = ChunkPacketBlockControllerAntiXray.next.get();
        boolean[][] nextNext = ChunkPacketBlockControllerAntiXray.nextNext.get();
        boolean[][] traceCache = ChunkPacketBlockControllerAntiXray.traceCache.get();
        // bitStorageReader, bitStorageWriter and nearbyChunkSections could also be reused (with ThreadLocal if necessary) but it's not worth it
        BitStorageReader bitStorageReader = new BitStorageReader();
        BitStorageWriter bitStorageWriter = new BitStorageWriter();
        LevelChunkSection[] nearbyChunkSections = new LevelChunkSection[4];
        LevelChunk chunk = chunkPacketInfoAntiXray.getChunk();
        Level level = chunk.level;
        int maxChunkSectionIndex = Math.min((maxBlockHeight >> 4) - chunk.getMinSection(), chunk.getSectionsCount() - 1);
        boolean[] solidTemp = null;
        boolean[] obfuscateTemp = null;
        boolean[] traceTemp = null;
        bitStorageReader.setBuffer(chunkPacketInfoAntiXray.getBuffer());
        bitStorageWriter.setBuffer(chunkPacketInfoAntiXray.getBuffer());
        int numberOfBlocks = presetBlockStateBits.length;
        // Keep the lambda expressions as simple as possible. They are used very frequently.
        IntSupplier random = numberOfBlocks == 1 ? (() -> 0) : new IntSupplier() {
            private int state;

            {
                while ((state = ThreadLocalRandom.current().nextInt()) == 0) ;
            }

            @Override
            public int getAsInt() {
                // https://en.wikipedia.org/wiki/Xorshift
                state ^= state << 13;
                state ^= state >>> 17;
                state ^= state << 5;
                // https://www.pcg-random.org/posts/bounded-rands.html
                return (int) ((Integer.toUnsignedLong(state) * numberOfBlocks) >>> 32);
            }
        };
        Collection<BlockPos> blocks = new HashSet<>();

        for (int chunkSectionIndex = 0; chunkSectionIndex <= maxChunkSectionIndex; chunkSectionIndex++) {
            if (chunkPacketInfoAntiXray.isWritten(chunkSectionIndex) && chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex) != null) {
                int[] presetBlockStateBitsTemp;

                if (chunkPacketInfoAntiXray.getPalette(chunkSectionIndex) == LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE) {
                    if (engineMode == EngineMode.HIDE) {
                        switch (level.getWorld().getEnvironment()) {
                            case NETHER:
                                presetBlockStateBitsTemp = presetBlockStateBitsNetherrackGlobal;
                                break;
                            case THE_END:
                                presetBlockStateBitsTemp = presetBlockStateBitsEndStoneGlobal;
                                break;
                            default:
                                presetBlockStateBitsTemp = presetBlockStateBitsStoneGlobal;
                        }
                    } else {
                        presetBlockStateBitsTemp = presetBlockStateBitsGlobal;
                    }
                } else {
                    // If it's presetBlockStates, use this.presetBlockStatesFull instead
                    BlockState[] presetBlockStatesFull = chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex) == presetBlockStates ? this.presetBlockStatesFull : chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex);
                    presetBlockStateBitsTemp = presetBlockStateBits;

                    for (int i = 0; i < presetBlockStateBitsTemp.length; i++) {
                        presetBlockStateBitsTemp[i] = chunkPacketInfoAntiXray.getPalette(chunkSectionIndex).idFor(presetBlockStatesFull[i]);
                    }
                }

                bitStorageWriter.setIndex(chunkPacketInfoAntiXray.getIndex(chunkSectionIndex));

                // Check if the chunk section below was not obfuscated
                if (chunkSectionIndex == 0 || !chunkPacketInfoAntiXray.isWritten(chunkSectionIndex - 1) || chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex - 1) == null) {
                    // If so, initialize some stuff
                    bitStorageReader.setBits(chunkPacketInfoAntiXray.getBits(chunkSectionIndex));
                    bitStorageReader.setIndex(chunkPacketInfoAntiXray.getIndex(chunkSectionIndex));
                    solidTemp = readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex), solid, solidGlobal);
                    obfuscateTemp = readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex), obfuscate, obfuscateGlobal);
                    traceTemp = trace == obfuscate ? obfuscateTemp : readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex), trace, traceGlobal);
                    // Read the blocks of the upper layer of the chunk section below if it exists
                    LevelChunkSection belowChunkSection = null;
                    boolean skipFirstLayer = chunkSectionIndex == 0 || (belowChunkSection = chunk.getSections()[chunkSectionIndex - 1]) == LevelChunk.EMPTY_SECTION;

                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            current[z][x] = true;
                            next[z][x] = skipFirstLayer || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(belowChunkSection.getBlockState(x, 15, z))];
                            traceCache[z][x] = false;
                        }
                    }

                    // Abuse the obfuscateLayer method to read the blocks of the first layer of the current chunk section
                    bitStorageWriter.setBits(0);
                    obfuscateLayer(chunk.getMinSection(), chunkSectionIndex, -1, bitStorageReader, bitStorageWriter, solidTemp, obfuscateTemp, traceTemp, presetBlockStateBitsTemp, current, next, nextNext, traceCache, emptyNearbyChunkSections, random, blocks);
                }

                bitStorageWriter.setBits(chunkPacketInfoAntiXray.getBits(chunkSectionIndex));
                nearbyChunkSections[0] = chunkPacketInfoAntiXray.getNearbyChunks()[0] == null ? LevelChunk.EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[0].getSections()[chunkSectionIndex];
                nearbyChunkSections[1] = chunkPacketInfoAntiXray.getNearbyChunks()[1] == null ? LevelChunk.EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[1].getSections()[chunkSectionIndex];
                nearbyChunkSections[2] = chunkPacketInfoAntiXray.getNearbyChunks()[2] == null ? LevelChunk.EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[2].getSections()[chunkSectionIndex];
                nearbyChunkSections[3] = chunkPacketInfoAntiXray.getNearbyChunks()[3] == null ? LevelChunk.EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[3].getSections()[chunkSectionIndex];

                // Obfuscate all layers of the current chunk section except the upper one
                for (int y = 0; y < 15; y++) {
                    boolean[][] temp = current;
                    current = next;
                    next = nextNext;
                    nextNext = temp;
                    obfuscateLayer(chunk.getMinSection(), chunkSectionIndex, y, bitStorageReader, bitStorageWriter, solidTemp, obfuscateTemp, traceTemp, presetBlockStateBitsTemp, current, next, nextNext, traceCache, nearbyChunkSections, random, blocks);
                }

                // Check if the chunk section above doesn't need obfuscation
                if (chunkSectionIndex == maxChunkSectionIndex || !chunkPacketInfoAntiXray.isWritten(chunkSectionIndex + 1) || chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex + 1) == null) {
                    // If so, obfuscate the upper layer of the current chunk section by reading blocks of the first layer from the chunk section above if it exists
                    LevelChunkSection aboveChunkSection = chunkSectionIndex == chunk.getSectionsCount() - 1 ? LevelChunk.EMPTY_SECTION : chunk.getSections()[chunkSectionIndex + 1];
                    boolean[][] temp = current;
                    current = next;
                    next = nextNext;
                    nextNext = temp;

                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (aboveChunkSection == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(aboveChunkSection.getBlockState(x, 0, z))]) {
                                current[z][x] = true;
                            }
                        }
                    }

                    // There is nothing to read anymore
                    bitStorageReader.setBits(0);
                    solid[0] = true;
                    obfuscateLayer(chunk.getMinSection(), chunkSectionIndex, 15, bitStorageReader, bitStorageWriter, solid, obfuscateTemp, traceTemp, presetBlockStateBitsTemp, current, next, nextNext, traceCache, nearbyChunkSections, random, blocks);
                } else {
                    // If not, initialize the reader and other stuff for the chunk section above to obfuscate the upper layer of the current chunk section
                    bitStorageReader.setBits(chunkPacketInfoAntiXray.getBits(chunkSectionIndex + 1));
                    bitStorageReader.setIndex(chunkPacketInfoAntiXray.getIndex(chunkSectionIndex + 1));
                    solidTemp = readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex + 1), solid, solidGlobal);
                    obfuscateTemp = readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex + 1), obfuscate, obfuscateGlobal);
                    traceTemp = trace == obfuscate ? obfuscateTemp : readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex + 1), trace, traceGlobal);
                    boolean[][] temp = current;
                    current = next;
                    next = nextNext;
                    nextNext = temp;
                    obfuscateLayer(chunk.getMinSection(), chunkSectionIndex, 15, bitStorageReader, bitStorageWriter, solidTemp, obfuscateTemp, traceTemp, presetBlockStateBitsTemp, current, next, nextNext, traceCache, nearbyChunkSections, random, blocks);
                }

                bitStorageWriter.flush();
            }
        }

        if (plugin.isRunning()) {
            plugin.getPacketChunkBlocksCache().put(chunkPacketInfoAntiXray.getChunkPacket(), new ChunkBlocks(chunkPacketInfoAntiXray.getChunk(), blocks));
        }

        chunkPacketInfoAntiXray.getChunkPacket().setReady(true);
    }

    private void obfuscateLayer(int minSection, int chunkSectionIndex, int y, BitStorageReader bitStorageReader, BitStorageWriter bitStorageWriter, boolean[] solid, boolean[] obfuscate, boolean[] trace, int[] presetBlockStateBits, boolean[][] current, boolean[][] next, boolean[][] nextNext, boolean[][] traceCache, LevelChunkSection[] nearbyChunkSections, IntSupplier random, Collection<? super BlockPos> blocks) {
        int realY = (chunkSectionIndex + minSection << 4) + y;
        // First block of first line
        int bits = bitStorageReader.read();

        if (nextNext[0][0] = !solid[bits]) {
            if (traceCache[0][0] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                blocks.add(new BlockPos(0, realY, 0));
            } else {
                bitStorageWriter.skip();
            }

            next[0][1] = true;
            next[1][0] = true;
        } else {
            if (current[0][0] || nearbyChunkSections[2] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[2].getBlockState(0, y, 15))] || nearbyChunkSections[0] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[0].getBlockState(15, y, 0))]) {
                if (traceCache[0][0] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                    blocks.add(new BlockPos(0, realY, 0));
                } else {
                    bitStorageWriter.skip();
                }
            } else {
                bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Not exposed to air
            }
        }

        if (trace[bits]) {
            traceCache[0][0] = true;
        } else {
            traceCache[0][0] = false;

            if (!obfuscate[bits]) {
                next[0][0] = true;
            }
        }

        // First line
        for (int x = 1; x < 15; x++) {
            bits = bitStorageReader.read();

            if (nextNext[0][x] = !solid[bits]) {
                if (traceCache[0][x] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                    blocks.add(new BlockPos(x, realY, 0));
                } else {
                    bitStorageWriter.skip();
                }

                next[0][x - 1] = true;
                next[0][x + 1] = true;
                next[1][x] = true;
            } else {
                if (current[0][x] || nearbyChunkSections[2] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[2].getBlockState(x, y, 15))]) {
                    if (traceCache[0][x] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                        bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                        blocks.add(new BlockPos(x, realY, 0));
                    } else {
                        bitStorageWriter.skip();
                    }
                } else {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Not exposed to air
                }
            }

            if (trace[bits]) {
                traceCache[0][x] = true;
            } else {
                traceCache[0][x] = false;

                if (!obfuscate[bits]) {
                    next[0][x] = true;
                }
            }
        }

        // Last block of first line
        bits = bitStorageReader.read();

        if (nextNext[0][15] = !solid[bits]) {
            if (traceCache[0][15] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                blocks.add(new BlockPos(15, realY, 0));
            } else {
                bitStorageWriter.skip();
            }

            next[0][14] = true;
            next[1][15] = true;
        } else {
            if (current[0][15] || nearbyChunkSections[2] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[2].getBlockState(15, y, 15))] || nearbyChunkSections[1] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[1].getBlockState(0, y, 0))]) {
                if (traceCache[0][15] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                    blocks.add(new BlockPos(15, realY, 0));
                } else {
                    bitStorageWriter.skip();
                }
            } else {
                bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Not exposed to air
            }
        }

        if (trace[bits]) {
            traceCache[0][15] = true;
        } else {
            traceCache[0][15] = false;

            if (!obfuscate[bits]) {
                next[0][15] = true;
            }
        }

        // All inner lines
        for (int z = 1; z < 15; z++) {
            // First block
            bits = bitStorageReader.read();

            if (nextNext[z][0] = !solid[bits]) {
                if (traceCache[z][0] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                    blocks.add(new BlockPos(0, realY, z));
                } else {
                    bitStorageWriter.skip();
                }

                next[z][1] = true;
                next[z - 1][0] = true;
                next[z + 1][0] = true;
            } else {
                if (current[z][0] || nearbyChunkSections[0] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[0].getBlockState(15, y, z))]) {
                    if (traceCache[z][0] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                        bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                        blocks.add(new BlockPos(0, realY, z));
                    } else {
                        bitStorageWriter.skip();
                    }
                } else {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Not exposed to air
                }
            }

            if (trace[bits]) {
                traceCache[z][0] = true;
            } else {
                traceCache[z][0] = false;

                if (!obfuscate[bits]) {
                    next[z][0] = true;
                }
            }

            // All inner blocks
            for (int x = 1; x < 15; x++) {
                bits = bitStorageReader.read();

                if (nextNext[z][x] = !solid[bits]) {
                    if (traceCache[z][x] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                        bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                        blocks.add(new BlockPos(x, realY, z));
                    } else {
                        bitStorageWriter.skip();
                    }

                    next[z][x - 1] = true;
                    next[z][x + 1] = true;
                    next[z - 1][x] = true;
                    next[z + 1][x] = true;
                } else {
                    if (current[z][x]) {
                        if (traceCache[z][x] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                            bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                            blocks.add(new BlockPos(x, realY, z));
                        } else {
                            bitStorageWriter.skip();
                        }
                    } else {
                        bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Not exposed to air
                    }
                }

                if (trace[bits]) {
                    traceCache[z][x] = true;
                } else {
                    traceCache[z][x] = false;

                    if (!obfuscate[bits]) {
                        next[z][x] = true;
                    }
                }
            }

            // Last block
            bits = bitStorageReader.read();

            if (nextNext[z][15] = !solid[bits]) {
                if (traceCache[z][15] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                    blocks.add(new BlockPos(15, realY, z));
                } else {
                    bitStorageWriter.skip();
                }

                next[z][14] = true;
                next[z - 1][15] = true;
                next[z + 1][15] = true;
            } else {
                if (current[z][15] || nearbyChunkSections[1] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[1].getBlockState(0, y, z))]) {
                    if (traceCache[z][15] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                        bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                        blocks.add(new BlockPos(15, realY, z));
                    } else {
                        bitStorageWriter.skip();
                    }
                } else {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Not exposed to air
                }
            }

            if (trace[bits]) {
                traceCache[z][15] = true;
            } else {
                traceCache[z][15] = false;

                if (!obfuscate[bits]) {
                    next[z][15] = true;
                }
            }
        }

        // First block of last line
        bits = bitStorageReader.read();

        if (nextNext[15][0] = !solid[bits]) {
            if (traceCache[15][0] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                blocks.add(new BlockPos(0, realY, 15));
            } else {
                bitStorageWriter.skip();
            }

            next[15][1] = true;
            next[14][0] = true;
        } else {
            if (current[15][0] || nearbyChunkSections[3] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[3].getBlockState(0, y, 0))] || nearbyChunkSections[0] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[0].getBlockState(15, y, 15))]) {
                if (traceCache[15][0] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                    blocks.add(new BlockPos(0, realY, 15));
                } else {
                    bitStorageWriter.skip();
                }
            } else {
                bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Not exposed to air
            }
        }

        if (trace[bits]) {
            traceCache[15][0] = true;
        } else {
            traceCache[15][0] = false;

            if (!obfuscate[bits]) {
                next[15][0] = true;
            }
        }

        // Last line
        for (int x = 1; x < 15; x++) {
            bits = bitStorageReader.read();

            if (nextNext[15][x] = !solid[bits]) {
                if (traceCache[15][x] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                    blocks.add(new BlockPos(x, realY, 15));
                } else {
                    bitStorageWriter.skip();
                }

                next[15][x - 1] = true;
                next[15][x + 1] = true;
                next[14][x] = true;
            } else {
                if (current[15][x] || nearbyChunkSections[3] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[3].getBlockState(x, y, 0))]) {
                    if (traceCache[15][x] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                        bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                        blocks.add(new BlockPos(x, realY, 15));
                    } else {
                        bitStorageWriter.skip();
                    }
                } else {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Not exposed to air
                }
            }

            if (trace[bits]) {
                traceCache[15][x] = true;
            } else {
                traceCache[15][x] = false;

                if (!obfuscate[bits]) {
                    next[15][x] = true;
                }
            }
        }

        // Last block of last line
        bits = bitStorageReader.read();

        if (nextNext[15][15] = !solid[bits]) {
            if (traceCache[15][15] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                blocks.add(new BlockPos(15, realY, 15));
            } else {
                bitStorageWriter.skip();
            }

            next[15][14] = true;
            next[14][15] = true;
        } else {
            if (current[15][15] || nearbyChunkSections[3] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[3].getBlockState(15, y, 0))] || nearbyChunkSections[1] == LevelChunk.EMPTY_SECTION || !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(nearbyChunkSections[1].getBlockState(0, y, 15))]) {
                if (traceCache[15][15] && blocks.size() < maxRayTraceBlockCountPerChunk) {
                    bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Exposed to air
                    blocks.add(new BlockPos(15, realY, 15));
                } else {
                    bitStorageWriter.skip();
                }
            } else {
                bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]); // Not exposed to air
            }
        }

        if (trace[bits]) {
            traceCache[15][15] = true;
        } else {
            traceCache[15][15] = false;

            if (!obfuscate[bits]) {
                next[15][15] = true;
            }
        }
    }

    private boolean[] readPalette(Palette<BlockState> palette, boolean[] temp, boolean[] global) {
        if (palette == LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE) {
            return global;
        }

        BlockState blockState;

        for (int i = 0; (blockState = palette.valueFor(i)) != null; i++) {
            temp[i] = global[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(blockState)];
        }

        return temp;
    }

    @Override
    public void onBlockChange(Level level, BlockPos blockPos, BlockState newBlockState, BlockState oldBlockState, int flags, int maxUpdateDepth) {
        if (oldBlockState != null && solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(oldBlockState)] && !solidGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(newBlockState)] && blockPos.getY() <= maxBlockHeightUpdatePosition) {
            updateNearbyBlocks(level, blockPos);
        }
    }

    @Override
    public void onPlayerLeftClickBlock(ServerPlayerGameMode serverPlayerGameMode, BlockPos blockPos, ServerboundPlayerActionPacket.Action action, Direction direction, int worldHeight) {
        if (blockPos.getY() <= maxBlockHeightUpdatePosition) {
            updateNearbyBlocks(serverPlayerGameMode.level, blockPos);
        }
    }

    private void updateNearbyBlocks(Level level, BlockPos blockPos) {
        if (updateRadius >= 2) {
            BlockPos temp = blockPos.west();
            updateBlock(level, temp);
            updateBlock(level, temp.west());
            updateBlock(level, temp.below());
            updateBlock(level, temp.above());
            updateBlock(level, temp.north());
            updateBlock(level, temp.south());
            updateBlock(level, temp = blockPos.east());
            updateBlock(level, temp.east());
            updateBlock(level, temp.below());
            updateBlock(level, temp.above());
            updateBlock(level, temp.north());
            updateBlock(level, temp.south());
            updateBlock(level, temp = blockPos.below());
            updateBlock(level, temp.below());
            updateBlock(level, temp.north());
            updateBlock(level, temp.south());
            updateBlock(level, temp = blockPos.above());
            updateBlock(level, temp.above());
            updateBlock(level, temp.north());
            updateBlock(level, temp.south());
            updateBlock(level, temp = blockPos.north());
            updateBlock(level, temp.north());
            updateBlock(level, temp = blockPos.south());
            updateBlock(level, temp.south());
        } else if (updateRadius == 1) {
            updateBlock(level, blockPos.west());
            updateBlock(level, blockPos.east());
            updateBlock(level, blockPos.below());
            updateBlock(level, blockPos.above());
            updateBlock(level, blockPos.north());
            updateBlock(level, blockPos.south());
        } else {
            // Do nothing if updateRadius <= 0 (test mode)
        }
    }

    public void updateBlock(Level level, BlockPos blockPos) {
        BlockState blockState = level.getTypeIfLoaded(blockPos);

        if (blockState != null && obfuscateGlobal[LevelChunkSection.GLOBAL_BLOCKSTATE_PALETTE.idFor(blockState)]) {
            ((ServerLevel) level).getChunkSource().blockChanged(blockPos);
        }
    }
}