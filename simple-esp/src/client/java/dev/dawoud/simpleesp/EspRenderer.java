package dev.dawoud.simpleesp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds what to highlight and hands it to vanilla's gizmo system, which draws boxes and
 * lines. Gizmos marked "always on top" ignore depth, so everything shows through walls.
 *
 *  - tick():    scans for the selected blocks around you (a few times per second)
 *  - extract(): every frame, emits one box per player / entity / block plus tracer lines
 */
public final class EspRenderer {
	private static final int ENTITY_RANGE = 512;
	private static final int MAX_ENTITIES = 300;
	private static final int MAX_BLOCKS = 1500;
	private static final int MAX_BLOCK_SCAN_HITS = 50000;
	private static final int BLOCK_SCAN_INTERVAL_TICKS = 10;
	private static final long SCAN_BUDGET_NANOS = 3_000_000L; // ~3 ms of scanning per tick
	private static final int MAX_FAILURES = 3;

	private static final float BOX_LINE_WIDTH = 2.0f;
	private static final float TRACER_WIDTH = 1.5f;

	private record BlockHit(int x, int y, int z, float r, float g, float b) { }

	private static final Map<Block, float[]> BLOCK_COLORS = new HashMap<>();
	private static List<BlockHit> blockHits = List.of();
	private static int scanCountdown = 0;
	private static boolean scanActive = false;
	private static int[] scanChunkX = new int[0];
	private static int[] scanChunkZ = new int[0];
	private static int scanIndex = 0;
	private static List<BlockHit> scanHits = List.of();
	private static double scanOriginX, scanOriginY, scanOriginZ;
	private static int failures = 0;
	private static boolean disabled = false;

	private EspRenderer() { }

	public static void init() {
		LevelExtractionEvents.END_EXTRACTION.register(EspRenderer::extract);
	}

	/** Makes the next tick rescan blocks immediately (used after changing the block list). */
	public static void forceScan() {
		scanActive = false;
		scanCountdown = 0;
	}

	// ------------------------------------------------------------------ block scanning (tick)
	//
	// Works through the loaded chunks around you, nearest first, with a small time budget per
	// tick so a huge radius never freezes the game. Every 16x16x16 section keeps a palette of
	// the block types it contains, so sections that can't hold your selection are skipped
	// without looking at a single block. Only chunks the server has sent you can be scanned,
	// so the real limit is the server's view distance.

	public static void tick(Minecraft mc) {
		EspConfig cfg = EspConfig.get();
		if (mc.level == null || mc.player == null || !cfg.enabled || cfg.blockSet().isEmpty()) {
			blockHits = List.of();
			scanActive = false;
			scanCountdown = 0;
			return;
		}

		if (!scanActive) {
			if (scanCountdown-- > 0) return;
			startScan(mc.player, cfg);
		}

		long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
		Set<Block> wanted = cfg.blockSet();
		Predicate<BlockState> mightMatch = state -> wanted.contains(state.getBlock());
		while (scanIndex < scanChunkX.length && System.nanoTime() < deadline) {
			scanChunk(mc.level, scanChunkX[scanIndex], scanChunkZ[scanIndex], wanted, mightMatch);
			scanIndex++;
		}

		if (scanIndex >= scanChunkX.length) {
			finishScan();
		}
	}

