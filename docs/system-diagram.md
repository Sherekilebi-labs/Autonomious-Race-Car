# MaxBot system map

Open [system-diagram.html](system-diagram.html) directly in a browser with JavaScript enabled, including via `file://`. It is a static source snapshot with no robot connection. The HTML embeds its data, scripts, styles and local Gilroy fonts; no server, installation or network dependency is required. Only the optional About C4 link opens an external website.

**Surface brief — Read / Operate.** Help a repository reader understand the whole project, then locate a source unit through recursive drill-down. Preserve the incumbent tokens from [DESIGN.md](../DESIGN.md) and [.impeccable/design.json](../.impeccable/design.json): Gilroy, blue `#0064b2`, white/light neutral surfaces, readable text and visible focus. Overview first, clear ancestry, search and source references support comprehension and navigation within the existing visual system.

## Explore

The overview opens with seven systems. Drill down through L1 system context → L2 subsystems → L3 components → L4 code and contracts. The snapshot contains 227 nodes including the overview: 7 systems, 20 subsystems, 58 components and 141 implementation units.

- Select a block to open its children or inspect an implementation unit. Use the outline, breadcrumbs, **Up** or **Overview** to navigate back.
- Search by name, source filename, symbol or technology. Use **Browse** to reveal navigation on narrow screens; these default to **List** view.
- Switch between **Map** and **List**. Drag empty map space to pan; use zoom buttons, Ctrl + wheel or **Fit view**. Select a connection to read its contract.
- Use Tab to reach controls, Enter to activate, Escape to go up and `/` to focus search outside a text field. **Copy link** preserves the current block in the URL hash.

Source links are relative to the HTML's repository location. Displayed line numbers are references; a browser file viewer may not jump to them. A copied single HTML remains interactive, but source references require the corresponding repository layout. Shared `file://` links also require the recipient's local path to match.

## Maintain

Edit authored architecture data, descriptions and source anchors in [build_system_diagram.py](../tools/maxbot/build_system_diagram.py). Edit markup, styles and interaction code in [system-diagram.template.html](system-diagram.template.html). The generated HTML is the distributable artifact; rebuild it after changing either input or the mapped source. Calibration details and embedded Gilroy fonts are read from repository files during generation.

Run from the repository root:

```sh
python3 tools/maxbot/build_system_diagram.py
node tools/maxbot/tests/test_system_diagram.cjs
```

The builder validates source paths/symbol anchors, unique IDs, hierarchy depth and local connection endpoints. Update source anchors when code moves. This is an authored map, so successful validation does not prove architectural completeness or keep descriptions automatically current.

## Verification and limits

Read-only builder validation passed for all 227 nodes. The [functional test](../tools/maxbot/tests/test_system_diagram.cjs) executes the shipped JavaScript through a minimal DOM/event adapter. Both desktop and mobile branches passed 227 routes and 210 source links, plus navigation, drill-down/up, search, invalid hashes, focus logic and map controls. These checks exercise logic, not browser layout, pixels or assistive technology.

Representative native Chrome inspection covered the desktop overview, a direct L4 link, selection of a code unit and its source anchor, and the responsive list view at 390 × 844. Screenshots were observed during inspection but were not saved. This is not exhaustive browser-layout or assistive-technology verification. The independent source/functional finish review recommended shipping; its bounded follow-up scored both findings resolved (outline disclosure focus and depth-appropriate connection copy). The diagram provides no physical robot, live connection, stopping, detector or performance verification.
