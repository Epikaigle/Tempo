# Apple Design Best Practices (for AI builders)

> Distilled from Apple's official Design site + Human Interface Guidelines
> (developer.apple.com/design — HIG snapshot Aug 2026, incl. Liquid Glass era).
> Written for an AI coding agent building an **Android** app (Tempo) whose identity is
> **glassmorphism blended with subtle color**. iOS-only tech is translated into
> platform-agnostic rules with Android mappings wherever applicable.
>
> Sources: every section links to its canonical Apple page (`*.apple.com/design/...`).
> Mirror used for extraction: `apple-docs.everest.mt` (same content, static HTML).

## 0. How an AI should use this file

1. **Normative keywords.** `[MUST]` / `[NEVER]` = hard rules, do not violate.
   `[SHOULD]` / `[AVOID]` = strong defaults, deviate only with a stated reason.
   `[VALUES]` = exact numbers/tokens to reuse (sizes, timings, ratios).
2. **Conflict resolution.** Usability > consistency > delight. When two rules clash
   (e.g. glass purity vs legibility), legibility wins — Apple says so explicitly.
3. **Platform translation.** Rules say "Apple HIG:" (verbatim principle) then
   "Android/Tempo:" (how to apply it here). Never copy iOS components pixel-for-pixel;
   apply the *principle* with Material/Android idioms.
4. **Glass rule of thumb (our identity).** Glass is a *functional layer for controls +
   navigation floating over content* — never decoration inside the content layer.
   Our twist (subtle color blended into glass) is allowed ONLY where Apple allows
   tint/vibrancy: to preserve legibility and hierarchy, never to decorate body content.
5. **Pre-ship gate.** Before finishing any UI task, run the checklist in §10.

## 1. Design principles (Apple HIG: Design principles)

Source: <https://developer.apple.com/design/human-interface-guidelines/design-principles>

Apple's principles are decision tools, not decoration rules. Weigh every UI choice against all eight:

### 1.1 Purpose

- [SHOULD] Create value: at every stage ask "what is this screen/feature FOR, does this element serve it?" Cut what doesn't.
- [SHOULD] Keep focused: prioritize the 1–3 most important features; make those truly great instead of many mediocre ones.
- [SHOULD] Differentiate: study existing solutions, don't re-create them; the design must express what sets the product apart (for us: glass + subtle color).

### 1.2 Agency (get out of the way)

- [MUST] Take people directly to task/content. Chrome, upsells, and onboarding must not stand between the user and their goal.
- [SHOULD] Freedom to explore: no locked-in flows or forced modes; any guided flow [MUST] be skippable/escapable to the main experience.
- [MUST] Forgiveness: every destructive or state-changing action [MUST] be reversible (undo, trash/restore, confirm only as last resort). People explore only when recovery is cheap.

### 1.3 Responsibility (trust)

- [MUST] Transparency: intentions clear from first interaction; every permission/data request carries a clear rationale ("why we need X").
- [MUST] Data minimization: collect only what the product needs to function; anticipate misuse and build protections in.
- [SHOULD] State what you collect and how it's used, in plain words, at the point of collection — not buried in settings.

### 1.4 Familiarity

- [SHOULD] Use concepts people already know (real-world metaphors + platform conventions) so the UI feels intuitive on first sight.
- [MUST] Consistency: once an element's look/behavior is established, apply it everywhere. People must be able to predict new interactions.
- [MUST] Feedback: always signal what's happening — control availability, content changes, progress, results. Use system patterns for alerts/choices.

### 1.5 Flexibility

- [MUST] Design for everyone from the start: accessibility and inclusion are day-one requirements, not polish (§3.1, §3.9).
- [SHOULD] Preserve context: keep content/controls in consistent, predictable positions across screens, orientations, and devices; ease transitions with natural animation.
- [SHOULD] Support many inputs (touch, keyboard, voice, switch); [MUST] on each supported platform feel native and polished, not ported.

### 1.6 Simplicity

- [SHOULD] Include just what's necessary — simplicity ≠ minimalism. Keep important things close, let the rest fall away.
- [SHOULD] Be concise: simplest wording is the most universal. Exact words only for labels and messages.
- [MUST] Establish hierarchy: form/function readily apparent; recognizable controls + consistent structure so people always know where they are and what's next.

### 1.7 Craft

- [MUST] Quality bar: deliberate decisions, smooth animations, precise wording, thoughtful audio. Every pixel signals how much you care.
- [SHOULD] Prototype early, discard what fails, test in real-world settings (sunlight, one-handed, low battery, bad network).
- [SHOULD] Maintain craft post-ship: adopt current platform capabilities/patterns; design is ongoing.

### 1.8 Delight

- [SHOULD] Name the target emotion first (energize? calm? thrill?) and let it shape motion, copy, and visuals.
- [SHOULD] Create defining moments: button presses, empty states, even errors can carry character.
- [NEVER] Mistake delight for decoration: never let whimsy obstruct the core task. Delight = sum of freedom + safety + familiarity + care, not ornaments.

## 2. Designing across platforms (Apple HIG: Getting started + platform pages)

Source: <https://developer.apple.com/design/human-interface-guidelines/designing-for-ios> (and iPadOS/macOS/tvOS/visionOS/watchOS/games siblings)

Apple HIG: "Create an app or game that feels at home on every platform you support."
Android/Tempo: we ship on phones first (foldables/tablets later). Steal the *phone-first*
rules below; read tablet/desktop/TV/watch rules as "what to do when form factor changes".

### 2.1 Phone (from Designing for iOS)

- [MUST] Concentrate on primary tasks: limit onscreen controls; secondary actions discoverable with minimal interaction (progressive disclosure).
- [MUST] Adapt seamlessly to orientation, Dark Mode, and variable font sizes (Dynamic Type → Android: `sp`, `fontScale`, allow 85%–130%+ without breakage).
- [MUST] Reachability: key controls in middle/bottom area; swipe-to-go-back and swipe actions on list rows.
- [SHOULD] With permission, use platform capabilities instead of manual entry (payments, biometrics, location).
- [VALUES] Minimum touch target 44×44 pt (≈ 48×48 dp on Android — use 48dp).

### 2.2 Large screens / tablets / foldables (from Designing for iPadOS + macOS)

- [SHOULD] Elevate content: minimize modals and full-screen transitions; controls easy to reach but never covering content.
- [SHOULD] Size/density follows viewing distance + input mode (touch = larger, pointer/keyboard = denser).
- [SHOULD] Support multi-input (touch + keyboard + trackpad/mouse + stylus); combine modes where useful.
- [SHOULD] Desktop-class: flatter hierarchy (more content, fewer nested levels), resizable/rearrangeable panes, full-screen focus mode, keyboard shortcuts for acceleration, personalization (customizable toolbars, colors, fonts).
- [AVOID] Porting phone UI 1:1 to tablets — re-lay-out for the space instead.

### 2.3 TV / 10-foot UI (from Designing for tvOS)

- [SHOULD] Focus system: gently highlight + expand the focused item; user always knows where they are.
- [SHOULD] Edge-to-edge artwork, subtle fluid animation, legible across the room (huge type, high contrast).
- [SHOULD] Multiuser: sign-in easy and infrequent; auto-switch profiles.

### 2.4 Watch / glanceables (from Designing for watchOS)

