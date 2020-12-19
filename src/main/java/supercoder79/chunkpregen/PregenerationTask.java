package supercoder79.chunkpregen;

import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.*;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.concurrent.atomic.AtomicLong;

public final class PregenerationTask {
    private static final long BATCH_SIZE = 32;
    private static final long QUEUE_THRESHOLD = 8;

    private final MinecraftServer server;
    private final ServerChunkManager chunkManager;

    private final ChunkIterator iterator;
    private final int totalCount;

    private final Object queueLock = new Object();

    private final AtomicLong queuedCount = new AtomicLong();
    private final AtomicLong okCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();

    private volatile Listener listener;
    private volatile boolean stopped;

    public PregenerationTask(ServerWorld world, int x, int z, int radius) {
        this.server = world.getServer();
        this.chunkManager = world.getChunkManager();

        this.iterator = new ChunkIterator(x, z, radius);

        int diameter = radius * 2 + 1;
        this.totalCount = diameter * diameter;
    }

    public long getOkCount() {
        return this.okCount.get();
    }

    public long getErrorCount() {
        return this.errorCount.get();
    }

    public long getTotalCount() {
        return this.totalCount;
    }

    public void run(Listener listener) {
        if (this.listener != null) {
            throw new IllegalStateException("already running!");
        }

        this.listener = listener;
        this.tryEnqueueTasks();
    }

    public void stop() {
        synchronized (this.queueLock) {
            this.stopped = true;
            this.listener = null;
        }
    }

    private void tryEnqueueTasks() {
        synchronized (this.queueLock) {
            if (this.stopped) {
                return;
            }

            long enqueueCount = BATCH_SIZE - this.queuedCount.get();
            if (enqueueCount <= 0) {
                return;
            }

            LongList chunks = this.collectChunks(enqueueCount);
            if (chunks.isEmpty()) {
                this.listener.complete(this.errorCount.get());
                this.stopped = true;
                return;
            }

            this.queuedCount.getAndAdd(chunks.size());
            this.server.submit(() -> this.enqueueChunks(chunks));
        }
    }

    private void enqueueChunks(LongList chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            long chunk = chunks.getLong(i);
            this.acquireChunk(chunk);
        }

        // tick the chunk manager to force the ChunkHolders to be created
        this.chunkManager.tick();

        ThreadedAnvilChunkStorage tacs = this.chunkManager.threadedAnvilChunkStorage;

        for (int i = 0; i < chunks.size(); i++) {
            long chunk = chunks.getLong(i);

            ChunkHolder holder = tacs.getChunkHolder(chunk);
            if (holder == null) {
                ChunkPregen.LOGGER.warn("Added ticket for chunk but it was not added! ({}; {})", ChunkPos.getPackedX(chunk), ChunkPos.getPackedZ(chunk));
                this.acceptChunkResult(chunk, ChunkHolder.UNLOADED_CHUNK);
                continue;
            }

            holder.getChunkAt(ChunkStatus.FULL, tacs).thenAccept(result -> {
                this.acceptChunkResult(chunk, result);
            });
        }
    }

    private void acceptChunkResult(long chunk, Either<Chunk, ChunkHolder.Unloaded> result) {
        this.releaseChunk(chunk);

        if (result.left().isPresent()) {
            this.okCount.getAndIncrement();
        } else {
            this.errorCount.getAndIncrement();
        }

        this.listener.update(this.okCount.get(), this.errorCount.get(), this.totalCount);

        long queuedCount = this.queuedCount.decrementAndGet();
        if (queuedCount <= QUEUE_THRESHOLD) {
            this.tryEnqueueTasks();
        }
    }

    private LongList collectChunks(long count) {
        LongList chunks = new LongArrayList((int)count);

        for (long i = 0; i < count; i++) {
            long chunk = this.iterator.next();
            if (chunk == Long.MAX_VALUE) {
                break;
            }

            chunks.add(chunk);
        }

        return chunks;
    }

    private void acquireChunk(long chunk) {
        ChunkPos pos = new ChunkPos(chunk);
        this.chunkManager.addTicket(ChunkTicketType.FORCED, pos, 0, pos);
    }

    private void releaseChunk(long chunk) {
        ChunkPos pos = new ChunkPos(chunk);
        this.chunkManager.removeTicket(ChunkTicketType.FORCED, pos, 0, pos);
    }

    public interface Listener {
        void update(long ok, long error, long total);

        void complete(long error);
    }
}
