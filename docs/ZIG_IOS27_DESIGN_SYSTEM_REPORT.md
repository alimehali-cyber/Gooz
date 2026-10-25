# ZIG iOS 27 Design System — Research & Overhaul Plan

**Repository:** `alimehali-cyber/Lm-arena` (branch `arena/b93bf689-lm-arena`)  
**Date:** 2026-10-16  
**Scope:** Every user-visible surface of the ZIG astronomy app (Home, ISS tracker, Compass AR, Satellite detail, Lab, Time Dilation, dialogs, navigation, settings).

---

## 1. Executive Summary

ZIG is an Android (Jetpack Compose) astronomy app with a sophisticated existing Liquid Glass implementation (`LiquidGlassSurface`, Kyant backdrop). The overhaul goal is to make **every** visible surface conform to the iOS 27 design language while retaining ZIG’s astronomy identity (celestial imagery, red brand accent).

Core decisions:

1. **Three themes only:** `GLASS` (iOS 27 Liquid Glass), `LIGHT` (clean premium iOS), `DARK` (OLED, mostly black). All legacy themes removed.
2. **Control layer vs. content layer separation** (Apple rule): Liquid Glass is used only for navigation, toolbars, floating controls, sheets, alerts. Content (cards, lists, imagery) uses standard materials/colors.
3. **iOS 27 Liquid Glass rules:** stronger diffusion, darkened edges, brighter specular highlights, uniform toolbar, transparency slider support, layered app icons, no gyroscopic shimmer.
4. **Measured tokens** from Apple’s official iOS 27 UI Kit (Figma 27.0.2) via the `seunghan91/ios27-design-system` repo: colors, spacing, radii, typography, materials.
5. **Accessibility first:** honor Reduce Transparency, Increase Contrast, Reduce Motion; text contrast repaired at token level.

---

## 2. Research Findings

### 2.1 iOS 27 Liquid Glass changes (WWDC 2026)

- Default transparency reduced (~40% → ~60% opacity floor); stronger diffusion for busy backgrounds.
- **Darkened edge** outline separates glass from content beneath.
- **Brighter specular** highlights read as light on an edge, not a sheen.
- **Uniform toolbar** when content scrolls under the top edge.
- **Transparency slider** (Settings → Appearance → Liquid Glass): ultra clear → fully tinted; accessibility overrides personalization.
- **Sidebars** edge-to-edge with colored icons.
- **Prominent Tab** generalized (any tab can take the search slot).
- **Layered app icons**: multiple glass layers baked into artwork; gyroscopic shimmer removed; static top/bottom highlights remain.
- **High Contrast Liquid Glass** middle mode (keeps shapes/refraction, +contrast, −50% blur, opaque text backdrop).
- Third-party opt-out via `UIDesignSystem` API.

### 2.2 Color system (measured, iOS 27 kit 27.0.2)

System backgrounds (light / dark):
- Primary: `#FFFFFF` / `#000000`
- Secondary: `#F2F2F7` / `#1C1C1E`
- Tertiary: `#FFFFFF` / `#2C2C2E`
- Grouped primary: `#F2F2F7` / `#000000`

Labels:
- Primary: `#000000` / `#FFFFFF`
- Secondary: `#3C3C43` @ 60% / `#EBEBF5` @ 70%
- Tertiary: `#3C3C43` @ 30% / `#EBEBF5` @ 30%
- Quaternary: `#3C3C43` @ 18% / `#EBEBF5` @ 16%

Separators:
- Opaque: `#C6C6C8` / `#38383A`
- Non-opaque: `#000000` @ 12% / `#FFFFFF` @ 17%

Accents (system, unchanged iOS 26→27): red `#FF383C`/`#FF4245`, orange, yellow, green, mint, teal, cyan, blue `#0088FF`/`#0091FF`, indigo, purple, pink, brown, gray.

### 2.3 Typography (SF Pro / Dynamic Type)

Scale (default size): Large Title 34, Title 1 28, Title 2 22, Title 3 20, Headline 17, Body 17, Callout 16, Subhead 15, Footnote 13, Caption 1 12, Caption 2 11. Line heights +4–6pt. Bold weights for titles; semibold headlines. Persian uses Vazirmatn (bundled) at equivalent sizes.

### 2.4 Spacing & radii (measured)

