package com.landen.forcerun;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltinRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.StructureManager;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.util.RandomSource;

import java.util.List;

import static net.minecraft.commands.Commands.literal;

public final class ForceRunFortress implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            registerCommand(dispatcher)
        );
    }

    private static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            literal("forcerun")
                .then(literal("fortress")
                    .requires(source -> source.hasPermission(2))
                    .executes(context -> generate(context.getSource()))
                )
        );
    }

    private static int generate(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("This command must be run by a player."));
            return 0;
        }

        if (player.level().dimension() != Level.NETHER) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("You must be in the Nether."));
            return 0;
        }

        var level = player.serverLevel();
        var target = player.blockPosition();

        // Force the chunks around the target to be loaded before generation.
        ChunkPos targetChunk = new ChunkPos(target);
        level.getChunk(targetChunk.x, targetChunk.z);

        var structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Holder.Reference<Structure> fortress = structureRegistry.getOrThrow(BuiltinRegistries.createKey("fortress"));

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        BiomeSource biomeSource = generator.getBiomeSource();
        RandomState randomState = level.getChunkSource().randomState();
        StructureTemplateManager templateManager = level.getStructureManager();
        long seed = level.getSeed();

        // Vanilla's own Structure.generate creates the actual procedural fortress pieces.
        StructureStart start = Structure.generate(
            fortress,
            Level.NETHER,
            level.registryAccess(),
            generator,
            biomeSource,
            randomState,
            templateManager,
            seed,
            targetChunk,
            0,
            level,
            holder -> true
        );

        if (!start.isValid() || start.getPieces().isEmpty()) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("Vanilla fortress generation returned no pieces here."));
            return 0;
        }

        // Move the complete vanilla piece graph so its generated footprint is centered
        // around the player's exact X/Z and starts at the player's Y.
        List<StructurePiece> pieces = start.getPieces();
        BoundingBox before = start.getBoundingBox();

        int generatedCenterX = targetChunk.getMinBlockX() + 8;
        int generatedCenterZ = targetChunk.getMinBlockZ() + 8;

        int dx = target.getX() - generatedCenterX;
        int dz = target.getZ() - generatedCenterZ;
        int dy = target.getY() - before.minY();

        for (StructurePiece piece : pieces) {
            piece.move(dx, dy, dz);
        }

        // Rebuild the start after moving its real vanilla pieces.
        start = new StructureStart(
            fortress.value(),
            targetChunk,
            0,
            new PiecesContainer(pieces)
        );

        BoundingBox box = start.getBoundingBox();

        // Place the same vanilla pieces chunk-by-chunk.
        int minChunkX = box.minX() >> 4;
        int maxChunkX = box.maxX() >> 4;
        int minChunkZ = box.minZ() >> 4;
        int maxChunkZ = box.maxZ() >> 4;

        StructureManager structureManager = level.getStructureManager();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                level.getChunk(cx, cz);

                BoundingBox chunkBox = new BoundingBox(
                    cx << 4,
                    level.getMinBuildHeight(),
                    cz << 4,
                    (cx << 4) + 15,
                    level.getMaxBuildHeight() - 1,
                    (cz << 4) + 15
                );

                start.placeInChunk(
                    level,
                    structureManager,
                    generator,
                    RandomSource.create(seed ^ ChunkPos.asLong(cx, cz)),
                    chunkBox,
                    new ChunkPos(cx, cz)
                );
            }
        }

        // Register the actual vanilla StructureStart in the source chunk.
        // This is what makes StructureManager/NaturalSpawner recognize the area
        // as a minecraft:fortress instead of merely seeing placed blocks.
        LevelChunk sourceChunk = level.getChunk(targetChunk.x, targetChunk.z);
        sourceChunk.setStartForStructure(fortress.value(), start);

        // Add references from every intersecting chunk back to the source chunk.
        long sourceLong = targetChunk.toLong();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz);
                chunk.addReferenceForStructure(fortress.value(), sourceLong);
            }
        }

        source.sendSuccess(
            () -> net.minecraft.network.chat.Component.literal(
                "Generated and registered a vanilla Nether Fortress at " +
                target.getX() + ", " + target.getY() + ", " + target.getZ() + "."
            ),
            true
        );

        return 1;
    }
}
