package com.visibilityenhancer;

import com.google.inject.Provides;
import java.util.*;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.*;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.Hooks;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
		name = "Raids Visibility Enhancer",
		description = "Teammate opacity, ground-view filters, and outlines for raids.",
		tags = {"raid", "opacity", "outline", "equipment"}
)
public class VisibilityEnhancer extends Plugin
{
	// OPTIMIZATION: 20 seconds / 600ms per tick = ~33 ticks. Tied to game engine instead of OS clock.
	private static final int INTERACTION_TIMEOUT_TICKS = 33;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

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
	private final Map<Player, Integer> lastInteractionMap = new HashMap<>(); // Now stores Tick Count instead of Long MS
	private final Set<Projectile> myProjectiles = new HashSet<>();
	private final Hooks.RenderableDrawListener drawListener = this::shouldDraw;

	private Player cachedLocalPlayer;
	private final List<Player> inRange = new ArrayList<>();
	private final Set<Player> currentInRange = new HashSet<>();
	private final Set<Player> noLongerGhosted = new HashSet<>();

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

		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				for (Player p : client.getPlayers())
				{
					if (p != null) restorePlayer(p);
				}
			}
		});

		ghostedPlayers.clear();
		originalEquipmentMap.clear();
		lastInteractionMap.clear();
		myProjectiles.clear();
		inRange.clear();
		currentInRange.clear();
		noLongerGhosted.clear();
		cachedLocalPlayer = null;
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		cachedLocalPlayer = client.getLocalPlayer();
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		Projectile proj = event.getProjectile();

		if (proj.getStartCycle() != client.getGameCycle()) return;

		Player local = client.getLocalPlayer();
		if (local == null) return;

		LocalPoint lp = local.getLocalLocation();

		int dx = proj.getX1() - lp.getX();
		int dy = proj.getY1() - lp.getY();
		int distSq = (dx * dx) + (dy * dy);
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

		// OPTIMIZATION: Use LocalPoint for raw 2D scene coordinates
		LocalPoint localLoc = local.getLocalLocation();
		if (localLoc == null)
		{
			clearAllGhosting();
			return;
		}

		inRange.clear();
		currentInRange.clear();
		noLongerGhosted.clear();

		int currentTick = client.getTickCount();
		boolean ignoreFriends = config.ignoreFriends();
		int maxDist = config.proximityRange();
		int localX = localLoc.getSceneX();
		int localY = localLoc.getSceneY();

		for (Player p : client.getPlayers())
		{
			if (p == null || p == local) continue;
			boolean isInteracting = p.getInteracting() == local || local.getInteracting() == p;
			if (isInteracting)
			{
				lastInteractionMap.put(p, currentTick);
			}

			boolean isFriend = ignoreFriends && (p.isFriend() || client.isFriended(p.getName(), false));
			boolean recentlyInteracted = (currentTick - lastInteractionMap.getOrDefault(p, -100)) < INTERACTION_TIMEOUT_TICKS;

			if (isFriend || recentlyInteracted)
			{
				if (ghostedPlayers.contains(p)) restorePlayer(p);
				continue;
			}

			LocalPoint pLoc = p.getLocalLocation();

			// OPTIMIZATION: Pure Chebyshev distance math (0 object allocation, exact OSRS tile distance)
			if (pLoc != null)
			{
				int dist = Math.max(Math.abs(localX - pLoc.getSceneX()), Math.abs(localY - pLoc.getSceneY()));
				if (dist <= maxDist)
				{
					inRange.add(p);
				}
			}
		}

		if (config.limitAffectedPlayers() && inRange.size() > config.maxAffectedPlayers())
		{
			// Sort using the same high-speed Chebyshev distance
			inRange.sort((p1, p2) ->
			{
				LocalPoint lp1 = p1.getLocalLocation();
				LocalPoint lp2 = p2.getLocalLocation();
				if (lp1 == null || lp2 == null) return 0;

				int dist1 = Math.max(Math.abs(localX - lp1.getSceneX()), Math.abs(localY - lp1.getSceneY()));
				int dist2 = Math.max(Math.abs(localX - lp2.getSceneX()), Math.abs(localY - lp2.getSceneY()));
				return Integer.compare(dist1, dist2);
			});
			currentInRange.addAll(inRange.subList(0, config.maxAffectedPlayers()));
		}
		else
		{
			currentInRange.addAll(inRange);
		}

		int opacity = config.playerOpacity();
		boolean hideOthersClothes = config.othersClearGround();
		for (Player p : currentInRange)
		{
			if (opacity < 100) applyOpacity(p, opacity);
			else restoreOpacity(p);

			if (hideOthersClothes) applyClothingFilter(p);
			else if (originalEquipmentMap.containsKey(p)) restoreClothing(p);
		}

		noLongerGhosted.addAll(ghostedPlayers);
		noLongerGhosted.removeAll(currentInRange);

		for (Player p : noLongerGhosted) restorePlayer(p);

		ghostedPlayers.clear();
		ghostedPlayers.addAll(currentInRange);
	}

	private boolean isRecentlyInteracting(Player p, Player local)
	{
		if (p.getInteracting() == local || local.getInteracting() == p) return true;
		return (client.getTickCount() - lastInteractionMap.getOrDefault(p, -100)) < INTERACTION_TIMEOUT_TICKS;
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
				int alpha = myProjectiles.contains(proj) ?
						myProjAlpha : othersAlpha;

				Model m = proj.getModel();
				if (m != null)
				{
					byte[] trans = m.getFaceTransparencies();
					if (trans != null && trans.length > 0 && (trans[0] & 0xFF) != alpha)
					{
						Arrays.fill(trans, (byte) alpha);
					}
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
			return target == null || target == cachedLocalPlayer || myProjectiles.contains(proj);
		}

		if (renderable instanceof Player)
		{
			Player player = (Player) renderable;
			boolean isGhost = ghostedPlayers.contains(player);
			boolean isLocal = player == cachedLocalPlayer;

			if (drawingUI)
			{
				// FORCE FALSE: Completely kills native prayers, HP bars, AND native text
				boolean hideSelfUI = isLocal && config.selfTransparentPrayers();
				boolean hideGhostUI = isGhost && config.othersTransparentPrayers();

				if (hideSelfUI || hideGhostUI)
				{
					return false;
				}

				// Also forcefully kill 2D UI for ghost extras so the native prayer doesn't show when typing
				if (isGhost && config.hideGhostExtras())
				{
					return false;
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