package com.visibilityenhancer;

import java.awt.Color;
import net.runelite.client.config.*;

@ConfigGroup("visibilityenhancer")
public interface VisibilityEnhancerConfig extends Config
{
	// --- OPACITY SECTION ---
	@ConfigSection(
			name = "Opacity & Range",
			description = "Control how transparent players and projectiles appear.",
			position = 1
	)
	String opacitySection = "opacitySection";

	@Range(min = 0, max = 100)
	@ConfigItem(
			keyName = "selfOpacity",
			name = "My Opacity",
			position = 1,
			section = opacitySection,
			description = "Transparency of your own character"
	)
	default int selfOpacity() { return 100; }

	@Range(min = 0, max = 100)
	@ConfigItem(
			keyName = "playerOpacity",
			name = "Ghost Opacity",
			position = 2,
			section = opacitySection,
			description = "Transparency of nearby players"
	)
	default int playerOpacity() { return 100; }

	@Range(min = 0, max = 100)
	@ConfigItem(
			keyName = "myProjectileOpacity",
			name = "My Projectile Opacity",
			position = 3,
			section = opacitySection,
			description = "Transparency of projectiles you fire"
	)
	default int myProjectileOpacity() { return 100; }

	@Range(min = 1, max = 50)
	@ConfigItem(
			keyName = "proximityRange",
			name = "Ghosting Distance",
			position = 4,
			section = opacitySection,
			description = "Radius (in tiles) around you where players will be ghosted"
	)
	default int proximityRange() { return 10; }

	@ConfigItem(
			keyName = "ignoreFriends",
			name = "Ignore Friends",
			position = 5,
			section = opacitySection,
			description = "Prevents friends from being ghosted/transparent"
	)
	default boolean ignoreFriends() { return false; }

	@ConfigItem(
			keyName = "limitAffectedPlayers",
			name = "Limit Max Ghosts",
			position = 6,
			section = opacitySection,
			description = "Limits the number of players affected for performance"
	)
	default boolean limitAffectedPlayers() { return true; }

	@Range(min = 1, max = 100)
	@ConfigItem(
			keyName = "maxAffectedPlayers",
			name = "Max Ghosts",
			position = 7,
			section = opacitySection,
			description = "The maximum number of players to apply effects to"
	)
	default int maxAffectedPlayers() { return 8; }


	// --- EXTRAS SECTION ---
	@ConfigSection(
			name = "Visibility Extras",
			description = "Ground view filters and projectile cleanup.",
			position = 10
	)
	String extrasSection = "extrasSection";

	@ConfigItem(
			keyName = "selfClearGround",
			name = "Clear Ground (Self)",
			position = 1,
			section = extrasSection,
			description = "Hides your Cape, Shield, Legs, and Boots to see ground markers better"
	)
	default boolean selfClearGround() { return false; }

	@ConfigItem(
			keyName = "othersClearGround",
			name = "Clear Ground (Others)",
			position = 2,
			section = extrasSection,
			description = "Hides Cape, Shield, Legs, and Boots on nearby ghosted players"
	)
	default boolean othersClearGround() { return false; }

	@ConfigItem(
			keyName = "hideOthersProjectiles",
			name = "Hide Others' Projectiles",
			position = 3,
			section = extrasSection,
			description = "Completely hides projectiles that didn't come from you"
	)
	default boolean hideOthersProjectiles() { return false; }

	@ConfigItem(
			keyName = "hideGhostExtras",
			name = "Hide Ghost Overheads/Hits",
			position = 4,
			section = extrasSection,
			description = "Hides overhead prayers and hitsplats for ghosted players"
	)
	default boolean hideGhostExtras() { return false; }


	// --- OUTLINE SECTION ---
	@ConfigSection(
			name = "Outlines",
			description = "Settings for player outlines and colors.",
			position = 20
	)
	String outlineSection = "outlineSection";

	@ConfigItem(
			keyName = "selfOutline",
			name = "Outline Myself",
			position = 1,
			section = outlineSection,
			description = "Enable an outline for your character"
	)
	default boolean selfOutline() { return false; }

	@Alpha
	@ConfigItem(
			keyName = "selfOutlineColor",
			name = "My Outline Color",
			position = 2,
			section = outlineSection,
			description = "The color of your own outline"
	)
	default Color selfOutlineColor() { return Color.WHITE; }

	@ConfigItem(
			keyName = "othersOutline",
			name = "Outline Ghosts",
			position = 3,
			section = outlineSection,
			description = "Enable outlines for nearby ghosted players"
	)
	default boolean othersOutline() { return false; }

	@Alpha
	@ConfigItem(
			keyName = "othersOutlineColor",
			name = "Ghost Outline Color",
			position = 4,
			section = outlineSection,
			description = "The color of ghosted player outlines"
	)
	default Color othersOutlineColor() { return new Color(255, 255, 255, 150); }

	@ConfigItem(
			keyName = "hideStackedOutlines",
			name = "Hide Stacked Outlines",
			position = 5,
			section = outlineSection,
			description = "Only shows one outline per tile if players are standing on each other"
	)
	default boolean hideStackedOutlines() { return true; }


	// --- OUTLINE STYLE SECTION ---
	@ConfigSection(
			name = "Outline Style",
			description = "Visual aesthetics of the outlines (Global settings).",
			position = 30
	)
	String styleSection = "styleSection";

	@Range(min = 1, max = 10)
	@ConfigItem(
			keyName = "outlineWidth",
			name = "Line Thickness",
			position = 1,
			section = styleSection,
			description = "Thickness of the primary outline"
	)
	default int outlineWidth() { return 1; }

	@Range(min = 0, max = 10)
	@ConfigItem(
			keyName = "outlineFeather",
			name = "Line Blur (Feather)",
			position = 2,
			section = styleSection,
			description = "How soft the edges of the primary line are"
	)
	default int outlineFeather() { return 0; }

	@ConfigItem(
			keyName = "enableGlow",
			name = "Add Outer Glow",
			position = 3,
			section = styleSection,
			description = "Adds a secondary, wider blurred layer behind the primary line"
	)
	default boolean enableGlow() { return false; }

	@Range(min = 1, max = 20)
	@ConfigItem(
			keyName = "glowWidth",
			name = "Glow Thickness",
			position = 4,
			section = styleSection,
			description = "Width of the glow layer"
	)
	default int glowWidth() { return 4; }

	@Range(min = 1, max = 10)
	@ConfigItem(
			keyName = "glowFeather",
			name = "Glow Blur",
			position = 5,
			section = styleSection,
			description = "Softness/Feathering of the glow layer"
	)
	default int glowFeather() { return 4; }
}