	private static void startScan(LocalPlayer player, EspConfig cfg) {
		int r = cfg.radiusChunks;
		int pcx = player.blockPosition().getX() >> 4;
		int pcz = player.blockPosition().getZ() >> 4;

		List<int[]> chunks = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz <= r * r) chunks.add(new int[] {pcx + dx, pcz + dz, dx * dx + dz * dz});
			}
		}
		chunks.sort(Comparator.comparingInt(c -> c[2])); // nearest first

		scanChunkX = new int[chunks.size()];
		scanChunkZ = new int[chunks.size()];
		for (int i = 0; i < chunks.size(); i++) {
			scanChunkX[i] = chunks.get(i)[0];
			scanChunkZ[i] = chunks.get(i)[1];
		}
		scanIndex = 0;
		scanHits = new ArrayList<>();
		scanOriginX = player.getX();
		scanOriginY = player.getY();
		scanOriginZ = player.getZ();
		scanActive = true;
	}

	private static void scanChunk(ClientLevel level, int cx, int cz, Set<Block> wanted, Predicate<BlockState> mightMatch) {
		if (scanHits.size() >= MAX_BLOCK_SCAN_HITS) return;
		LevelChunk chunk = level.getChunk(cx, cz);
		LevelChunkSection[] sections = chunk.getSections();
		int minY = level.dimensionType().minY();

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir() || !section.maybeHas(mightMatch)) continue;

			int baseY = minY + (i << 4);
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						Block block = section.getBlockState(x, y, z).getBlock();
						if (wanted.contains(block)) {
							float[] c = BLOCK_COLORS.computeIfAbsent(block,
								b -> colorFor(BuiltInRegistries.BLOCK.getKey(b).toString()));
							scanHits.add(new BlockHit((cx << 4) + x, baseY + y, (cz << 4) + z, c[0], c[1], c[2]));
						}
					}
				}
			}
		}
	}

	private static void finishScan() {
		List<BlockHit> hits = scanHits;
		if (hits.size() > MAX_BLOCKS) {
			double px = scanOriginX, py = scanOriginY, pz = scanOriginZ;
			hits.sort(Comparator.comparingDouble(h -> sq(h.x + 0.5 - px) + sq(h.y + 0.5 - py) + sq(h.z + 0.5 - pz)));
			hits = new ArrayList<>(hits.subList(0, MAX_BLOCKS));
		}
		blockHits = hits;
		scanHits = List.of();
		scanActive = false;
		scanCountdown = BLOCK_SCAN_INTERVAL_TICKS;
	}

	// ------------------------------------------------------------------ per-frame drawing

	private static void extract(LevelExtractionContext context) {
		if (disabled) return;
		EspConfig cfg = EspConfig.get();
		Minecraft mc = Minecraft.getInstance();
		if (!cfg.enabled || mc.level == null || mc.player == null) return;

		try {
			emit(mc, mc.level, mc.player, cfg);
			failures = 0;
		} catch (RuntimeException e) {
			if (failures == 0) {
				SimpleEspClient.LOGGER.error("Simple ESP failed to draw a frame", e);
			}
			if (++failures >= MAX_FAILURES) {
				disabled = true;
				SimpleEspClient.LOGGER.error("Simple ESP drawing failed {} times in a row and was turned off for this session", MAX_FAILURES);
			}
		}
	}

	private static void emit(Minecraft mc, ClientLevel level, LocalPlayer self, EspConfig cfg) {
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float fillAlpha = cfg.opacityPercent / 100f;

		// Tracers start just in front of the crosshair.
		Vec3 tracerStart = self.getEyePosition(partial).add(self.getLookAngle().scale(0.5));

		// Players and selected entities
		AABB area = self.getBoundingBox().inflate(ENTITY_RANGE);
		List<Entity> nearby = level.getEntities((Entity) null, area, e -> true);
		nearby.sort(Comparator.comparingDouble(e -> self.distanceToSqr(e)));
		int count = 0;
		for (Entity e : nearby) {
			if (e == self || e.isRemoved()) continue;

			float[] color;
			if (e instanceof Player) {
				if (!cfg.players) continue;
				color = new float[] {1.0f, 0.25f, 0.25f};
			} else if (cfg.entitySet().contains(e.getType())) {
				color = colorFor(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
			} else {
				continue;
			}

			// Smooth the 20 TPS movement between ticks.
			double dx = Mth.lerp((double) partial, e.xo, e.getX()) - e.getX();
			double dy = Mth.lerp((double) partial, e.yo, e.getY()) - e.getY();
			double dz = Mth.lerp((double) partial, e.zo, e.getZ()) - e.getZ();
			AABB box = e.getBoundingBox().move(dx, dy, dz);

			Gizmos.cuboid(box, GizmoStyle.strokeAndFill(
				argb(color[0], color[1], color[2], 1f), BOX_LINE_WIDTH,
				argb(color[0], color[1], color[2], fillAlpha))).setAlwaysOnTop();

			if (cfg.tracers) {
				Gizmos.line(tracerStart, box.getCenter(),
					argb(color[0], color[1], color[2], 0.9f), TRACER_WIDTH).setAlwaysOnTop();
			}
			if (++count >= MAX_ENTITIES) break;
		}

		// Blocks found by the tick scan
		int tracerBudget = 64;
		for (BlockHit h : blockHits) {
			AABB box = new AABB(h.x, h.y, h.z, h.x + 1, h.y + 1, h.z + 1);
			Gizmos.cuboid(box, GizmoStyle.strokeAndFill(
				argb(h.r, h.g, h.b, 1f), 1.5f,
				argb(h.r, h.g, h.b, fillAlpha))).setAlwaysOnTop();

			if (cfg.blockTracers && tracerBudget-- > 0) {
				Gizmos.line(tracerStart, box.getCenter(),
					argb(h.r, h.g, h.b, 0.9f), TRACER_WIDTH).setAlwaysOnTop();
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	private static int argb(float r, float g, float b, float a) {
		return (to255(a) << 24) | (to255(r) << 16) | (to255(g) << 8) | to255(b);
	}

	private static int to255(float v) {
		return Math.max(0, Math.min(255, Math.round(v * 255f)));
	}

	private static double sq(double d) {
		return d * d;
	}

	/** Stable, bright color from an id string, so the same block/entity is always the same color. */
	static float[] colorFor(String key) {
		float h = ((key.hashCode() & 0x7fffffff) % 360) / 360f;
		float s = 0.75f, v = 1f;
		int i = (int) (h * 6);
		float f = h * 6 - i, p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
		return switch (i % 6) {
			case 0 -> new float[] {v, t, p};
			case 1 -> new float[] {q, v, p};
			case 2 -> new float[] {p, v, t};
			case 3 -> new float[] {p, q, v};
			case 4 -> new float[] {t, p, v};
			default -> new float[] {v, p, q};
		};
	}
}