- 8pt grid with 4pt subdivisions; content margin 16pt (iPhone).
- Navigation bar height 54 (was 44); large title 125.
- Tab bar height 54, glass platter 62, total 95 (minimized 40/48/88).
- List row regular 52, tall 68.
- Buttons: small 28, medium 34, large 50 (all pills except borderless-large 12).
- Alerts: 300 wide, 14 padding, 48 button height, radius 34.
- Sheets: large top 38, medium floating [34,34,58,58], grabber 58×4.
- Radii: card 12, alert/menu/context-menu 34, popover 38, sheet 34/38, notification 24/26, liquid glass medium/large 34, clear 23, pill 9999.
- Touch target minimum 44×44.

### 2.5 Liquid Glass material (measured)

- Depth 16, frost small/medium/large 7/12/14, splay 6, refraction 100.
- Transparency slider: wire surfaces to scale variables; `prefers-reduced-transparency` overrides.
- Avoid glass-on-glass; glass only on control layer.

### 2.6 Iconography

- SF Symbols (system) on iOS; on Android use Material Symbols (outlined/filled) with consistent 24dp optical size.
- Layered app icons: multiple transparent panes with selective refraction; static top/bottom highlights.
- Icon sizes: 14/18/22/28/36 (matches RedIconSize).

---

## 3. ZIG Design System Basis

### 3.1 Principles

1. **Deference:** UI serves content (sky, stars, orbits).
2. **Clarity:** high contrast, semantic colors, Dynamic Type.
3. **Depth:** layers via glass on controls only; elevation via shadows.
4. **Concentricity:** nested radii = outer − padding.
5. **Consistency:** single token source; no ad-hoc colors.
6. **Adaptive:** themes + accessibility.

### 3.2 Token architecture (existing, refined)

- `RedSpacing`: 4/8/12/16/20/24/32/40/48 (8pt grid).
- `RedCornerRadius`: xs 4, sm 8, md 10, window 34 (glass medium/large), pill 999.
- `RedElevation`: none/subtle/card/popover/modal/floating.
- `RedColorTokens`: semantic backgrounds, surfaces, text, borders, accents, status.
- `RedTheme`: Compose accessor.
- `LiquidGlassConfig`: clarity, blur, refraction, chromatic, highlight, shadow, tint alpha (transparency slider).

### 3.2b Corner geometry: squircle everywhere

Every rounded rectangle in the app is a **squircle** — Lamé's superellipse
`|x/a|ⁿ + |y/a|ⁿ = 1` at **n = 4** — not a circular-arc round rect.

A `RoundedCornerShape` corner is an arc: curvature jumps from `0` on the straight edge to `1/r` at
the join. That is a G1 join, and it is the small roughness the eye picks up in a screen full of
cards. A superellipse runs its curvature continuously from `0` to a maximum on the corner diagonal
and back — a G2 join — so the corner reads as one curve instead of "a rectangle with surgery".

| Piece | Where | Notes |
|---|---|---|
| Curve constants | `ui/theme/Squircle.kt` (`Squircle.CORNER_HALF`) | 4 cubic Béziers per corner; arc-length nodes solved as G2 cubic Hermites |
| Shape | `SquircleShape : CornerBasedShape` | drop-in for `RoundedCornerShape`, same RTL mirroring |
| Call-site API | `squircleShape(radius)` | mirrors `RoundedCornerShape`'s overloads one-for-one |
| Named shapes | `RedShapes.container / control / item` | token radii, no magic numbers |
| Material surfaces | `RedShapes.material` -> `MaterialTheme(shapes = ...)` | cards, dialogs, sheets, menus, text fields, chips |
| Concentric nesting | `squircleConcentric(outer, gap)` | corner *boxes* share the gap, so curves stay parallel |

Accuracy, measured against the analytic curve on the unit corner box and asserted on every build by
`SquircleGeometryTest`:

- maximum positional deviation **0.0098 % of r** (0.01 px at 34 dp on a 3x display);
- curvature continuity at the internal joins to single-precision noise;
- curvature **exactly 0** where the corner meets an edge, so the edge join is G2 too.

**Capsules are the one exception.** A capsule is a rectangle whose radius has reached half its short
side; its ends are semicircular by definition. Smoothing them would visibly flatten every pill, badge
and avatar, so capsules stay circular — via the named `RedShapes.capsule`, which makes the intent
explicit at the call site instead of hiding a G1 corner inside a token.

