package com.visibilityenhancer;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.HeadIcon;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.SpriteID;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.FontManager;

public class VisibilityEnhancerOverlay extends Overlay
{
	private final Client client;
	private final VisibilityEnhancer plugin;
	private final VisibilityEnhancerConfig config;
	private final ModelOutlineRenderer modelOutlineRenderer;
	private final SpriteManager spriteManager;

	// OPTIMIZATION: Reusable collections
	private final Set<WorldPoint> renderedTiles = new HashSet<>();
	private final List<Player> sortedGhosts = new ArrayList<>(32);

	// OPTIMIZATION: Object Caching to prevent GC stutters
	private int cachedOutlineWidth = -1;
	private int cachedGlowWidth = -1;
	private BasicStroke primaryStroke;
	private BasicStroke glowStroke;

	private Color cachedColor;
	private Color cachedGlowColor;
	private Color cachedFillColor;

	@Inject
	private VisibilityEnhancerOverlay(Client client, VisibilityEnhancer plugin, VisibilityEnhancerConfig config, ModelOutlineRenderer modelOutlineRenderer, SpriteManager spriteManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.modelOutlineRenderer = modelOutlineRenderer;
		this.spriteManager = spriteManager;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Player local = client.getLocalPlayer();
		WorldPoint localPoint = local != null ? local.getWorldLocation() : null;
		LocalPoint localLocalPoint = local != null ? local.getLocalLocation() : null;

		// --- Outline Rendering: Local Player ---
		if (local != null && config.selfOutline())
		{
			if (config.selfUseFloorTileOutline())
			{
				renderFloorTile(graphics, local, config.selfOutlineColor());
			}
			else
			{
				renderOutlineLayers(local, config.selfOutlineColor());
			}
		}

		// --- Outline Rendering: Ghosted Players ---
		if (config.othersOutline())
		{
			renderedTiles.clear();
			boolean hideStacked = config.hideStackedOutlines();
			boolean useFloorTile = config.othersUseFloorTileOutline(); // Updated method call
			Color othersColor = config.othersOutlineColor();

			// OPTIMIZATION: Clear and reuse list instead of instantiating new ArrayList
			sortedGhosts.clear();
			sortedGhosts.addAll(plugin.getGhostedPlayers());

			// OPTIMIZATION: Use LocalPoint for sorting (Avoids heavy 3D plane translation math)
			if (localLocalPoint != null)
			{
				sortedGhosts.sort((p1, p2) ->
				{
					LocalPoint lp1 = p1.getLocalLocation();
					LocalPoint lp2 = p2.getLocalLocation();
					if (lp1 == null || lp2 == null) return 0;

					return Integer.compare(lp2.distanceTo(localLocalPoint), lp1.distanceTo(localLocalPoint));
				});
			}

			for (Player player : sortedGhosts)
			{
				WorldPoint playerPoint = player.getWorldLocation();
				if (playerPoint == null) continue;

				if (hideStacked)
				{
					if (localPoint != null && playerPoint.equals(localPoint)) continue;
					if (renderedTiles.contains(playerPoint)) continue;
					renderedTiles.add(playerPoint);
				}

				if (useFloorTile)
				{
					renderFloorTile(graphics, player, othersColor);
				}
				else
				{
					renderOutlineLayers(player, othersColor);
				}
			}
		}

		// --- Transparent Prayer & Text Rendering ---
		boolean selfCustomPrayers = config.selfTransparentPrayers();
		boolean othersCustomPrayers = config.othersTransparentPrayers();
		boolean hideGhostExtras = config.hideGhostExtras();

		// Handle local player
		if (selfCustomPrayers && local != null)
		{
			drawTransparentPrayer(graphics, local, 100);
			drawOverheadText(graphics, local);
		}

		// Handle ghosted players
		if (othersCustomPrayers || hideGhostExtras)
		{
			for (Player player : plugin.getGhostedPlayers())
			{
				if (othersCustomPrayers)
				{
					drawTransparentPrayer(graphics, player, config.playerOpacity());
				}

				drawOverheadText(graphics, player);
			}
		}

		return null;
	}

