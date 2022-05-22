package gjum.minecraft.mapsync.common;

import gjum.minecraft.mapsync.common.data.ChunkTile;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;

/**
 * contains any background processes and data structures, to be able to easily tear down when leaving the dimension
 */
public class DimensionState {
	private static final Minecraft mc = Minecraft.getInstance();

	public final ResourceKey<Level> dimension;
	boolean hasShutDown = false;

	private final DimensionChunkMeta chunkMeta;
	private final RenderQueue renderQueue;

	private final HashMap<ChunkPos, byte[]> serverKnownChunkHashes = new HashMap<>();

	DimensionState(String mcServerName, ResourceKey<Level> dimension) {
		this.dimension = dimension;
		String dimensionName = dimension.location().toString();
		chunkMeta = new DimensionChunkMeta(mcServerName, dimensionName);
		renderQueue = new RenderQueue(this);
	}

	public long getChunkTimestamp(ChunkPos chunkPos) {
		return chunkMeta.getTimestamp(chunkPos);
	}

	public void setChunkTimestamp(ChunkPos chunkPos, long timestamp) {
		chunkMeta.setTimestamp(chunkPos, timestamp);
	}

	public synchronized void shutDown() {
		if (hasShutDown) return;
		hasShutDown = true;
		renderQueue.shutDown();
	}

	public void processSharedChunk(ChunkTile chunkTile) {
		if (mc.level == null) return;
		if (dimension != mc.level.dimension()) {
			shutDown(); // player entered different dimension
			return;
		}

		if (chunkTile.dimension() != dimension) {
			System.out.println("XXX Dropping chunk tile: wrong dimension "
					+ chunkTile.dimension() + " wanted " + dimension);
			return; // don't render tile to the wrong dimension
		}

		serverKnownChunkHashes.put(chunkTile.chunkPos(), chunkTile.dataHash());

		if (mc.level.getChunkSource().hasChunk(chunkTile.x(), chunkTile.z())) {
			System.out.println("XXX Dropping chunk tile: loaded in world");
			return; // don't update loaded chunks
		}

		renderQueue.renderLater(chunkTile);
	}

	public synchronized byte[] getServerKnownChunkHash(ChunkPos chunkPos) {
		return serverKnownChunkHashes.get(chunkPos);
	}

	public synchronized void setServerKnownChunkHash(ChunkPos chunkPos, byte[] hash) {
		serverKnownChunkHashes.put(chunkPos, hash);
	}
}