- [MUST] Glanceable single-screen interactions (< 1 minute, 1–2 gestures); minimal hierarchy depth.
- [SHOULD] Proactive, moment-relevant content from on-device data; complications/widgets dive straight into the app.
- [SHOULD] Notifications deliver value + actions without opening the app.
- [SHOULD] Use color as supporting information and materials for hierarchy/place.
- Android/Tempo: same rules apply to widgets, notifications, and Wear OS tiles.

### 2.5 Immersive / spatial (from Designing for visionOS)

- [SHOULD] Minimum effective immersion per moment — not everything needs full immersion.
- [MUST] Content inside the field of view; [NEVER] force head-turns/position changes to interact.
- [NEVER] Overwhelming, jarring, too-fast motion, or scenes without a stationary frame of reference.
- [SHOULD] Prefer familiar standard windows/controls for standard tasks.

### 2.6 Games (from Designing for games)

- [MUST] Jump into gameplay: no lengthy download/tutorial/permission walls before first fun; tutorials as reference, not prerequisite.
- [MUST] Permissions in-context (ask when the scenario needs the sensor/data) with a visible rationale.
- [AVOID] Rating prompts early — only after quality time spent.
- [MUST] Legibility everywhere: contrast + minimum text size per display; 44×44pt buttons; dynamic/relative layouts, [NEVER] fixed layouts; vector/resolution-independent art.
- [MUST] Welcome everyone: never color-alone for meaning; subtitles/descriptions for audio; options for text size, effects, motion, interactions.

## 3. Foundations

### 3.1 Accessibility — design for everyone, day one

Source: <https://developer.apple.com/design/human-interface-guidelines/accessibility>
Apple HIG: an accessible interface is Intuitive (familiar, consistent), Perceivable (never one single channel), Adaptable (respects system settings + personalization).

- [MUST] Text/icons resizable by the user; check contrast in ALL modes (light/dark/increased-contrast). [VALUES] Minimum contrast 4.5:1 for text (see §3.5).
- [MUST] Never rely on a single sense: pair audio cues with haptics/visuals; provide captions/subtitles/audio-descriptions/transcripts for media.
- [MUST] Touch targets ≥ platform minimum (48dp Android) AND treat spacing as important as size; core actions via ≥2 input types (touch + keyboard/switch/voice); frequent actions = simplest gesture, [NEVER] custom multi-finger gestures for repeats.
- [MUST] Label every interactive element for screen readers (TalkBack); don't break system keyboard shortcuts; support full keyboard access.
- [MUST] Cognitive: consistent, memorable interactions; prefer system gestures over custom ones; [NEVER] auto-dismiss on a timer (use explicit dismiss); [NEVER] autoplay audio/video without visible start/stop + global opt-out; confirm twice before hard-to-recover deletes.
- [MUST] Respect reduce-motion / dim-flashing-lights: replace axis transitions with fades, kill bounce/parallax/z-depth/blur animations, dim flashes.
- Android/Tempo: test with TalkBack + Switch Access + 200% font + high-contrast + reduced motion on a real device.

### 3.2 App icons — unique, simple, centered

Source: <https://developer.apple.com/design/human-interface-guidelines/app-icons>

- [SHOULD] Simple background (solid/gradient) that spotlights one primary glyph; filled overlapping shapes; illustration over photo; [NEVER] screenshots, stock UI replicas, or hardware replicas in the icon.
- [MUST] Primary content centered (masking-safe); crisp vector edges (no feathered/soft edges that break system light/shadow); avoid hairline strokes and sharp corners that die at small sizes.
- [AVOID] Words in icons (no "Watch"/"Play"/"New"); mnemonic letters OK if essential.
- [SHOULD] Keep all appearance variants (dark/tinted/mono) recognizably the same icon — [NEVER] swap elements between variants; [NEVER] near-clone another app's icon; [NEVER] black background (watch tiles).
- [VALUES] sRGB / Gray Gamma 2.2 / Display P3; PNG for raster; vector-first. Android/Tempo: adaptive icon (foreground + bg layers), monochrome variant for themed icons.

### 3.3 Branding — defer to content

Source: <https://developer.apple.com/design/human-interface-guidelines/branding>

- [MUST] Branding always defers to content: refined, unobtrusive, never distracting.
- [NEVER] Use the launch screen as branding (it flashes by too fast) — brand the welcome/onboarding screen instead.
- [NEVER] Put platform trademarks in app name/artwork. Android/Tempo: same with Android/Google marks.

### 3.4 Color — judicious, semantic, never sole carrier

Source: <https://developer.apple.com/design/human-interface-guidelines/color>

- [NEVER] One color = two meanings. [MUST] Every light/dark/high-contrast variant of every color; custom colors ship light + dark + boosted-contrast variants.
- [NEVER] Hard-code dynamic colors; use semantic roles (primary/secondary/tertiary text, separator, accent) as intended — [NEVER] reuse a role for another purpose (separator as text, etc.).
- [MUST] Inclusive color: never color-alone for state/meaning/interactivity — always add icon + label/shape; mind cultural meanings.
- [AVOID] Artwork/translucency wrecking nearby colors — test glass over colorful art.
- Liquid Glass color rules (critical for our identity): glass has NO inherent color — it borrows from content behind it. To emphasize primary actions, color the BACKGROUND, not symbols/text. With colorful backgrounds prefer monochrome toolbars/tab bars; pick accents with strong differentiation; [MUST] keep the resting/default scroll state legible even if colors slide under controls during scroll.
- Android/Tempo: Material `colorScheme` roles (primary/onPrimary/surface/outline…) + dynamic color; define tonal variants for dark + contrast; our glass tint = accent at low alpha over blur, tuned so resting-state contrast passes.

### 3.5 Dark Mode — full support, no app toggle

Source: <https://developer.apple.com/design/human-interface-guidelines/dark-mode>

- [NEVER] Ship an app-specific theme toggle — follow the system setting (one switch for the user, not two). Rare exception: inherently dark experiences (media/reading at night).
- [MUST] Look great + stay legible in both modes; dark variants are NOT simple inversions — re-tune each; [VALUES] keep text contrast ≥ 4.5:1.
- [NEVER] Hard-coded non-adaptive colors. [SHOULD] Slightly darken white-background images so they don't glow in dark mode; verify full-color icons/images in both.
- [AVOID] Adding transparency to colored states that sit over variable backgrounds (color will fluctuate).
- Android/Tempo: `uiMode` night resources / Material dark scheme; test OLED true-black vs dark-gray deliberately.

### 3.6 Icons (in-app) — one concept, optical balance

Source: <https://developer.apple.com/design/human-interface-guidelines/icons>

- [MUST] One icon = one instantly-understood concept; optically (not geometrically) centered/balanced; match visual weight across the set.
- [SHOULD] Gender-neutral, culture-neutral, localizable; abstract glyph for "text passage"; provide mirrored version for RTL (see §3.13).
- [SHOULD] Vector-first (auto-scales); PNG only where effects demand it, then ship all densities. [NEVER] Apple-hardware replicas (Android: same for Pixel/hardware art).
- [AVOID] Custom selected/unselected icon pairs inside standard components — let the component handle state.

### 3.7 Images — scale, format, device truth

Source: <https://developer.apple.com/design/human-interface-guidelines/images>