	private void renderOutlineLayers(Player player, Color color)
	{
		if (config.enableGlow())
		{
			modelOutlineRenderer.drawOutline(player, config.glowWidth(), color, config.glowFeather());
		}
		modelOutlineRenderer.drawOutline(player, config.outlineWidth(), color, config.outlineFeather());
	}

	private void renderFloorTile(Graphics2D graphics, Player player, Color color)
	{
		Polygon poly = Perspective.getCanvasTilePoly(client, player.getLocalLocation());
		if (poly != null)
		{
			// OPTIMIZATION: Lazy evaluate Colors and Strokes to avoid instantiating them 50x a second
			if (cachedColor == null || !cachedColor.equals(color))
			{
				cachedColor = color;
				cachedGlowColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, color.getAlpha() - 100));
				cachedFillColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), 50);
			}

			if (cachedOutlineWidth != config.outlineWidth())
			{
				cachedOutlineWidth = config.outlineWidth();
				primaryStroke = new BasicStroke(cachedOutlineWidth);
			}

			if (cachedGlowWidth != config.glowWidth() || cachedOutlineWidth != config.outlineWidth())
			{
				cachedGlowWidth = config.glowWidth();
				glowStroke = new BasicStroke(cachedOutlineWidth + cachedGlowWidth);
			}

			// Add a thicker, more transparent stroke behind the primary line to mimic the "Glow" effect
			if (config.enableGlow())
			{
				graphics.setColor(cachedGlowColor);
				graphics.setStroke(glowStroke);
				graphics.draw(poly);
			}

			// Draw the primary outline border using your custom width
			graphics.setColor(cachedColor);
			graphics.setStroke(primaryStroke);
			graphics.draw(poly);

			// Optionally fill the inside of the tile
			if (config.fillFloorTile())
			{
				graphics.setColor(cachedFillColor);
				graphics.fill(poly);
			}
		}
	}

	private void drawTransparentPrayer(Graphics2D graphics, Player player, int opacityPercent)
	{
		HeadIcon icon = player.getOverheadIcon();
		if (icon == null) return;

		int spriteId = getSpriteId(icon);
		if (spriteId == -1) return;

		BufferedImage prayerImage = spriteManager.getSprite(spriteId, 0);
		if (prayerImage == null) return;

		int zOffset = 20;
		Point point = player.getCanvasImageLocation(prayerImage, player.getLogicalHeight() + zOffset);
		if (point == null) return;

		int drawX = point.getX();
		int drawY = point.getY() - 25;

		float alpha = opacityPercent / 100f;
		Composite originalComposite = graphics.getComposite();
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

		graphics.drawImage(prayerImage, drawX, drawY, null);

		graphics.setComposite(originalComposite);
	}

	private void drawOverheadText(Graphics2D graphics, Player player)
	{
		String text = player.getOverheadText();
		if (text == null || text.isEmpty()) return;

		int zOffset = 20;
		Point textPoint = player.getCanvasTextLocation(graphics, text, player.getLogicalHeight() + zOffset);
		if (textPoint == null) return;

		int drawX = textPoint.getX() - 1;
		int drawY = textPoint.getY() + 6;
		Point adjustedPoint = new Point(drawX, drawY);

		graphics.setFont(FontManager.getRunescapeBoldFont());
		OverlayUtil.renderTextLocation(graphics, adjustedPoint, text, Color.YELLOW);
	}

	private int getSpriteId(HeadIcon icon)
	{
		switch (icon)
		{
			case MELEE: return SpriteID.PRAYER_PROTECT_FROM_MELEE;
			case RANGED: return SpriteID.PRAYER_PROTECT_FROM_MISSILES;
			case MAGIC: return SpriteID.PRAYER_PROTECT_FROM_MAGIC;
			case RETRIBUTION: return SpriteID.PRAYER_RETRIBUTION;
			case SMITE: return SpriteID.PRAYER_SMITE;
			case REDEMPTION: return SpriteID.PRAYER_REDEMPTION;
			default: return -1;
		}
	}
}