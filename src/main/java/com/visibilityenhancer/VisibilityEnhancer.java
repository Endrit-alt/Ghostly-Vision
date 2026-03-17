package com.visibilityenhancer;

import com.google.inject.Provides;
import java.util.*;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.Hooks;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
		name = "Visibility Enhancer",
		description = "Teammate opacity, ground-view filters, and outlines for raids/PvP.",
		tags = {"raid", "pvp", "opacity", "outline", "equipment"}
)
public class VisibilityEnhancer extends Plugin
{
	private static final long INTERACTION_TIMEOUT_MS = 20000; // 20 seconds

	@Inject
	private Client client;

	@Inject
	private VisibilityEnhancerConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private VisibilityEnhancerOverlay overlay;

	@Inject
	private Hooks hooks;

	@Getter
	private final Set<Player> ghostedPlayers = new HashSet<>();

	private final Map<Player, int[]> originalEquipmentMap = new HashMap<>();
	private final Map<Player, Long> lastInteractionMap = new HashMap<>();
	private final Set<Projectile> myProjectiles = new HashSet<>();

	private final Hooks.RenderableDrawListener drawListener = this::shouldDraw;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		hooks.registerRenderableDrawListener(drawListener);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		hooks.unregisterRenderableDrawListener(drawListener);

		for (Player p : client.getPlayers())
		{
			if (p != null) restorePlayer(p);
		}
		ghostedPlayers.clear();
		originalEquipmentMap.clear();
		lastInteractionMap.clear();
		myProjectiles.clear();
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		Projectile proj = event.getProjectile();
		if (proj.getStartCycle() != client.getGameCycle()) return;

		Player local = client.getLocalPlayer();
		if (local == null) return;

		LocalPoint lp = local.getLocalLocation();
		// Increased buffer and check to ensure powered staves like Shadow are caught correctly
		int distSq = (int) (Math.pow(proj.getX1() - lp.getX(), 2) + Math.pow(proj.getY1() - lp.getY(), 2));

