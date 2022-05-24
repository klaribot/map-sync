package gjum.minecraft.mapsync.common;

import com.mojang.blaze3d.platform.InputConstants;
import gjum.minecraft.mapsync.common.data.ChunkTile;
import gjum.minecraft.mapsync.common.net.TcpClient;
import gjum.minecraft.mapsync.common.net.packet.ChunkTilePacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

import static gjum.minecraft.mapsync.common.Cartography.chunkTileFromLevel;

public abstract class MapSyncMod {
	public static final String VERSION = "1.0.0";

	private static final Minecraft mc = Minecraft.getInstance();

	public static final Logger logger = LogManager.getLogger(MapSyncMod.class);

	public static MapSyncMod INSTANCE;

	public static MapSyncMod getMod() {
		return INSTANCE;
	}

	private static final KeyMapping openGuiKey = new KeyMapping(
			"key.map-sync.openGui",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_COMMA,
			"category.map-sync"
	);

	private @Nullable TcpClient syncClient;

	/**
	 * Tracks state and render thread for current mc dimension.
	 * Never access this directly; always go through `getDimensionState()`.
	 */
	private @Nullable DimensionState dimensionState;

	public MapSyncMod() {
		if (INSTANCE != null) throw new IllegalStateException("Constructor called twice");
		INSTANCE = this;
	}

	/**
	 * for example: 1.0.0+forge
	 */
	public abstract String getVersion();

	public abstract void registerKeyBinding(KeyMapping mapping);

	public void init() {
		registerKeyBinding(openGuiKey);
	}

	public void handleTick() {
		while (openGuiKey.consumeClick()) {
			// XXX handle key press
		}
	}

	public void handleConnectedToServer(ClientboundLoginPacket packet) {
		final ServerData currentServer = Minecraft.getInstance().getCurrentServer();
		if (currentServer == null) return;
		String gameAddress = currentServer.ip;

		@Nullable String syncServerAddress = "localhost:12312"; // XXX

		if (syncClient != null) {
			// avoid reconnecting to same sync server, to keep shared state (expensive to resync)
			if (!syncClient.gameAddress.equals(gameAddress)
					|| !syncClient.address.equals(syncServerAddress)
			) {
				debugLog("Disconnecting tcp; different address");
				syncClient.shutDown();
				syncClient = null;
				shutDownDimensionState();
			} else if (syncClient.isConnected()) {
				debugLog("Reusing existing tcp");
				// keep using existing connection; this retains shared state
				syncClient.autoReconnect = true;
			}
		}

		if (syncClient == null || syncClient.isShutDown) {
			syncClient = new TcpClient(syncServerAddress, gameAddress);
			shutDownDimensionState();
		}
	}

	public void handleDisconnectedFromMcServer() {
		if (syncClient != null) {
			// stay connected (to retain shared state), but don't auto reconnect if the connection is lost
			syncClient.autoReconnect = false;
			// TODO tell server our dimension is null, so it doesn't send full chunks that we can't use
		}
	}

	public void handleRespawn(ClientboundRespawnPacket packet) {
		debugLog("handleRespawn");
		// TODO handle dimensions correctly
		// shutDownDimensionState();
	}

	/**
	 * for current dimension
	 */
	public @NotNull DimensionState getDimensionState() {
		if (mc.level == null)
			throw new Error("Can't map while not in a dimension");
		if (syncClient == null)
			throw new Error("Can't map while not connected to sync server");
		if (dimensionState != null && dimensionState.dimension != mc.level.dimension()) {
			shutDownDimensionState();
		}
		if (dimensionState == null || dimensionState.hasShutDown) {
			dimensionState = new DimensionState(syncClient.gameAddress, mc.level.dimension());
		}
		return dimensionState;
	}

	private void shutDownDimensionState() {
		if (dimensionState != null) {
			dimensionState.shutDown();
			dimensionState = null;
		}
	}

	/**
	 * an entire chunk was received from the mc server;
	 * send it to the map data server right away.
	 */
	public void handleMcFullChunk(int cx, int cz) {
		if (mc.level == null || syncClient == null) return;
		// TODO disable in nether (no meaningful "surface layer")

		var chunkTile = chunkTileFromLevel(mc.level, cx, cz);

		// TODO handle journeymap skipping chunks due to rate limiting - probably need mixin on render function
		if (RenderQueue.areAllMapModsMapping()) {
			getDimensionState().setChunkTimestamp(chunkTile.chunkPos(), chunkTile.timestamp());
		}

		sendChunkTileToMapDataServer(chunkTile);
	}

	/**
	 * part of a chunk changed, and the chunk is likely to change again soon,
	 * so a ChunkTile update is queued, instead of updating instantly.
	 */
	public void handleMcChunkPartialChange(int cx, int cz) {
		// TODO update ChunkTile in a second or so; remember dimension in case it changes til then
	}

	/**
	 * if the server already has the chunk (same hash), the chunk is dropped.
	 */
	private void sendChunkTileToMapDataServer(ChunkTile chunkTile) {
		if (syncClient == null) return;
		var serverKnownHash = getDimensionState().getServerKnownChunkHash(chunkTile.chunkPos());
		if (Arrays.equals(chunkTile.dataHash(), serverKnownHash)) {
			debugLog("server already has chunk (hash) " + chunkTile.chunkPos());
			return; // server already has this chunk
		}

		syncClient.send(new ChunkTilePacket(chunkTile));

		// assume packet will reach server eventually
		getDimensionState().setServerKnownChunkHash(chunkTile.chunkPos(), chunkTile.dataHash());
	}

	public void handleSyncServerEncryptionSuccess() {
		debugLog("tcp encrypted");
	}

	public void handleSharedChunk(ChunkTile chunkTile) {
		getDimensionState().processSharedChunk(chunkTile);
	}

	public static void debugLog(String msg) {
		// we could also make use of slf4j's debug() but I don't know how to reconfigure that at runtime based on globalConfig
			logger.info(msg);
	}
}