- [MUST] Ship resolution-independent art at all scale factors; [MUST] test on real devices, not just previews.
- [SHOULD] Vector for icons/flat art; PNG for effects-heavy raster; honor color profiles (wide-gamut vs sRGB).
- [AVOID] Transparency bloat on constrained surfaces; composite onto solid bg when the backdrop is fixed. [AVOID] Bitmap art where the system will upscale it (use vectors).
- Android/Tempo: WebP/AVIF + VectorDrawable; `nodpi` discipline; test xxhdpi small screens.

### 3.8 Immersive experiences (spatial) — minimum effective immersion

Source: <https://developer.apple.com/design/human-interface-guidelines/immersive-experiences>

- [SHOULD] Launch flat (Shared Space equivalent); immerse per-moment, never by default; prefer subtle passthrough tints, [NEVER] bright/dramatic ones.
- [MUST] Comfort: content in field of view, gentle motion, explicit enter/exit immersion controls (never force system gestures to escape); [NEVER] sudden/jarring immersion transitions; [NEVER] encourage physical movement while immersed; bring objects to people, not people to objects.
- [SHOULD] Environments: calm (low motion/contrast) for focus tasks; ground plane always; quality concentrated where attention goes; lower/stop soundscapes when other audio plays.
- Android/Tempo: applies to any full-screen immersive mode (viewer, AR, game): same comfort + explicit exit rules.

### 3.9 Inclusion — put people first

Source: <https://developer.apple.com/design/human-interface-guidelines/inclusion>

- [MUST] Review words AND images across perspectives (age, gender, race, sexuality, ability incl. temporary/situational disability, language/culture, religion, education, class). Goal = welcoming, not merely inoffensive.
- [SHOULD] Welcoming language: plain words over jargon (define terms if unavoidable); reserve "we/our" for the company, not faux-intimacy; humor only with care.
- [MUST] Gender: default to neutral nouns/pronouns; customizable avatars/characters; ask gender only when legally/medically needed — then offer nonbinary/self-identify/decline-to-state.
- [NEVER] Stereotyped depictions (occupations, heroes/villains, families) or assumption-laden flows (e.g. "first car", "college subject" security questions).
- [MUST] Disability: each disability is a spectrum; never assume it blocks desire to use the product; represent people with disabilities; never use disability as a negative metaphor; familiar consistent interactions perceivable by sight/hearing/touch.

### 3.10 Layout — hierarchy, edges, adaptability

Source: <https://developer.apple.com/design/human-interface-guidelines/layout>

- [MUST] Visual hierarchy first: most important info instantly visible; [NEVER] crowd it with nonessentials; keep content and controls clearly distinct (controls float OVER content, not on the same plane).
- [MUST] Full-bleed: backgrounds/art extend to display edges; scrollable layouts run edge to edge and top to bottom.
- [MUST] Adaptability matrix — layout [MUST] survive: all screen sizes/densities · portrait+landscape · notches/cutouts/Dynamic Island · multi-window/foldables · 85%–200% font scales · RTL locales (see §3.13). Respect system safe areas/margins; [NEVER] fixed layouts — use constraint/relative layouts.
- [SHOULD] Prefer scroll-edge effects (blur/fade) over hard backgrounds to separate scrolling content from control areas.
- [SHOULD] Games: full-bleed preferred; letterbox/pillarbox optional; full-width buttons must harmonize with hardware curvature + safe areas; keep status bar visible.
- [SHOULD] Shrinking space: hide tertiary columns (inspectors) first; convertible tab-bar↔sidebar navigation; minimize surprise reflows between min/max sizes.
- [AVOID] Controls/critical info at window bottom (desktop) or inside camera housing; >2–3 side-by-side controls on narrow screens; focus-state overlaps on TV grids (space unfocused rows generously).

### 3.11 Materials + Liquid Glass — THE glass spec (core of our identity)

Source: <https://developer.apple.com/design/human-interface-guidelines/materials>
Apple HIG: a material creates depth/layering/hierarchy by letting background color pass through; two families — **Liquid Glass** (dynamic, for the functional layer) and **Standard materials** (for structure inside the content layer).

- [MUST] Liquid Glass = a distinct functional layer for CONTROLS + NAVIGATION (tab bars, sidebars, toolbars) floating above content. Content scrolls/peek-through beneath → dynamism + depth WITH legibility.
- [NEVER] Liquid Glass inside the content layer (app backgrounds, cards, body) — causes complexity + confused hierarchy. Use Standard materials there. Sole exception: transient in-content controls (sliders, toggles) may take glass appearance WHILE activated to signal interactivity.
- [MUST] Sparingly: glass draws attention to content beneath; multiple custom glass controls distract. Limit to the most important functional elements.
- [VALUES] Two variants: **Regular** (blurs + adjusts background luminosity; default; for text-heavy chrome: alerts, sidebars, popovers) and **Clear** (highly translucent; ONLY over visually rich media backgrounds like photos/video for immersion).
- [MUST] Clear-glass legibility: bright content behind → add dark dimming layer at 35% opacity; dark content → no dimming needed; scroll-edge blur/fade where content slides under chrome.
- [MUST] Glass respects user settings: reduce-transparency and increase-contrast [MUST] collapse glass to solid/opaque. [NEVER] ship glass without that fallback.
- [MUST] Choose materials by SEMANTIC use-case, never by the color they happen to impart (system settings change appearance). Always pair materials with vibrant foreground colors (label/fill/separator vibrancy levels; default = highest contrast; [AVOID] quaternary text on thin/ultraThin).
- [VALUES] Thickness ladder (content layer): ultraThin < thin < regular (default) < thick. Thicker = contrast for fine text; thinner = context preservation. visionOS mapping: thin = interactive/selected, regular = section separation (sidebar/grouped lists), thick = dark element over regular.
- [SHOULD] Prefer translucency over opacity in floating surfaces (opaque blocks feel constricting); [NEVER] strip default material backgrounds from modal sheets.
- Android/Tempo mapping: Regular ≈ `scrim/blur 24–40dp radius + surface @72–82% + 1dp outline white@12%`; Clear ≈ `blur + surface @28–40%` restricted to media overlays + 35%-black dimmer on bright art; our subtle-color blend = tint the glass with accent/tonal color at LOW alpha (8–16%) and [MUST] re-verify contrast at resting scroll state + dark mode + contrast-boosted mode. Test glass over: bright photo, dark photo, busy pattern, pure white, pure black.

### 3.12 Motion — purposeful, interruptible, optional

Source: <https://developer.apple.com/design/human-interface-guidelines/motion>

- [NEVER] Motion for its own sake; [NEVER] motion as the ONLY carrier of information.
- [MUST] Reversible + consistent physics (dismiss mirrors reveal); [AVOID] motion on frequently-repeated interactions; [NEVER] block input waiting for an animation (interruptible, skippable).
- [SHOULD] 3 comfort edits when reduce-motion is on: tighten springs (no bounce) · track gestures directly · fades instead of x/y/z moves, depth changes, and blur in/out.
- [AVOID] Sustained oscillation (~0.2 Hz is nauseating), peripheral motion, rotating worlds without a stationary frame of reference.
- Android/Tempo: 150–300ms standard easing (emphasized decelerate), shared-element/fade for relocations, animated icons only to signal state change.

### 3.13 Privacy — paramount, in-context, minimal

Source: <https://developer.apple.com/design/human-interface-guidelines/privacy>