		if (distSq < (180 * 180) && local.getAnimation() != -1)
		{
			myProjectiles.add(proj);
		}
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		Player p = event.getPlayer();
		ghostedPlayers.remove(p);
		originalEquipmentMap.remove(p);
		lastInteractionMap.remove(p);
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged event)
	{
		Player p = event.getPlayer();
		Player local = client.getLocalPlayer();
		if (p == null || local == null) return;

		if (p == local)
		{
			// FIX: When you change gear, forget the old "original" look so we grab the new one
			originalEquipmentMap.remove(p);
			if (config.selfClearGround()) applyClothingFilter(p);
			return;
		}

		if (isRecentlyInteracting(p, local))
		{
			restorePlayer(p);
			return;
		}

		if (ghostedPlayers.contains(p) && config.othersClearGround())
		{
			applyClothingFilter(p);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			clearAllGhosting();
			return;
		}

		myProjectiles.removeIf(p -> client.getGameCycle() >= p.getEndCycle());

		if (config.selfClearGround()) applyClothingFilter(local);
		else if (originalEquipmentMap.containsKey(local)) restoreClothing(local);

		WorldPoint localLoc = local.getWorldLocation();
		if (localLoc == null)
		{
			clearAllGhosting();
			return;
		}

		List<Player> inRange = new ArrayList<>();
		long now = System.currentTimeMillis();

		for (Player p : client.getPlayers())
		{
			if (p == null || p == local) continue;

			boolean isInteracting = p.getInteracting() == local || local.getInteracting() == p;
			if (isInteracting)
			{
				lastInteractionMap.put(p, now);
			}

			boolean isFriend = config.ignoreFriends() && (p.isFriend() || client.isFriended(p.getName(), false));
			boolean recentlyInteracted = (now - lastInteractionMap.getOrDefault(p, 0L)) < INTERACTION_TIMEOUT_MS;

			if (isFriend || recentlyInteracted)
			{
				if (ghostedPlayers.contains(p)) restorePlayer(p);
				continue;
			}

			WorldPoint pLoc = p.getWorldLocation();
			if (pLoc != null && localLoc.distanceTo(pLoc) <= config.proximityRange())
			{
				inRange.add(p);
			}
		}

		if (config.limitAffectedPlayers() && inRange.size() > config.maxAffectedPlayers())
		{
			inRange.sort(Comparator.comparingInt(p -> localLoc.distanceTo(p.getWorldLocation())));
			inRange = inRange.subList(0, config.maxAffectedPlayers());
		}

		Set<Player> currentInRange = new HashSet<>(inRange);
		int opacity = config.playerOpacity();
		boolean hideOthersClothes = config.othersClearGround();

		for (Player p : currentInRange)
		{
			if (opacity < 100) applyOpacity(p, opacity);
			else restoreOpacity(p);

			if (hideOthersClothes) applyClothingFilter(p);
			else if (originalEquipmentMap.containsKey(p)) restoreClothing(p);
		}

		Set<Player> noLongerGhosted = new HashSet<>(ghostedPlayers);
		noLongerGhosted.removeAll(currentInRange);
		for (Player p : noLongerGhosted) restorePlayer(p);

		ghostedPlayers.clear();
		ghostedPlayers.addAll(currentInRange);
	}

	private boolean isRecentlyInteracting(Player p, Player local)
	{
		if (p.getInteracting() == local || local.getInteracting() == p) return true;
		return (System.currentTimeMillis() - lastInteractionMap.getOrDefault(p, 0L)) < INTERACTION_TIMEOUT_MS;
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		Player local = client.getLocalPlayer();
		if (local == null) return;

		int selfOpacity = config.selfOpacity();
		if (selfOpacity < 100) applyOpacity(local, selfOpacity);
		else restoreOpacity(local);

		int othersAlpha = clampAlpha(config.playerOpacity());
		int myProjAlpha = clampAlpha(config.myProjectileOpacity());

		for (Projectile proj : client.getProjectiles())
		{
			Actor target = proj.getInteracting();
			if (target != null && target != local)
			{
				int alpha = myProjectiles.contains(proj) ? myProjAlpha : othersAlpha;

				Model m = proj.getModel();
				if (m != null)
				{
					byte[] trans = m.getFaceTransparencies();
					if (trans != null && trans.length > 0) Arrays.fill(trans, (byte) alpha);
				}
			}
		}
	}

	private boolean shouldDraw(Renderable renderable, boolean drawingUI)
	{
		if (renderable instanceof Projectile && config.hideOthersProjectiles())
		{
			Projectile proj = (Projectile) renderable;
			Actor target = proj.getInteracting();
			return target == null || target == client.getLocalPlayer() || myProjectiles.contains(proj);
		}

		if (renderable instanceof Player && config.hideGhostExtras())
		{
			Player player = (Player) renderable;
			if (ghostedPlayers.contains(player))
			{
				if (drawingUI)
				{
					return player.getOverheadText() != null;
				}
			}
		}
		return true;
	}

	private void applyClothingFilter(Player player)
	{
		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null) return;

		int[] equipmentIds = comp.getEquipmentIds();

		// Save the look BEFORE we hide anything
		if (!originalEquipmentMap.containsKey(player))
		{
			originalEquipmentMap.put(player, equipmentIds.clone());
		}

		int[] slotsToHide = {
				KitType.CAPE.getIndex(),
				KitType.SHIELD.getIndex(),
				KitType.LEGS.getIndex(),
				KitType.BOOTS.getIndex()
		};

		boolean changed = false;
		for (int slot : slotsToHide)
		{
			if (equipmentIds[slot] != -1)
			{
				equipmentIds[slot] = -1;
				changed = true;
			}
		}
		if (changed) comp.setHash();
	}

	private void restoreClothing(Player player)
	{
		if (!originalEquipmentMap.containsKey(player)) return;

		PlayerComposition comp = player.getPlayerComposition();
		if (comp != null)
		{
			int[] original = originalEquipmentMap.get(player);
			int[] current = comp.getEquipmentIds();
			System.arraycopy(original, 0, current, 0, original.length);
			comp.setHash();
		}
		originalEquipmentMap.remove(player);
	}

	private void applyOpacity(Player p, int opacityPercent)
	{
		Model model = p.getModel();
		if (model == null) return;
		byte[] trans = model.getFaceTransparencies();
		if (trans == null || trans.length == 0) return;
		int alpha = clampAlpha(opacityPercent);
		if ((trans[0] & 0xFF) != alpha) Arrays.fill(trans, (byte) alpha);
	}

	private void restoreOpacity(Player p)
	{
		Model model = p.getModel();
		if (model == null) return;
		byte[] trans = model.getFaceTransparencies();
		if (trans != null && trans.length > 0 && (trans[0] & 0xFF) != 0) Arrays.fill(trans, (byte) 0);
	}

	private void restorePlayer(Player p)
	{
		restoreOpacity(p);
		restoreClothing(p);
	}

	private void clearAllGhosting()
	{
		for (Player p : ghostedPlayers) restorePlayer(p);
		ghostedPlayers.clear();
		originalEquipmentMap.clear();
		lastInteractionMap.clear();
		myProjectiles.clear();
	}

	private int clampAlpha(int opacityPercent)
	{
		if (opacityPercent >= 100) return 0;
		return (int) ((100 - opacityPercent) * 2.5);
	}

	@Provides
	VisibilityEnhancerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(VisibilityEnhancerConfig.class);
	}
}