### 3.3 Theme behavior

| Theme | Base | Surfaces | Text | Glass |
|---|---|---|---|---|
| GLASS | dark sky-friendly | translucent glass over content | adaptive ink | real Kyant backdrop sampling |
| LIGHT | iOS system light | white/secondary grouped | black/secondary | glass on controls, light tint |
| DARK | OLED black | black/1c1c1e | white/secondary | glass on controls, dark tint |

---

## 4. Three ZIG Color Palettes (choose one)

All palettes use iOS 27 measured neutrals (backgrounds/labels/separators) for system correctness. They differ in **brand accent** and **celestial accent** pairing.

### Palette A — "ZIG Crimson" (current identity, refined)

Brand: deep vermilion (astronomy/red planet heritage). Premium, warm, distinctive.

| Token | Light | Dark | Glass |
|---|---|---|---|
| accentPrimary | `#D32F2F` | `#E53935` | `#E53935` |
| accentSecondary (gold) | `#B8860B` | `#D4AF37` | `#D4AF37` |
| accentTertiary (blue) | `#0284C7` | `#38BDF8` | `#38BDF8` |
| statusSuccess | `#2E7D32` | `#34C759` | `#34C759` |
| statusWarning | `#B45309` | `#FF9F0A` | `#FF9F0A` |
| statusError | `#DC2626` | `#FF453A` | `#FF453A` |
| statusInfo | `#0284C7` | `#0A84FF` | `#0A84FF` |

Neutrals: iOS 27 system (above). Background light `#FFFFFF`, dark `#000000`. Glass base dark sky `#090A0F`.

### Palette B — "ZIG Celestial" (cool, scientific, iOS-native)

Brand: system blue/indigo. Feels like a native Apple astronomy tool (Maps/Weather vibe).

| Token | Light | Dark | Glass |
|---|---|---|---|
| accentPrimary | `#0088FF` | `#0091FF` | `#0091FF` |
| accentSecondary (gold) | `#C5A059` | `#D4AF37` | `#D4AF37` |
| accentTertiary (cyan) | `#00C0E8` | `#3CD3FE` | `#3CD3FE` |
| statusSuccess | `#2E7D32` | `#30D158` | `#30D158` |
| statusWarning | `#B45309` | `#FF9F0A` | `#FF9F0A` |
| statusError | `#DC2626` | `#FF453A` | `#FF453A` |
| statusInfo | `#0088FF` | `#0A84FF` | `#0A84FF` |

Neutrals: iOS 27 system. Accent is blue (primary actions), gold for celestial highlights.

### Palette C — "ZIG Aurora" (modern, vibrant, premium)

Brand: teal/mint with indigo. Fresh, modern, high-energy; stands out.

| Token | Light | Dark | Glass |
|---|---|---|---|
| accentPrimary | `#00C3D0` | `#00D2E0` | `#00D2E0` |
| accentSecondary (indigo) | `#6155F5` | `#6D7CFF` | `#6D7CFF` |
| accentTertiary (gold) | `#C5A059` | `#D4AF37` | `#D4AF37` |
| statusSuccess | `#2E7D32` | `#30D158` | `#30D158` |
| statusWarning | `#B45309` | `#FF9F0A` | `#FF9F0A` |
| statusError | `#DC2626` | `#FF453A` | `#FF453A` |
| statusInfo | `#00C0E8` | `#3CD3FE` | `#3CD3FE` |

Neutrals: iOS 27 system. Teal primary, indigo secondary.

**Recommendation:** Palette A preserves brand equity and is safest; Palette B maximizes iOS-native feel; Palette C is most distinctive for marketing. All share the same neutral system and glass behavior, so switching is a token-only change.

---

## 5. Screen-by-Screen Design (3 variants each)

Variant key: **G** = Glass, **L** = Light, **D** = Dark (OLED).

### 5.1 Home Screen

- **G:** Hero sky canvas full-bleed; moon card as floating glass capsule (radius 34) over sky; list of top objects as translucent rows with glass header; bottom nav glass pill. Strong diffusion + darkened edge. Text adaptive ink.
- **L:** White background; grouped table sections (iOS style) with 12-radius cards; moon card white with subtle shadow; section headers 13pt semibold gray; bottom nav translucent white.
- **D:** Pure black background; cards `#1C1C1E` with 1pt `#27272A` border; moon card black with gray border; text white/secondary; bottom nav black glass.

