# Project Tab Colors

An Eclipse plug-in that colors each editor tab by the project its file belongs to. It is built for workspaces with many services that share file names (`Application.java`, `application.yml`, `pom.xml`, `Dockerfile`), where the tab title alone doesn't tell you which service you're in.

## Features

- **Colored editor tabs.** Each tab's text and a thin line along its top edge use its project's color, in every window, split stack, and detached window. The focused editor shows the color at full strength; other tabs show a softer variant. Files outside any project keep the theme's default color.
- **Overflow list.** When there are too many tabs, the chevron dropdown lists the hidden ones in their project colors too.
- **Project Explorer legend.** Project names in Project Explorer and Package Explorer use the same color.
- **Tab Color menu.** Right-click a resource, then choose **Project Tab Colors > Tab Color...** to pick a color, or **Reset Tab Color** to go back to the automatic one.
- **Close by project.** Two commands: **Close Editors of This Project** (Ctrl+Alt+W) and **Close Editors of Other Projects** (Ctrl+Shift+Alt+W). They're in the same submenu and in the **Navigate** menu.
- **Status bar.** The status bar shows the active editor's project with a color square. Click it to reveal the project in Project Explorer, or click the palette button next to it to change the project's color.

## Requirements

- Eclipse 2023-12 (4.30) or later
- Java 17 or later

The plug-in is built against the 2023-12 release. It has been tested on Eclipse 2025-06 (4.36) on Windows, with light and dark themes.

## Install

1. Build it (see [Build](#build)), or use a built update-site zip.
2. In Eclipse, choose **Help > Install New Software... > Add... > Archive...**, select `it.sco.tabcolors.site/target/it.sco.tabcolors.site-1.0.0-SNAPSHOT.zip`, and install **Project Tab Colors**.
3. Restart Eclipse.
4. In **Preferences > General > Startup and Shutdown**, make sure **Project Tab Colors** is ticked.

Alternatively, copy the plug-in jar from `it.sco.tabcolors.site/target/repository/plugins/` into `<eclipse>/dropins/` and start Eclipse once with `-clean`. Each build gets a new timestamp in the jar's name, so delete the old jar before copying a new one.

## How colors work

**Automatic colors.** A project without a chosen color gets one computed from its name. The same name gives the same color on every machine.

**Chosen colors** are stored in the project itself, so you can commit them with the service:

```
<project>/.settings/it.sco.tabcolors.prefs
```
```
eclipse.preferences.version=1
color=#E06C75
```

Eclipse writes the value as `color=\#E06C75`; both forms are read correctly. Edits to this file are picked up immediately, whether they come from a `git pull` or from editing it by hand. Resetting the color removes the key, and deletes the file if nothing else is stored in it.

**Themes.** The stored color is never changed. When drawing, the plug-in keeps the color's hue but adjusts its lightness so it stays readable against the actual tab background: 4.5:1 contrast for text and 3:1 for the line, the WCAG guidelines. A color that works on a light theme is lightened on a dark theme, and the reverse.

**Explorer colors** can be switched off in **Preferences > General > Appearance > Label Decorations > Project Tab Colors**. Tabs keep their colors.

## Build

You need JDK 17 or later; the build was tested with JDK 21. Maven is fetched by the wrapper.

```
./mvnw clean verify          # Windows: mvnw.cmd clean verify
```

The first build downloads the Eclipse 2023-12 target platform, which takes a few minutes.

Outputs:

| Path | What |
|---|---|
| `it.sco.tabcolors.site/target/repository/` | p2 update site |
| `it.sco.tabcolors.site/target/it.sco.tabcolors.site-1.0.0-SNAPSHOT.zip` | Zipped update site for **Install New Software > Archive** |
| `it.sco.tabcolors.site/target/repository/plugins/it.sco.tabcolors_*.jar` | Plug-in jar for `dropins/` |

Modules:

| Module | Packaging | Contents |
|---|---|---|
| `it.sco.tabcolors` | eclipse-plugin | The plug-in |
| `it.sco.tabcolors.tests` | eclipse-test-plugin | JUnit 5 tests: color parsing and hashing, contrast, cache invalidation against a real headless workspace |
| `it.sco.tabcolors.feature` | eclipse-feature | Feature for installation |
| `it.sco.tabcolors.site` | eclipse-repository | Update site |

## Continuous integration

GitHub Actions (`.github/workflows/build.yml`) runs `./mvnw clean verify` on every push and pull request, on Linux with JDK 21.

- **Download a build:** open the workflow run in the **Actions** tab. The update-site zip and the plug-in jar are under the run's artifacts as `project-tab-colors-update-site`.
- **When tests fail:** the surefire reports are uploaded as `test-reports`.
- **Release:** pushing a tag that starts with `v` (for example `git tag v1.0.0 && git push origin v1.0.0`) creates a GitHub Release with the zip and jar attached.

## Diagnostics

Start Eclipse with `-debug` and open **Window > Show View > Error Log**. The plug-in then writes INFO entries from `it.sco.tabcolors`:

- **Startup cost.** Time spent on the UI thread, broken down by phase, plus the background warm-up time. With 16 tabs open the UI-thread time is about 4 ms.
- **Tab lookup.** If an editor tab can't be found in the workbench model, one line explaining why.
- **Status bar size.** The geometry of the status bar indicator.

Without `-debug` the plug-in logs only errors.

## Implementation notes

- **Internal API.** The code uses no `org.eclipse.ui.internal` API (`grep -r "ui.internal" it.sco.tabcolors/src` finds nothing). All SWT and E4-model access to tabs is in `TabPainter`; the status bar code is in `ActiveProjectStatusControl`.
- **Non-API details it relies on.** These are verified against Eclipse 4.36 and may need checking on new releases:
  - the `CTabItem` data key `"modelElement"`, which links a tab to its `MPart` (the value of `AbstractPartRenderer.OWNING_ME`);
  - the part tooltip override key `e4_override_title_tool_tip_key`;
  - `IThemeEngine.Events.THEME_CHANGED`, which produces "discouraged access" compiler warnings.
- **Startup.** Reading project colors, precomputing contrast-adjusted colors, and loading classes run on the background thread used for early startup. Each tab's project name is remembered in its `MPart` persisted state (key `it.sco.tabcolors.project`, saved in `workbench.xmi`), so tabs that haven't been opened yet are colored without loading their editors. The last theme's tab backgrounds are kept in the instance preferences for the same purpose.
- **Never call `CTabItem.getBounds()` right after changing a tab's colors.** It forces a synchronous layout of the whole tab folder. Doing that per tab was once the main startup cost.
- **Status bar palette button.** On Windows, a status-bar toolbar that holds only a custom control is about 7 px tall. The palette button gives the row its normal height.
