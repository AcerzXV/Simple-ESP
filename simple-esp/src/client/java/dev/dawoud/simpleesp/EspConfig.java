package dev.dawoud.simpleesp;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

/** Saved settings. Blocks and entities are stored as registry ids, e.g. "minecraft:chest". */
public final class EspConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("simple-esp.json");
	private static EspConfig instance = new EspConfig();

	public boolean enabled = true;
	public boolean players = true;
	public boolean tracers = true;
	public boolean blockTracers = false;
	public int radiusChunks = 4;
	public int opacityPercent = 35;
	public Set<String> blocks = new LinkedHashSet<>();
	public Set<String> entities = new LinkedHashSet<>();

	// Fast lookups rebuilt from the id lists above (not saved).
	private transient Set<Block> blockSet = Set.of();
	private transient Set<EntityType<?>> entitySet = Set.of();

	public static EspConfig get() {
		return instance;
	}

	public static void load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				EspConfig loaded = GSON.fromJson(reader, EspConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (Exception e) {
				SimpleEspClient.LOGGER.warn("Could not read {}, using defaults", PATH, e);
			}
		}
		if (instance.blocks == null) instance.blocks = new LinkedHashSet<>();
		if (instance.entities == null) instance.entities = new LinkedHashSet<>();
		instance.rebuild();
	}

	public void save() {
		try (Writer writer = Files.newBufferedWriter(PATH)) {
			GSON.toJson(this, writer);
		} catch (IOException e) {
			SimpleEspClient.LOGGER.warn("Could not write {}", PATH, e);
		}
	}

	/** Re-resolves the id lists into real Block / EntityType sets. Call after editing them. */
	public void rebuild() {
		Set<Block> b = new HashSet<>();
		for (String s : blocks) {
			Identifier id = Identifier.tryParse(s);
			if (id != null) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(b::add);
		}
		Set<EntityType<?>> e = new HashSet<>();
		for (String s : entities) {
			Identifier id = Identifier.tryParse(s);
			if (id != null) BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(e::add);
		}
		blockSet = b;
		entitySet = e;
	}

	public Set<Block> blockSet() {
		return blockSet;
	}

	public Set<EntityType<?>> entitySet() {
		return entitySet;
	}

	/** Adds the id if missing, removes it if present. Returns true if it is now selected. */
	public static boolean toggle(Set<String> set, Identifier id) {
		String key = id.toString();
		if (set.remove(key)) return false;
		set.add(key);
		return true;
	}
}
