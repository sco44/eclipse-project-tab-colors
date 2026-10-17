package it.sco.tabcolors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.core.services.events.IEventBroker;
import org.eclipse.e4.ui.css.swt.theme.IThemeEngine;
import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspective;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * All CTabItem / E4 model access lives here, so Eclipse upgrades that break it
 * break only this class. Every method must be called on the UI thread unless
 * its name ends in {@code Later}.
 * <p>
 * Each colored tab gets its project color as text color and as a thin line along
 * its outer edge, drawn by a paint listener on the tab folder after the theme's
 * renderer (on the selected tab it covers the theme's highlight). The focused
 * editor shows the color at full strength; all other tabs a softer variant. Both
 * are adjusted for contrast against the actual theme, see {@link ColorContrast}.
 * The chevron's "more tabs" list is colored too.
 * <p>
 * Restored editors find their {@link MPart} through their site. Unrestored editors
 * (lazily restored at startup) have no public link to it, so their part is found by
 * matching label and tooltip, which the workbench derives from that same part.
 */
final class TabPainter {

    /**
     * Value of {@code AbstractPartRenderer.OWNING_ME}, which lives in a non-API package
     * of bundle {@code org.eclipse.e4.ui.workbench.swt} (verified with javap on the
     * 2023-12 bundle). Hard-coded so no non-API package is referenced from source;
     * javac would inline the constant anyway.
     */
    private static final String OWNING_ME = "modelElement";
    /** Project RGB of a colored item (unadjusted), absent if we did not color it. */
    private static final String PROJECT_RGB = "it.sco.tabcolors.projectRgb";
    /** RGB of the item's line. */
    private static final String LINE_COLOR = "it.sco.tabcolors.lineColor";
    /** Marks folders and part-list tables we already hooked. */
    private static final String HOOKED = "it.sco.tabcolors.hooked";

    static final int LINE_WIDTH = 2;
    /** The theme's selected-tab highlight is 2 px, drawn up to 1 px below the tab top. */
    private static final int SELECTED_LINE_WIDTH = LINE_WIDTH + 1;
    /** How far unfocused tabs blend toward the background, and their minimum contrast. */
    private static final double SOFTEN = 0.3;
    private static final double SOFT_TEXT_FLOOR = 3.0;
    private static final double SOFT_LINE_FLOOR = 1.5;

    private static final ILog LOG = Platform.getLog(TabPainter.class);
    private static final AtomicBoolean repaintScheduled = new AtomicBoolean();
    private static final AtomicBoolean debugLogged = new AtomicBoolean();

    /** Tab of the editor that has focus: full-strength color. UI thread only. */
    private static CTabItem activeItem;
    /** Last seen editor tab background, used by the explorer decorator off the UI thread. */
    private static volatile RGB themeBackground;

    private TabPainter() {
    }

    // ---- scheduling ------------------------------------------------------------

    /** Colors one editor tab after the current UI event (e.g. CSS restyling) has run. */
    static void paintLater(IEditorReference ref) {
        asyncExec(() -> paint(ref));
    }

    /** The editor got focus: its tab gets the full-strength color. */
    static void activatedLater(IEditorReference ref) {
        asyncExec(() -> {
            paint(ref);
            setActiveItem(itemOf(ref));
        });
    }

    /** The editor lost focus (to another editor or a view): its tab turns soft. */
    static void deactivatedLater(IEditorReference ref) {
        asyncExec(() -> {
            CTabItem item = itemOf(ref);
            if (item != null && item == activeItem) {
                setActiveItem(null);
            }
            paint(ref);
        });
    }

    /** Repaints every editor tab once, however many times it is requested before it runs. */
    static void repaintAllLater() {
        if (repaintScheduled.compareAndSet(false, true)) {
            asyncExec(() -> {
                repaintScheduled.set(false);
                repaintAll();
            });
        }
    }

    // ---- painting -----------------------------------------------------------------

    static void repaintAll() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            repaintWindow(window);
        }
    }

    static void repaintWindow(IWorkbenchWindow window) {
        try {
            editorParts(window).forEach((part, ref) -> {
                try {
                    paint(part, ref);
                } catch (RuntimeException e) {
                    LOG.error("Cannot color editor tab " + ref.getId(), e);
                }
            });
        } catch (RuntimeException e) {
            LOG.error("Cannot color editor tabs", e);
        }
    }

    /** Sets the tab's colors from its project; restores the defaults if it has none. */
    static void paint(IEditorReference ref) {
        try {
            MPart part = modelPart(ref);
            if (part != null) {
                paint(part, ref);
            }
        } catch (RuntimeException e) {
            LOG.error("Cannot color editor tab " + ref.getId(), e);
        }
    }

    private static void paint(MPart part, IEditorReference ref) {
        CTabItem item = tabItem(part);
        if (item == null || item.isDisposed()) {
            return;
        }
        apply(item, colorOf(ref));
    }

    private static RGB colorOf(IEditorReference ref) {
        IProject project = EditorProjects.projectOf(ref);
        return project == null ? null : ProjectColorStore.get().colorOf(project);
    }

    /** Marks the focused editor at startup, before any activation event. */
    static void initActive(IWorkbenchPage page) {
        if (page != null && page.getActivePartReference() instanceof IEditorReference ref) {
            setActiveItem(itemOf(ref));
        }
    }

    /** Seeds {@link #themeBackground} before any tab has been painted. */
    static void initThemeBackground(Display display) {
        if (themeBackground == null) {
            themeBackground = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
        }
    }

    /** Background the explorer decorator adjusts against; may be {@code null} early. Any thread. */
    static RGB themeBackground() {
        return themeBackground;
    }

    /** Repaints tabs and explorer labels when the CSS theme changes. */
    static void subscribeThemeChanges(IWorkbench workbench) {
        IEventBroker broker = workbench.getService(IEventBroker.class);
        if (broker == null) {
            LOG.warn("No IEventBroker; tab colors are not reapplied on theme change");
            return;
        }
        broker.subscribe(IThemeEngine.Events.THEME_CHANGED, event -> asyncExec(() -> {
            repaintAll(); // also refreshes themeBackground
            workbench.getDecoratorManager().update(ProjectColorDecorator.ID);
        }));
    }

    private static void apply(CTabItem item, RGB rgb) {
        CTabFolder folder = item.getParent();
        if (rgb == null) {
            if (item.getData(PROJECT_RGB) != null) {
                item.setData(PROJECT_RGB, null);
                item.setData(LINE_COLOR, null);
                item.setForeground(null); // null restores the theme default
                item.setSelectionForeground(null);
                redraw(item);
            }
            return;
        }
        RGB background = rgbOf(folder.getBackground());
        RGB selectionBackground = rgbOf(folder.getSelectionBackground());
        if (selectionBackground == null) {
            selectionBackground = background;
        }
        if (background != null) {
            themeBackground = background;
        }
        boolean focused = item == activeItem;
        RGB text = shade(rgb, background, focused, ColorContrast.TEXT, SOFT_TEXT_FLOOR);
        RGB selectedText = shade(rgb, selectionBackground, focused, ColorContrast.TEXT, SOFT_TEXT_FLOOR);
        RGB line = shade(rgb, item == folder.getSelection() ? selectionBackground : background, focused,
                ColorContrast.LINE, SOFT_LINE_FLOOR);
        hookFolder(folder);

        // CTabItem compares colors by identity, so skip redundant sets to avoid redraws.
        if (rgb.equals(item.getData(PROJECT_RGB)) && text.equals(rgbOf(item.getForeground()))
                && selectedText.equals(rgbOf(item.getSelectionForeground()))
                && line.equals(item.getData(LINE_COLOR))) {
            return;
        }
        item.setData(PROJECT_RGB, rgb);
        item.setData(LINE_COLOR, line);
        // device-less Colors: no OS resource, no dispose needed
        item.setForeground(new Color(text));
        item.setSelectionForeground(new Color(selectedText));
        redraw(item);
    }

    private static RGB shade(RGB rgb, RGB background, boolean focused, double min, double softFloor) {
        return focused ? ColorContrast.readable(rgb, background, min)
                : ColorContrast.soften(rgb, background, min, SOFTEN, softFloor);
    }

    private static void setActiveItem(CTabItem item) {
        CTabItem previous = activeItem;
        if (previous == item) {
            return;
        }
        activeItem = item;
        reapply(previous);
        reapply(item);
    }

    /** Recomputes an item's colors (e.g. after a focus change) from its stored project color. */
    private static void reapply(CTabItem item) {
        if (item != null && !item.isDisposed() && item.getData(PROJECT_RGB) instanceof RGB rgb) {
            apply(item, rgb);
        }
    }

    // ---- folder hooks: line painter and "more tabs" list ----------------------------

    private static void hookFolder(CTabFolder folder) {
        if (folder.getData(HOOKED) != null) {
            return;
        }
        folder.setData(HOOKED, Boolean.TRUE);
        // Added after CTabFolder's own paint listener, so it draws on top of the theme.
        folder.addListener(SWT.Paint, TabPainter::paintLines);
        // Added after the StackRenderer's listener, which opens the list synchronously.
        folder.addCTabFolder2Listener(new CTabFolder2Adapter() {
            @Override
            public void showList(CTabFolderEvent event) {
                asyncExec(() -> colorPartLists(folder));
            }
        });
        // Selecting another tab changes which background its line is adjusted against.
        folder.addListener(SWT.Selection, e -> asyncExec(() -> {
            for (CTabItem item : folder.getItems()) {
                reapply(item);
            }
        }));
    }

    private static void paintLines(Event event) {
        try {
            CTabFolder folder = (CTabFolder) event.widget;
            GC gc = event.gc;
            Color previous = gc.getBackground();
            boolean bottom = folder.getTabPosition() == SWT.BOTTOM;
            CTabItem selected = folder.getSelection();
            for (CTabItem item : folder.getItems()) {
                if (!item.isShowing() || !(item.getData(LINE_COLOR) instanceof RGB rgb)) {
                    continue;
                }
                Rectangle b = item.getBounds();
                int height = item == selected ? SELECTED_LINE_WIDTH : LINE_WIDTH;
                gc.setBackground(new Color(rgb));
                gc.fillRectangle(b.x, bottom ? b.y + b.height - height : b.y, b.width, height);
            }
            gc.setBackground(previous);
        } catch (RuntimeException e) {
            LOG.error("Cannot draw project tab lines", e);
        }
    }

    /** Colors the rows of the chevron's part list popup(s) opened from this folder. */
    private static void colorPartLists(CTabFolder folder) {
        if (folder.isDisposed()) {
            return;
        }
        for (Shell shell : folder.getShell().getShells()) {
            if (shell.isDisposed() || !shell.isVisible()) {
                continue;
            }
            Table table = find(shell, Table.class);
            if (table == null || !listsEditors(table)) {
                continue;
            }
            colorPartList(table);
            if (table.getData(HOOKED) == null) {
                table.setData(HOOKED, Boolean.TRUE);
                // Typing in the filter refreshes the viewer, which resets row colors.
                Text filter = find(shell, Text.class);
                if (filter != null) {
                    filter.addListener(SWT.Modify, e -> asyncExec(() -> colorPartList(table)));
                }
            }
        }
    }

    private static boolean listsEditors(Table table) {
        for (TableItem row : table.getItems()) {
            if (row.getData() instanceof MPart) {
                return true;
            }
        }
        return false;
    }

    private static void colorPartList(Table table) {
        if (table.isDisposed()) {
            return;
        }
        Map<MPart, IEditorReference> refs = new LinkedHashMap<>();
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            refs.putAll(editorParts(window));
        }
        RGB background = rgbOf(table.getBackground());
        for (TableItem row : table.getItems()) {
            IEditorReference ref = row.getData() instanceof MPart part ? refs.get(part) : null;
            if (ref != null) {
                RGB rgb = colorOf(ref);
                if (rgb != null) {
                    row.setForeground(new Color(ColorContrast.readable(rgb, background, ColorContrast.TEXT)));
                }
            }
        }
    }

    private static <T extends Control> T find(Control control, Class<T> type) {
        if (type.isInstance(control)) {
            return type.cast(control);
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void redraw(CTabItem item) {
        if (item != null && !item.isDisposed() && item.isShowing()) {
            Rectangle b = item.getBounds();
            item.getParent().redraw(b.x, b.y, b.width, b.height, false);
        }
    }

    private static RGB rgbOf(Color color) {
        return color == null || color.isDisposed() ? null : color.getRGB();
    }

    // ---- E4 model lookup --------------------------------------------------------

    private static CTabItem itemOf(IEditorReference ref) {
        MPart part = modelPart(ref);
        return part == null ? null : tabItem(part);
    }

    /** Editor parts of all pages of a window, restored or not, mapped to their references. */
    private static Map<MPart, IEditorReference> editorParts(IWorkbenchWindow window) {
        Map<MPart, IEditorReference> result = new LinkedHashMap<>();
        List<MPart> allParts = null;
        for (IWorkbenchPage page : window.getPages()) {
            for (IEditorReference ref : page.getEditorReferences()) {
                MPart part = restoredPart(ref);
                if (part == null) {
                    if (allParts == null) {
                        allParts = allParts(window);
                    }
                    part = matchUnrestored(ref, allParts, result.keySet());
                }
                if (part != null) {
                    result.put(part, ref);
                }
            }
        }
        return result;
    }

    private static MPart modelPart(IEditorReference ref) {
        MPart part = restoredPart(ref);
        if (part != null || ref.getEditor(false) != null) {
            return part;
        }
        return matchUnrestored(ref, allParts(ref.getPage().getWorkbenchWindow()), Set.of());
    }

    private static MPart restoredPart(IEditorReference ref) {
        IEditorPart editor = ref.getEditor(false);
        if (editor == null) {
            return null;
        }
        MPart part = editor.getSite().getService(MPart.class);
        if (part == null) {
            debugOnce("No MPart from site of restored editor " + ref.getId());
        }
        return part;
    }

    /**
     * An unrestored editor's reference takes its name and tooltip from its MPart, so a
     * part with the same label and tooltip is that editor's part. Two parts can only
     * tie if they show the same file, which means the same project and color.
     */
    private static MPart matchUnrestored(IEditorReference ref, List<MPart> parts, Collection<MPart> taken) {
        String label = ref.getPartName();
        String tooltip = Objects.requireNonNullElse(ref.getTitleToolTip(), "");
        if (label == null || label.isEmpty()) {
            return null;
        }
        for (MPart part : parts) {
            if (!taken.contains(part) && label.equals(part.getLocalizedLabel())
                    && tooltip.equals(Objects.requireNonNullElse(part.getLocalizedTooltip(), ""))) {
                return part;
            }
        }
        debugOnce("No MPart matches unrestored editor '" + label + "' (" + tooltip + ")");
        return null;
    }

    /** All parts in a window's model, including detached windows and the shared editor area. */
    private static List<MPart> allParts(IWorkbenchWindow window) {
        List<MPart> parts = new ArrayList<>();
        MWindow model = window.getService(MWindow.class);
        if (model != null) {
            collectParts(model, parts, new HashSet<>());
        } else {
            debugOnce("No MWindow for workbench window");
        }
        return parts;
    }

    private static void collectParts(MUIElement element, List<MPart> parts, Set<MUIElement> seen) {
        if (element == null || !seen.add(element)) {
            return;
        }
        if (element instanceof MPart part) {
            parts.add(part);
        }
        if (element instanceof MElementContainer<?> container) {
            for (Object child : container.getChildren()) {
                collectParts((MUIElement) child, parts, seen);
            }
        }
        if (element instanceof MPlaceholder placeholder) {
            collectParts(placeholder.getRef(), parts, seen);
        }
        if (element instanceof MWindow window) {
            window.getWindows().forEach(w -> collectParts(w, parts, seen));
            window.getSharedElements().forEach(e -> collectParts(e, parts, seen));
        }
        if (element instanceof MPerspective perspective) {
            perspective.getWindows().forEach(w -> collectParts(w, parts, seen));
        }
    }

    private static CTabItem tabItem(MPart part) {
        MPlaceholder placeholder = part.getCurSharedRef();
        MElementContainer<MUIElement> parent = part.getParent();
        if (parent == null && placeholder != null) {
            parent = placeholder.getParent();
        }
        if (parent == null || !(parent.getWidget() instanceof CTabFolder folder) || folder.isDisposed()) {
            return null;
        }
        for (CTabItem item : folder.getItems()) {
            Object owner = item.getData(OWNING_ME);
            if (owner == part || (placeholder != null && owner == placeholder)) {
                return item;
            }
        }
        if (Platform.inDebugMode()) {
            StringBuilder owners = new StringBuilder();
            for (CTabItem item : folder.getItems()) {
                Object owner = item.getData(OWNING_ME);
                owners.append(owner == null ? "null" : owner.getClass().getName()).append(' ');
            }
            debugOnce("No CTabItem owned by " + part.getClass().getName() + " (" + part.getLabel()
                    + "); item owners: " + owners);
        }
        return null;
    }

    /** Diagnostics for the unverified E4 assumptions; only with {@code -debug}, logged once. */
    private static void debugOnce(String message) {
        if (Platform.inDebugMode() && debugLogged.compareAndSet(false, true)) {
            LOG.info("Project Tab Colors diagnostics: " + message);
        }
    }

    private static void asyncExec(Runnable runnable) {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            try {
                runnable.run();
            } catch (RuntimeException e) {
                LOG.error("Tab color update failed", e);
            }
        });
    }
}