Common: large title "ZIG" 34pt bold; 16pt margins; 52pt rows; 44pt tap targets.

### 5.2 ISS Tracker

- **G:** Map in glass-framed container (radius 34) with glass overlay chips for position/time; floating glass control bar; orbit track accent.
- **L:** Map in white card (radius 12) with light shadow; info chips white pills; controls white glass.
- **D:** Map in black container with `#1C1C1E` chips; controls black glass.

### 5.3 Compass AR

- **G:** Full-screen camera; glass HUD panels (top status, bottom controls) with darkened edges; crosshair accent; adaptive ink.
- **L:** Camera with white translucent HUD; large clear typography; minimal chrome.
- **D:** Camera with black translucent HUD; white text; high contrast.

### 5.4 Satellite Detail

- **G:** Hero glass header with satellite name; glass info cards (orbit params) in grid; glass action buttons.
- **L:** White header; grouped info table; white cards radius 12.
- **D:** Black header; dark cards `#1C1C1E`.

### 5.5 Lab Screen

- **G:** Glass tab bar; feature cards as glass tiles with icons; physics sandbox canvas with glass controls.
- **L:** White grouped sections; cards white; icons tinted.
- **D:** Black; dark tiles.

### 5.6 Time Dilation Calculator

- **G:** Glass input panel; glass result card; sliders with glass track.
- **L:** White inputs; grouped form; result card white.
- **D:** Black inputs; dark result.

### 5.7 Settings Dialog

- **G:** Glass sheet (radius 34 top) with grouped rows; glass toggles; transparency slider preview.
- **L:** White sheet; iOS grouped table; toggles standard.
- **D:** Black sheet; dark grouped rows.

### 5.8 Floating Navigation Bar

- Glass capsule (radius 34/999) with blur 0 + refraction 28; icons 22; labels 10; height 64 (or 54 iOS). Active tab accent tint; inactive secondary.

### 5.9 Dialogs (Location, Date, Calibration, Object Detail, Eclipse, Moon, Favorites)

- Glass: floating glass platter radius 34, darkened edge, specular highlight, adaptive ink.
- Light: white platter radius 34, shadow, black text.
- Dark: black platter, white text.

---

## 6. Best Repos / Sources for an AI Coding Agent

1. **seunghan91/ios27-design-system** — measured tokens (colors, spacing, radii, typography, materials, icons, animations), 36 component specs, 48 page recipes. Primary reference. `/tmp/ios27-design-system`.
2. **Apple HIG** — `developer.apple.com/design/human-interface-guidelines` — principles, components, accessibility.
3. **Apple Design Resources** — Figma/Sketch kits (official).
4. **WWDC 2025/2026 videos** — "Meet Liquid Glass", "Get to know the new design system", iOS 27 changes.
5. **Kyant backdrop library** (Android Compose) — `com.kyant.backdrop` — real glass sampling.
6. **Material 3 Compose** — baseline components, dynamic color.
7. **Compose Material Symbols** — icon set.
8. **LearnUI.design iOS guidelines** — typography tables.
9. **Bitrig liquid glass best practices** — control vs content layer.
10. **MacRumors/MacStories iOS 27 reviews** — user-facing changes.

---

## 7. Implementation Roadmap

Phase 1 (done): Theme enum reduction, token color/radius updates, build green.  
Phase 2: Wire transparency slider to `LiquidGlassConfig`; add High Contrast mode.  
Phase 3: Apply iOS radii/spacing to all components (cards 12/34, buttons pill, alerts 34, sheets 34/38).  
Phase 4: Typography scale alignment (Large Title 34, etc.).  
Phase 5: Icon consistency (Material Symbols outlined, 24dp).  
Phase 6: Per-screen layout polish (above).  
Phase 7: Accessibility (Reduce Transparency/Motion/Contrast).  
Phase 8: Layered app icon (Icon Composer equivalent).  
Phase 9: Tests + visual regression.

---

## 8. Notes

- No placeholders/false data in info pages; leave unknown fields empty/default.
- Persian follows Vazirmatn + Persian-writing conventions.
- Information from credible sources (Apple, measured kit).
- Integration with existing search/detail/AR/catalogue flows preserved.