- [MUST] Transparency + protection: disclose practices (store listing + in-app), respect opt-outs (tracking, email relay), prefer on-device processing over server round-trips.
- [MUST] Permission timing: ask ONLY when the feature needing it is used ([NEVER] at launch unless the app can't function without it); pre-alert screens [MUST] contain no escape actions and [NEVER] precede tracking prompts with misleading custom screens.
- [MUST] Permission copy: sentence case, active voice, ends with period, states the why.
- [MUST] Auth/data hygiene: [NEVER] passwords in plain text; [NEVER] custom auth schemes — use system passkeys/biometric/autofill; sandbox; never assume identity.
- [SHOULD] One-tap scoped grants (e.g. "share current location" button) beat blanket permissions.
- Android/Tempo: runtime permissions + rationale UI + photo-picker/partial-access equivalents; data-safety section parity.

### 3.14 Right-to-left — mirror flows, not everything

Source: <https://developer.apple.com/design/human-interface-guidelines/right-to-left>

- [MUST] Mirror layout direction, list alignment, control order, and progress/counting direction; back button points WITH reading flow (right in RTL).
- [NEVER] Reorder digits inside a number (phone/credit-card stay identical); [NEVER] flip photos, logos, universal marks (✓), clocks, or real-world objects; directional icons (forward/back, sound waves) DO flip — prefer re-drawing over naive mirroring for complex glyphs.
- [SHOULD] Localized icon variants for text-bearing icons; watch Arabic/Hebrew size next to uppercased Latin.
- Android/Tempo: `start/end` (never `left/right`), `autoMirrored` drawables, test in Arabic + Hebrew.

### 3.15 Symbols (SF Symbols → our icon font)

Source: <https://developer.apple.com/design/human-interface-guidelines/sf-symbols>

- [SHOULD] One consistent symbol set, aligned to text across weights/sizes; outline variant beside text (lists/toolbars), fill variant for emphasis/selection (tab bars, swipe actions); enclosing shapes (circle/square) rescue small-size legibility.
- [MUST] Symbol animation [MUST] communicate state change only (bounce = something happened; replace styles: down-up = change, up-up = forward progress, off-up = next action); [NEVER] ambiguous/cumulative animation; match app tone.
- [NEVER] Symbols (or confusing lookalikes) in app icons/logos/trademarks. Custom glyphs [MUST] be simple, recognizable, inclusive, action-direct; test in motion at all sizes.
- Android/Tempo: Material Symbols (rounded to match our glass softness) with same outline/fill discipline; variable/animated icons only for state.

### 3.16 Spatial layout (3D/AR discipline)

Source: <https://developer.apple.com/design/human-interface-guidelines/spatial-layout>

- [MUST] Anchor content in the world, [NEVER] to the head; keep it in field of view; depth used to clarify/delight, not everywhere.
- [NEVER] Let controls overlap interactive elements; reserve direct-touch gestures for nearby, short, inspectable objects.
- Android/Tempo: same for AR/3D viewers; fixed-scale only for true-size objects.

### 3.17 Typography — legible, hierarchical, resizable

Source: <https://developer.apple.com/design/human-interface-guidelines/typography>

- [MUST] Honor platform default AND minimum sizes (custom fonts included); if hard to read → larger size, more contrast, or a legibility-optimized face. [AVOID] Ultralight/Thin/Light weights, especially small.
- [MUST] Hierarchy survives font scaling: relative distinctions kept; enlarging content size must NOT blow up chrome (tab titles stay put); stacked layout + fewer columns at large sizes; [NEVER] truncate in scrollables without a full-text view.
- [AVOID] Tight leading for 3+ lines even in short spaces; render depth-free, billboarded (face-viewer) text in 3D; [AVOID] shadows for contrast; bold when text floats without background.
- Android/Tempo: system/default sans with tabular numerals for data; `sp` everywhere; test 85%/100%/130%/200%; hierarchy via size+weight+color, never color alone.

### 3.18 Writing — every word earns its place

Source: <https://developer.apple.com/design/human-interface-guidelines/writing>

- [MUST] Check every word; one idea per screen; buttons/links = verbs ("Send" beats "Let's do it!", [NEVER] "Click here" — screen readers need descriptive links).
- [MUST] Errors: prevent first; when shown → adjacent to the problem, blame-free, with the fix ("Use only letters" beats "Invalid name"); link directly to the setting instead of describing its location; hints show format (<name@example.com>).
- [SHOULD] Match device context: tap (touch) vs click (pointer); brevity on small/far screens; shared screens (TV) address the room, not "you"; empty states carry voice + utility but [NEVER] hide crucial info that vanishes.
- [AVOID] "We" ambiguity, cuteness over clarity, undefined jargon.

## 4. Patterns (25 common tasks — when + how)

Base: <https://developer.apple.com/design/human-interface-guidelines/patterns>

### 4.1 Charting data (`…/charting-data`)

- [SHOULD] Chart only to analyze/compare (trends, states, categories); plain lists/tables (scrollable, searchable, sortable) when you just deliver data. Reveal dense data/functionality gradually.
- [SHOULD] Common chart types; big enough for labels + interaction; consistent type/style/colors across same-purpose charts (deviate only to flag real differences; one dataset = one chart language).

### 4.2 Collaboration & sharing (`…/collaboration-and-sharing`)

- [SHOULD] Simple, responsive, content-first; surface only essential collaborators/info; deep-linkable invites (universal/app links); event updates via messaging/notifications.

### 4.3 Drag and drop (`…/drag-and-drop`)

- [MUST] Always offer a non-drag alternative (not everyone can drag); cross-app drops = copy; [SHOULD] multi-select drag, undoable drops, confirm-before-irreversible; offer highest→lowest fidelity representations; stable, non-flickering drag image; clear drop feedback (incl. failed-drop animation).
- [SHOULD] Pointer-shape change over valid targets; no forced pause between select and drag.

### 4.4 Entering data (`…/entering-data`)

- [NEVER] Ask for data you can pre-fill (settings) or request via permission (location/calendar); [MUST] gate progress honestly (Next/Continue enabled only when required input valid); prefer choices (pickers/menus) over typing; numeric formatters for numbers; inline correction over post-form error hunts.

### 4.5 Feedback (`…/feedback`)

- [MUST] Cover 4 cases: current status · success/failure of important actions · warning before negative consequences · path to fix mistakes. Integrate status inline; interrupt ONLY for preventable data loss ([NEVER] warn when loss is the expected outcome).
- [AVOID] Indeterminate spinners on glanceable surfaces (watch/widgets).

### 4.6 File management (`…/file-management`)

- [MUST] Autosave continuously (periodic + on close/switch); [NEVER] force explicit save; work is preserved unless user cancels/deletes; "Edited" marker cleared on save; unsaved state always visible when autosave is off.
- [SHOULD] Previews for custom types; document launchers uncluttered (title + 2 actions, gentle accessory animation).

### 4.7 Going full screen (`…/going-full-screen`)

- [SHOULD] Offer for games, media, deep focus tasks; user-invoked entry; subtle mode transitions; essential controls persist or reveal easily; auto-pause media/game on exit; [NEVER] auto-end the mode on app switch; keep Dock/system access; defer system gestures (two-swipe exit) in games.

### 4.8 Launching (`…/launching`)

- [MUST] Interactive within ~2s; launch screen = blank-canvas match of first screen (orientation + appearance), [NEVER] text, logos, splash/about content; restore previous location (no retracing); splash/branding belongs to onboarding, not launch.

### 4.9 Live-viewing apps (`…/live-viewing-apps`)

- [MUST] Live content distinguishable from VOD at a glance; live in first tab (1 tap to play); playback primary, record/restart/download secondary; leaving live context stops audio; program guide opens on now/channel/time with fast paging + favorites; DVR with auto-storage management.

### 4.10 Loading (`…/loading`)

- [MUST] Best load = invisible load (pre-schedule downloads post-install/update); else skeleton placeholders swapped for real content; [VALUES] determinate bar when time known, indeterminate only when unknown; >2s video loads get black screen + centered spinner, dismissed the moment playback can start.
- [AVOID] Loaders on glanceables; branded loading theater in utilities.

### 4.11 Managing accounts (`…/managing-accounts`)

- [NEVER] Require accounts unless core function needs one — value first, signup later, with a friendly benefits line. Prefer system sign-in (Sign in with Apple → Android: Credential Manager/passkeys); name the method on the button ("Continue with …"); [NEVER] app-specific biometric opt-in toggles; [NEVER] "passcode" for account auth.
- [MUST] In-app account DELETION (not just deactivate), discoverable, as easy as signup; else a direct deletion link (never buried in legal pages); honor regional deletion law; disclose subscription-billing consequences.
- [SHOULD] Shared/TV screens: authenticate via second device; don't re-ask profiles per session.

### 4.12 Managing notifications (`…/managing-notifications`)

- [MUST] Four urgency tiers and nothing else: passive (at leisure) · active/default (now-ish) · time-sensitive (within the hour, user can demote) · critical (health/safety, rare). [NEVER] high urgency for low value.
- [NEVER] Marketing without explicit opt-in + in-app management screen; [NEVER] marketing at time-sensitive/critical priority or through Focus/summary breaks.
- Android/Tempo: channels with matching importance; no promo in high-importance channels, ever.

### 4.13 Modality (`…/modality`)

- [MUST] Modal ONLY to: deliver critical info · confirm/modify last action · complete one narrow task without losing context · focus/immerse. [NEVER] "app inside an app"; single path, no fake-dismiss buttons; always an obvious exit; confirm before discarding user content; [NEVER] stacked alerts (one at a time).

### 4.14 Multitasking (`…/multitasking`)

- [MUST] Save/restore context at any moment (you never know when the user leaves); pause attention-requiring activity (media/game) on switch-away and resume seamlessly; duck/pause audio correctly on interruption (indefinite for primary audio, duck-and-restore for transient); [NEVER] notify for routine background completions — let users discover on return.

### 4.15 Offering help (`…/offering-help`)

- [MUST] Help = contextual, dismissible, about YOUR task (never explain standard components); device-correct verbs/art; [VALUES] tips ≤2 sentences, feature with >3 steps is too complex for a tip; tooltips = fragments without the control's own name (long tooltip = redesign the UI).

### 4.16 Onboarding (`…/onboarding`)

- [MUST] Learn-by-doing beats slideshows; prerequisite flows brief, skippable, never reshown (but findable); [NEVER] teach the OS/device, license walls, or download waits; permissions only if the app can't function without them; ratings/purchases only after real usage.

### 4.17 Playing audio (`…/playing-audio`)

- [MUST] Honor silent switch + system volume (adjust YOUR mix, never master volume); never halt other apps' audio unless you must (and flag so they resume); [NEVER] redefine standard transport controls; custom player chrome only for commands the system lacks; vary repetitive sounds (pitch/volume jitter).

### 4.18 Playing haptics (`…/playing-haptics`)

- [MUST] 1 haptic = 1 cause (learnable mapping); haptics complement, never replace, visual/audio feedback; short/transient for discrete events ([AVOID] long/continuous, esp. stylus); user kill-switch that costs nothing; [NEVER] vibrate through camera/gyro/mic work. Use system patterns (notification/impact/selection) over custom ones.

### 4.19 Playing video (`…/playing-video`)

- [MUST] System player unless truly custom (then mirror its behavior); original aspect ratio always (aspect-fill only for wide 2:1–2.40:1, aspect-fit otherwise); transport/content actions ≤2 steps; [NEVER] mixed audio sources; jump black-screen→content (no splash/intro barriers; no "resume?" prompts); >2s loads = black + centered spinner only.
- [AVOID] Big overlays (small logo/countdown max; translucent SDR on retention-prone screens); no immersive autoplay; keep controls unoccluded.

### 4.20 Printing (`…/printing`)

- [SHOULD] System print UI; app options as custom categories; preview effects; store doc-modified settings; hide advanced behind disclosure; [NEVER] re-implement system options (orientation, order…).

### 4.21 Ratings & reviews (`…/ratings-and-reviews`)

- [NEVER] Ask on first launch/onboarding, mid-task, or mid-game. [VALUES] ask after real engagement, ≥1–2 weeks between prompts. (Store listing always allows rating — prompts are a privilege.)

### 4.22 Searching (`…/searching`)

- [MUST] Always show scope context (which mailbox/folder/library); scope-visible field + recent/suggested searches; system-wide indexing where supported; prefer system open/save views.

### 4.23 Settings (`…/settings`)

- [MUST] Great defaults beat settings pages (auto-detect performance, accessories, appearance). [NEVER] duplicate system settings; [NEVER] ask in settings what you can sense.
- [SHOULD] Settings = rarely-changed options only; task-specific options live IN the task; deep-link to system settings instead of describing paths.

### 4.24 Undo & redo (`…/undo-and-redo`)

- [MUST] Undo/redo for all reversible work; [NEVER] arbitrary limits; batch-undo for related micro-adjustments; standard symbols in toolbar; [NEVER] redefine platform undo gestures.

### 4.25 Workouts / live sessions (`…/workouts`)

- [MUST] Session screen = only session-relevant data, glanceable in motion (large type, high contrast, key metric first); unmistakable start/stop feedback + easy pause/resume/stop; summary with progress rings; [NEVER] upsell/navigate elsewhere mid-session.

## 5. Components (system familiarity — use standard, customize sparingly)

Base: <https://developer.apple.com/design/human-interface-guidelines/components>
Apple HIG: standard components give people a familiar, consistent experience. Customize only with purpose; keep behavior predictable.

### 5.1 Content

- **Charts** (`…/charts`): common types; axes sized for labels + touch (expand hit area to full plot for scrubbing); fixed range when bounds are meaningful, dynamic when data varies; [NEVER] color-alone encoding; interaction optional for critical info; animate changes AND announce them for VoiceOver; accessibility labels = values + context ("June 6, 60 minutes"), no subjective words ("rapidly"), no color descriptions; glanceable-only on watch.
- **Image views** (`…/image-views`): display only — interactivity belongs on a Button; icons → symbols, not image views; prescale to view size, uniform sizes for performance; text-over-image needs shadow/scrim contrast.
- **Text views** (`…/text-views`): multiline/styled, optionally editable; content-appropriate keyboard; copyable useful strings (errors, codes, IPs); test with bold-text accessibility.
- **Web views** (`…/web-views`): embed brief web content in-context is fine; [NEVER] build a browser inside the app.

### 5.2 Layout & organization

- **Boxes** (`…/boxes`): small vs container; padding/alignment for sub-grouping; title colon only in settings panes.
- **Collections** (`…/collections`): visual grids; [AVOID] look-at-me custom layouts; text-heavy → table instead; animate insert/delete/reorder; [NEVER] re-layout under the user's eyes except on explicit action.
- **Column views** (`…/column-views`): deep hierarchies with frequent level-hopping (no sorting needed); show selected-item preview when leaf reached.
- **Disclosure controls** (`…/disclosure-controls`): frequent actions on top, advanced hidden; labels name the hidden content ("Advanced Options").
- **Labels** (`…/labels`): short static text; style changes must keep legibility; copyable useful strings.
- **Lists & tables** (`…/lists-and-tables`): default for text rows; nouns/short phrases, title case, no period; context header when no column headings; huge text → alternative layouts; alternating rows on dense desktop tables; hierarchy → outline view; watch detail = short + swipeable.
- **Lockups/cards** (`…/lockups`): one interactive unit; focus-expansion needs breathing room (no overlap/displacement).
- **Outline views** (`…/outline-views`): hierarchical data only; always multi-column headings; sortable/resizable columns; centered-ellipsis truncation; search for long trees.
- **Split views** (`…/split-views`): adjacent panes; resizable with visible dividers, hideable panes for focus; narrow → hide tertiary first; supplementary info beats new windows; sheets only for blocking mini-tasks.
- **Tab views** (`…/tab-views`): mutually exclusive, self-contained panes; [VALUES] ≤6 tabs (more → menu/pop-up); 1-tap switching (never a 2-tap pop-up for tabs); controls affect their own pane only.

### 5.3 Menus & actions

- **Buttons** (`…/buttons`): style + content (icon and/or text) + role (normal/primary/cancel/destructive). [VALUES] hit region ≥44×44pt (48dp Android; gaze/3D: 60). Every custom button gets a press state. Text beats cryptic icons; labels = verb-first title case ("Add to Cart"). [NEVER] primary role on destructive actions; colorful content → monochrome button labels. Watch: full-width primary; floating buttons: circular/capsule, ≥60pt center spacing, thin-material bg on glass.
- **Activity/share sheets** (`…/activity-views`): context-appropriate actions only, verb-first titles, no company names, no duplicates of system actions; notify on failure, never on success.
- **Context menus** (`…/context-menus`): most-likely commands for THIS item (not advanced/rare); every item also lives in main UI; hide (don't dim) unavailable; ≤3 groups; short labels; graphical preview that animates cleanly.
- **Edit menus** (`…/edit-menus`): standard reveal gestures only; context-relevant commands (no Copy without selection, no Paste with empty clipboard); no confirmations (undo covers it); don't duplicate with custom controls.
- **Home-screen quick actions** (`…/home-screen-quick-actions`): predictable, stable; short localized titles (no app name); symbols not emoji. Android: app shortcuts, same rules.
- **Menus** (`…/menus`): title case, ellipsis when more info needed ("Save As…"); frequent first, related grouped; submenus for repeated terms (≤5 items, never the only path, single-modifier reveal); toggled items show CURRENT state + checkmarks; empty group → menu stays open-able.
- **Pop-up buttons** (exclusive choice, space-saving; "Custom…" for rarities) vs **Pull-down buttons** (actions menu; primary actions stay visible, ≥3 items to justify the tap; "More" for overflow) — never confuse the two.
- **Menu bar** (`…/the-menu-bar`): standard order (App·File·Edit·Format·View·…·Window·Help); list every command somewhere in UI (menu bar is no dumping ground); disable, don't hide, unactionable items; File: Duplicate > Save As, autosave, no paths in recents; dynamic items need a non-dynamic path too.
- **Toolbars** (`…/toolbars`): deliberate few items; standard symbols (no "Back"/"Close" text); leading = navigation/sidebar/title (fixed), center = common controls (customizable, collapses to overflow), trailing = inspectors/search/More/Done (always visible); colorful content → monochrome; custom components concentric with bar corners; hideable for focus.

### 5.4 Navigation & search

- **Search fields** (`…/search-fields`): placement = tab (always available) / toolbar (view-scoped) / inline above the list it filters (pin on scroll); scope bars + tokens + filters + categorized results; dedicated areas autofocus; no heavy typing on TV.
- **Sidebars** (`…/sidebars`): top-level collections; familiar symbols, purposeful icon color; user-hideable but [NEVER] hidden by default; >2 levels → add content-list pane; no critical actions at the bottom.
- **Tab bars** (`…/tab-bars`): top-level sections, always visible, [NEVER] disabled/hidden; fewer tabs win; complex IA → adaptive tab-bar↔sidebar; filled icons; badges for critical counts only; colorful content → monochrome bar (see §3.11).
- **Path controls / token fields**: paths for file context; tokens = editable filter chips with suggestion pairing.

### 5.5 Presentation (modality ladder — least interruptive first)

- **Sheets** (`…/sheets`): scoped in-context tasks; Cancel (discard) + Done (save) paired, [NEVER] Cancel+Done+Back trio; complex/long flows → full-screen or window instead; swipe-to-dismiss expected; confirm before discarding created content.
- **Alerts** (`…/alerts`): critical + actionable + (usually) unexpected ONLY. [NEVER] for info, startup states, or common undoable deletes. Title = what happened (never "Error"/codes, ≤2 lines, no blame); buttons = verbs ("Delete Photo"), default trailing/top, Cancel present for destructive, [NEVER] Cancel-as-default, "OK" only for pure info; one alert at a time; no scrolling alerts.
- **Action sheets** (`…/action-sheets`): choices clarifying an initiated action; short titles; use sparingly; ≤4 buttons on small surfaces.
- **Popovers** (`…/popovers`): transient, arrow-anchored to source; wide screens only (compact → sheet); never cascaded; nothing above except alerts; auto-close saves work; warn via alerts, not popovers.
- **Panels/inspectors** (`…/panels`): floating supplementary controls; sliders/steppers over typing; no minimize, no Window-menu listing; HUD only for media/full-screen, never covering what it adjusts.
- **Scroll views** (`…/scroll-views`): elastic indicators; obvious overflow; [NEVER] nested same-orientation scrollers; auto-scroll to selection/caret/pointer; automatic scroll-edge style (legibility-tested); no indicator+page-control redundancy on one axis.
- **Page controls** (`…/page-controls`): flat peer lists only ([VALUES] ≤10 dots, else grid); bottom-centered; simple equidistant indicators, ≤2 styles, system-colored.
- **Windows** (`…/windows`): primary (nav+content) vs auxiliary (one task + close); fluid resize with min/max; [NEVER] new-window-by-default; system frames only; nothing critical in bottom bars; inactive windows visibly recede (no materials).

### 5.6 Selection & input

- **Pickers** (`…/pickers`): medium-long lists (short → pull-down, huge → searchable list); reduced minute granularity; compact/inline/wheels per context.
- **Segmented controls** (`…/segmented-controls`): grouped functions OR selection state — [NEVER] both; text-only or icons-only; subviews yes, app sections no (that's tabs); keep focusables clear on TV.
- **Sliders** (`…/sliders`): supplement with field+stepper; labeled, tick-marked, live feedback; [NEVER] for system volume.
- **Steppers** (`…/steppers`): small tweaks (pair with field for jumps); always show the affected value; Shift-click acceleration on desktop.
- **Text fields** (`…/text-fields`): small specific input (large → text view); secure fields for secrets; logical tab order; locale-aware validation timing (email on field-exit, username/password pre-submit); minimize typing (offer buttons/choices); option-lists beat typing on watch.
- **Toggles/switches/checkboxes/radios** (`…/toggles`): toggle = binary state ONLY (state difference obvious beyond color); list-row switches need no label; accent color with contrast; hierarchy → checkboxes, >2 exclusive → radios (>5 → pop-up), single on/off → checkbox; mixed-state → checkbox.
- **Color wells** (`…/color-wells`): system color picker for familiarity. **Combo boxes**: choices + typing, items ≤ field width. **Digit entry (PIN/OTP)**: full-screen, secure-masked, purpose-titled. **Image wells**: default image restored on clear, standard copy/paste. **Virtual keyboards**: right keyboard per field, meaningful Return key (Search/Go…), [NEVER] duplicate system keys or stuff help into the keyboard.

### 5.7 Status

- **Progress** (`…/progress-indicators`): determinate preferred (even-paced, no style-switching mid-task, concrete titles not "Loading…"); indeterminate only for truly unknown waits; spinners unlabeled; auto-refresh over manual (refresh titles describe content, not the gesture).
- **Gauges** (`…/gauges`): value-in-range with gradient/zone coloring; continuous style for large ranges.
- **Ratings** (`…/rating-indicators`): equidistant stars; custom symbols only if unmistakably "rating".
- [NEVER] Platform-reserved indicators (e.g. Activity rings) for other data — see below.

### 5.8 System experiences

- **Widgets** (`…/widgets`): glanceable, dynamic-but-not-realtime content, one size done well > all sizes; 16pt standard margins, rounded-corner-safe; deep-link every interaction to its content (no mini-apps); fresh-or-timestamped; survive light/dark/tinted/clear/desaturated appearances; lock-screen = info, not launcher; no mirroring widget UI inside the app.
- **Live Activities** (`…/live-activities`): short/medium events (≤8h), info-only related to the event; [NEVER] ads; sensitive content → innocuous summary + tap for details; alert only for must-know updates (no parallel push for same update); one rotating layout over many; end immediately + proportional dismissal; tap lands on the exact detail.
- **Notifications (UI)** (`…/notifications`): glanceable brief titles, full sentences, system truncation; [NEVER] private content, [NEVER] nag repeats, [NEVER] in-app to-do instructions; actions = time-saving (never just "open app"); badges = unread counts only (never weather/scores/dates), never the sole channel, never mimicked by custom art; custom sounds short + non-essential.
- **App Shortcuts / voice** (`…/app-shortcuts`, `…/siri`): key functions system-wide; brief spoken phrases incl. app name; static info → Snippets (≤400pt, short, deep-link for more), evolving events → Live Activities; [NEVER] ads/marketing/upsell in voice content; audio-only fallback assumed.
- **System controls** (`…/controls`): symbol must stand alone (title/value may be absent); config on first add; redact on lock screen; auth-gate security actions; beyond-capture camera work requires unlock.
- **Complications** (`…/complications`): fresh relevant data > launcher; multiple families; tinted-mode-safe (never color-alone); [NEVER] repeat system alerts or show in notifications.
- **Status bars** (`…/status-bars`): always readable; scroll-edge blur behind; hide only for full-screen media (users need clock/signal).
- **Reserved system visuals**: [NEVER] replicate/modify Activity rings, [NEVER] ring-ify other data, black bg + margins intact; TV Top Shelf = new/exciting content, no fake interactivity.

## 6. Inputs (support many; assume none)

Base: <https://developer.apple.com/design/human-interface-guidelines/inputs>

- **Gestures** (`…/gestures`): [NEVER] redefine standard gestures or invent unique ones for standard actions; [NEVER] assume ability — every gesture action gets a button/keyboard/voice path; custom gestures must be discoverable, simple, distinct, optional, and conflict-free with system UI; teach in-context, test in real use.
- **Focus & selection** (`…/focus-and-selection`): every element focusable by keyboard/remote; [NEVER] move focus without user action; custom focus effects only if essential; focused items need large-size assets + non-overlapping expansion.
- **Keyboards** (`…/keyboards`): [NEVER] repurpose standard shortcuts; custom = ⌘-led (Android: Ctrl-led), listed modifier order, discoverable; frequent unique action earns its own shortcut.
- **Pointing devices** (`…/pointing-devices`): [NEVER] redefine system trackpad gestures; touch≡pointer results (Option-drag duplicates either way); system pointer effects for standard elements; custom pointer instantly legible, no instructional text; magnetism/highlight for tiny targets.
- **Game controls** (`…/game-controls`): always support default touch AND controllers; [VALUES] frequent ≥44×44pt, minor ≥28×28; press states visible+tactile; hide movement UI until touch (floating thumbstick at touchdown, direct-drag camera); one control per multi-press combo; symbols not "A/X/R1"; auto-detect controllers, graceful connect prompts.
- **Stylus/handwriting** (`…/apple-pencil-and-scribble`): mark instantly, no modes/buttons first; direct manipulation only; controls repositionable out from under hands; hover = preview only ([NEVER] destructive on hover); write-anywhere fields stay still while writing (grow before/after, never during); undo/redo visible in compact modes.
- **Hardware buttons/dials** (`…/action-button`, `…/digital-crown`, `…/camera-control`, `…/remotes`): verb-first names ("Start Race"); back up every dial/button with touch UI + visual feedback; second-press flows forward (stop lives in UI); TV: gesture follows focus direction, ignore accidental taps during playback, Back opens pause menu in games.
- **Motion/proximity sensors** (`…/gyro-and-accelerometer`, `…/nearby-interactions`): [NEVER] collect without purpose; [NEVER] tilt-to-drive UI outside gameplay (battery + ability); proximity is enhancement, never the only path; implicit hold-guidance over instructions.
- **Gaze/3D input** (`…/eyes`): always multiple input paths; [VALUES] ≥16pt margins / 60pt center spacing between targets; subtle look-cues, no FOV-filling textures; hover delays by purpose (instant = affordance, short = tabs, long = tooltips).
- **Voice/AI input**: see §7 generative-ai/siri rules — human stays in charge, dismiss/retry everything.

## 7. Technologies (integrate, don't re-implement)

Base: <https://developer.apple.com/design/human-interface-guidelines/technologies>
Rule for all: use the system capability (share sheet, player, auth, pay, map…) instead of rebuilding it; hide gracefully on unsupported devices ([NEVER] error walls); permissions in-context with rationale (§3.13).

- **Generative AI** (`…/generative-ai`): human decides, AI proposes — every output dismissible/reversible/retryable; [NEVER] infer personal/cultural attributes (ask instead); diverse test pools vs stereotypes; full value when AI is off/unavailable.
- **Machine learning** (`…/machine-learning`): define the role (complementary like QuickType vs essential like Face ID); calibrate to mistake-cost (anxious health nudge ≠ shrug-worthy music miss); expect slow model iteration — design data/metrics to be changeable.
- **Voice assistant** (`…/siri`): expose personal-context actions (recents, favorites) system-wide; [NEVER] ads/upsell in delivered content; custom responses only when built-ins fail.
- **Sign-in** (`…/sign-in-with-apple`): account setup before method choice; link-existing offered; reuse transaction-known data ([NEVER] re-ask); instant value, no post-signup interrogation. Android: Credential Manager/passkeys, same flow.
- **Payments** (`…/apple-pay`, `…/in-app-purchase`, `…/tap-to-pay-on-iphone`): wallet-pay = primary (not only) option where available, inline in the flow; official buttons unmodified; purchase UI stays in-app-looking (short titles, plain words); merchant/user setup precedes customer-facing steps.
- **Wallet & passes** (`…/wallet`): background-issue predictable passes; batch multi-segment passes; correct dates/voiding; permission before deletion; re-ask [NEVER] after decline.
- **Media casting** (`…/airplay`): full resolution ladders; background/lock playback continues; [NEVER] auto-mirror or kill other apps' audio except for immersive takeover.
- **Maps** (`…/maps`): default style for parity, muted when YOUR data must pop; search + category filters; attribution fixed with padding, never permanently covered.
- **AR** (`…/augmented-reality`): declutter to preserve illusion; 60fps; small/coarse reflections; extra UI in screen-space, persistent controls indirect.
- **Car/driving** (`…/carplay`): setup while parked; works locked and phone-inaccessible; problems deferred to stops; timely content over buttons while driving.
- **Home/IoT** (`…/homekit`): setup·naming·rooms·automations·support surfaced in detail views, not buried settings.
- **Health/research/care** (`…/healthkit`, `…/carekit`, `…/researchkit`): permission every access, privacy-policy URL, [NEVER] custom permission screens or side-channel flows, data strictly need-to-know; [NEVER] touch Activity rings.
- **Sync & continuity** (`…/icloud`): latest-everywhere assumed, no "where is it" UI, no per-doc cloud toggles, user-created content only.
- **Lightweight entry** (`…/app-clips`): one finite task, instantly launchable (QR/NFC/link), trial before commitment. Android: instant-app/clip equivalent.
- **Social play** (`…/game-center`, `…/shareplay`): access point in menus/settings ([NEVER] mid-gameplay); pause under overlays; guest onboarding (login/download/pay) BEFORE the shared moment; trademark terms verbatim.
- **Messaging content** (`…/imessage-apps-and-stickers`): one primary job, instant in compact view, legible rotated/scaled on any background.
- **Audio recognition** (`…/shazamkit`): mic permission with why; mic off the moment sampling ends.
- **Proximity exchange** (`…/nfc`): "scan/hold near" language ([NEVER] "tap/NFC/tag" jargon); in-app scan path for devices without background reading; no object-contact encouragement.
- **Media capture** (`…/live-photos`, `…/photo-editing`): edits apply to the whole capture; [NEVER] split frames/audio apart; still-fallback + share-as-still always offered; cancel keeps work-in-progress.
- **Identity/keys** (`…/id-verifier`): minimum-data requests (age threshold > birthdate); Display-only vs Data-transfer by legal need; no transport-specific symbols.
- **Screen reader** (`…/voiceover`): descriptive labels for every control/image (decorative = hidden), kept current; [NEVER] custom gestures as the only path.
- **Desktop porting** (`…/mac-catalyst`): sensors/rear-camera/ARKit-dependent features don't belong on desktop; pointer+keyboard+windows+toolbars+rich text required.
- **Glanceable hardware** (`…/always-on`): dimmed, static, private; prominence via dimming the secondary, not brightening the primary.

## 8. Tempo mapping — our glass + subtle color, within Apple's rules

Our identity (glassmorphism blended with subtle color) is a *constrained* version of §3.11. Concrete build rules:

1. [MUST] Glass ONLY on: bottom nav/tab bar, top toolbar, FABs/sheets/dialogs chrome, media overlays, focus states. [NEVER] on cards, lists, body, or article backgrounds (use tonal surfaces).
2. [MUST] Two recipes: **Regular glass** (blur + surface @72–82% + hairline light border; all chrome with text) and **Clear glass** (blur + surface @28–40%, media overlays ONLY + 35%-black dimmer when art is bright).
3. [MUST] Our subtle color = accent/tonal tint at 8–16% alpha INSIDE the glass (never on glyphs/text); resting scroll state must pass contrast in light + dark + high-contrast + reduce-transparency (which collapses to solid surface — test it).
4. [MUST] Scroll-edge treatment (blur/fade, not solid bar) everywhere content slides under chrome; monochrome chrome over colorful feeds.
5. [MUST] 48dp targets, verb-first labels, semantic color roles, `sp` type that survives 200%, RTL mirroring, haptic+visual confirmation on primary actions, undo for destructive ones.
6. [AVOID] Tinted glass over already-colorful art; stacked glass-on-glass; glass in empty/loading/error states (clarity first).

## 9. Apple Design site extras (beyond the HIG)

Source: <https://developer.apple.com/design/>

- **Design videos** (developer.apple.com/videos/design/): WWDC design sessions (e.g. "Meet Liquid Glass" WWDC25, "Essential Design Principles" WWDC17, inclusive-design, charts, spatial) — watch before building a new pattern; each HIG page's Resources→Videos links the canonical talks.
- **Apple Design Awards** (…/design/awards/): yearly exemplars across delight, inclusivity, innovation, interaction, visuals — benchmark Tempo against winners, not against average apps.
- **Get started / Design Pathway** (…/design/get-started/): ordered videos+docs+resources ramp for new designers — onboarding reference for juniors/AI agents alike.
- **Developer stories**: inclusion-first case studies (e.g. Art of Fauna) — process models for accessibility reviews.

## 10. AI pre-ship checklist (run before finishing ANY UI task)

- [ ] §1: purpose clear? focused? forgiving (undo)? transparent permissions? consistent? hierarchical? crafted? delight ≠ obstruction?
- [ ] §2: phone-first right; tablet/foldable re-layout; glanceables/widgets covered.
- [ ] §3: contrast ≥4.5:1 light+dark+contrast-boost; font scales 85–200%; TalkBack labels; no color-alone meaning; no app theme toggle; icons optical + RTL-safe; glass only on chrome (regular/clear correct, 35% dimmer verified, reduce-transparency fallback solid); motion interruptible + reduced-motion fades; permissions in-context with why; RTL mirrored (no flipped digits/logos); type hierarchy survives scaling; copy = verbs, errors blame-free with fixes.
- [ ] §4: launch ≤2s w/ proper launch screen; skeletons > spinners; autosave on; modal used only for critical/narrow/focus tasks; notifications tiered correctly, marketing opted-in only; settings = rare-only + system-respecting; rating prompt only after real use; undo/redo present.
- [ ] §5: standard components, 48dp targets, press states, verb labels, destructive ≠ primary; alerts critical+actionable only; sheets Cancel+Done; badges critical-only; widgets glanceable + deep-linked; Live Activities ended on time; no system-visual imitation.
- [ ] §6/§7: no gesture-only actions; keyboard paths; AI outputs reversible; system capabilities used, not cloned; health/auth/pay flows use platform rails.
- [ ] §8: Tempo glass recipes + tint alpha + resting-state contrast verified on bright/dark/busy art.

*Coverage: Getting started · Design principles · 7 platform pages · 18 Foundations · 25 Patterns · 64 Components (8 groups) · 13 Inputs · 29 Technologies — 158 HIG pages total, none skipped.*
