package dev.dawoud.simpleesp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

/**
 * The settings menu. Top: switches. Middle: Blocks / Entities tabs with a search box and a
 * paged list - click any entry to add or remove it from the ESP.
 */
public class EspScreen extends Screen {
	private enum Tab { BLOCKS, ENTITIES }

	private static final int[] RADIUS_OPTIONS = {2, 4, 8, 16, 24, 32};
	private static final int[] OPACITY_OPTIONS = {15, 25, 35, 50, 70};

	private final EspConfig cfg = EspConfig.get();
	private final List<Identifier> allBlocks = new ArrayList<>();
	private final List<Identifier> allEntities = new ArrayList<>();
	private final List<Button> rows = new ArrayList<>();

	private Tab tab = Tab.BLOCKS;
	private String query = "";
	private int page = 0;
	private int rowCount = 5;
	private List<Identifier> filtered = List.of();

	private Button blocksTab;
	private Button entitiesTab;
	private Button prevButton;
	private Button nextButton;
	private EditBox search;

	public EspScreen() {
		super(Component.literal("Simple ESP"));
		for (Block b : BuiltInRegistries.BLOCK) {
			if (!b.defaultBlockState().isAir()) {
				allBlocks.add(BuiltInRegistries.BLOCK.getKey(b));
			}
		}
		for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
			allEntities.add(BuiltInRegistries.ENTITY_TYPE.getKey(t));
		}
		allBlocks.sort(Comparator.comparing(Identifier::toString));
		allEntities.sort(Comparator.comparing(Identifier::toString));
	}

	@Override
	protected void init() {
		rows.clear();
		int cx = this.width / 2;
		int x0 = cx - 154;

		// Row 1: switches
		this.addRenderableWidget(toggle(x0, 24, 100, "ESP", () -> cfg.enabled, v -> cfg.enabled = v));
		this.addRenderableWidget(toggle(x0 + 104, 24, 100, "Players", () -> cfg.players, v -> cfg.players = v));
		this.addRenderableWidget(toggle(x0 + 208, 24, 100, "Tracers", () -> cfg.tracers, v -> cfg.tracers = v));

		// Row 2: numbers
		this.addRenderableWidget(toggle(x0, 48, 100, "Block lines", () -> cfg.blockTracers, v -> cfg.blockTracers = v));
		this.addRenderableWidget(cycle(x0 + 104, 48, 100, "Radius", " ch", RADIUS_OPTIONS,
			() -> cfg.radiusChunks, v -> cfg.radiusChunks = v));
		this.addRenderableWidget(cycle(x0 + 208, 48, 100, "Fill", "%", OPACITY_OPTIONS,
			() -> cfg.opacityPercent, v -> cfg.opacityPercent = v));

		// Row 3: tabs + search
		blocksTab = Button.builder(Component.literal("Blocks"), b -> switchTab(Tab.BLOCKS))
			.bounds(x0, 72, 60, 20).build();
		entitiesTab = Button.builder(Component.literal("Entities"), b -> switchTab(Tab.ENTITIES))
			.bounds(x0 + 62, 72, 60, 20).build();
		this.addRenderableWidget(blocksTab);
		this.addRenderableWidget(entitiesTab);

		search = new EditBox(this.font, x0 + 126, 72, 182, 20, Component.literal("Search"));
		search.setHint(Component.literal("Search..."));
		search.setMaxLength(64);
		search.setValue(query);
		search.setResponder(s -> {
			query = s;
			page = 0;
			refresh();
		});
		this.addRenderableWidget(search);
		this.setInitialFocus(search);

		// List rows
		int listTop = 98;
		int footerY = this.height - 26;
		rowCount = Math.max(3, Math.min(12, (footerY - 16 - listTop) / 21));
		for (int i = 0; i < rowCount; i++) {
			final int slot = i;
			Button row = Button.builder(Component.empty(), b -> onRowClicked(slot))
				.bounds(x0, listTop + i * 21, 308, 20).build();
			rows.add(row);
			this.addRenderableWidget(row);
		}

		// Footer
		prevButton = Button.builder(Component.literal("<"), b -> { page--; refresh(); })
			.bounds(x0, footerY, 30, 20).build();
		nextButton = Button.builder(Component.literal(">"), b -> { page++; refresh(); })
			.bounds(x0 + 278, footerY, 30, 20).build();
		this.addRenderableWidget(prevButton);
		this.addRenderableWidget(nextButton);
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
			.bounds(cx - 50, footerY, 100, 20).build());

		refresh();
	}

	private void switchTab(Tab newTab) {
		tab = newTab;
		page = 0;
		refresh();
	}

	private Set<String> selectedSet() {
		return tab == Tab.BLOCKS ? cfg.blocks : cfg.entities;
	}

	private void onRowClicked(int slot) {
		int index = page * rowCount + slot;
		if (index < 0 || index >= filtered.size()) return;
		EspConfig.toggle(selectedSet(), filtered.get(index));
		cfg.rebuild();
		refresh();
	}

	/** Re-applies the search, paging, and button labels. */
	private void refresh() {
		List<Identifier> source = tab == Tab.BLOCKS ? allBlocks : allEntities;
		String q = query.toLowerCase(Locale.ROOT).trim();
		if (q.isEmpty()) {
			filtered = source;
		} else {
			List<Identifier> out = new ArrayList<>();
			for (Identifier id : source) {
				if (id.toString().contains(q)) out.add(id);
			}
			filtered = out;
		}

		int pages = Math.max(1, (filtered.size() + rowCount - 1) / rowCount);
		page = Math.max(0, Math.min(page, pages - 1));

		Set<String> selected = selectedSet();
		for (int i = 0; i < rows.size(); i++) {
			Button row = rows.get(i);
			int index = page * rowCount + i;
			if (index < filtered.size()) {
				Identifier id = filtered.get(index);
				boolean on = selected.contains(id.toString());
				row.visible = true;
				row.setMessage(Component.literal((on ? "[x]  " : "[   ]  ") + id)
					.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.WHITE));
			} else {
				row.visible = false;
			}
		}

		prevButton.active = page > 0;
		nextButton.active = page < pages - 1;
		blocksTab.setMessage(tabLabel("Blocks", tab == Tab.BLOCKS));
		entitiesTab.setMessage(tabLabel("Entities", tab == Tab.ENTITIES));
	}

	private static Component tabLabel(String name, boolean active) {
		return Component.literal(name).withStyle(active ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
	}

	private static Button toggle(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set) {
		return Button.builder(toggleLabel(name, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(toggleLabel(name, get.getAsBoolean()));
		}).bounds(x, y, w, 20).build();
	}

	private static Component toggleLabel(String name, boolean on) {
		return Component.literal(name + ": " + (on ? "ON" : "OFF"))
			.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	private static Button cycle(int x, int y, int w, String name, String suffix,
			int[] options, IntSupplier get, IntConsumer set) {
		return Button.builder(Component.literal(name + ": " + get.getAsInt() + suffix), b -> {
			int current = get.getAsInt();
			int next = options[0];
			for (int i = 0; i < options.length; i++) {
				if (options[i] > current) {
					next = options[i];
					break;
				}
			}
			set.accept(next);
			b.setMessage(Component.literal(name + ": " + next + suffix));
		}).bounds(x, y, w, 20).build();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		int cx = this.width / 2;
		String title = "Simple ESP  -  " + cfg.blocks.size() + " blocks, " + cfg.entities.size() + " entities selected";
		graphics.text(this.font, title, cx - this.font.width(title) / 2, 10, 0xFFFFFFFF, true);

		int pages = Math.max(1, (filtered.size() + rowCount - 1) / rowCount);
		String pageText = filtered.size() + " results  -  page " + (page + 1) + "/" + pages;
		graphics.text(this.font, pageText, cx - this.font.width(pageText) / 2, this.height - 40, 0xFFAAAAAA, true);
	}

	@Override
	public void removed() {
		cfg.rebuild();
		cfg.save();
		EspRenderer.forceScan();
		super.removed();
	}
}
