package it.sco.tabcolors;

import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.core.services.events.IEventBroker;
import org.eclipse.e4.ui.css.swt.theme.IThemeEngine;
import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
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
 * Each colored tab gets its project color as text color (adjusted per theme for
 * contrast, see {@link ColorContrast}) and a thin colored line along its outer
 * edge. The line is drawn by a paint listener on the tab folder, after the
 * theme's own renderer, and is omitted on the editor that has focus.
 * <p>
 * Unrestored editors (lazily restored at startup) have no reachable {@link MPart}
 * through public API, so they are skipped and get colored on {@code partVisible} /
 * {@code partBroughtToTop}, which restores them.
 */
final class TabPainter {

    /**
     * Value of {@code AbstractPartRenderer.OWNING_ME}, which lives in a non-API package
     * of bundle {@code org.eclipse.e4.ui.workbench.swt} (verified with javap on the
     * 2023-12 bundle). Hard-coded so no non-API package is referenced from source;
     * javac would inline the constant anyway.
     */
    private static final String OWNING_ME = "modelElement";
    /** Marks items we colored, so we only reset colors we set ourselves. */
    private static final String COLORED = "it.sco.tabcolors.colored";
    /** RGB of the item's line, or absent for no line. */
    private static final String LINE_COLOR = "it.sco.tabcolors.lineColor";
    /** Marks folders that already have our line paint listener. */
    private static final String LINE_HOOKED = "it.sco.tabcolors.lineHooked";
    static final int LINE_WIDTH = 2;

    private static final ILog LOG = Platform.getLog(TabPainter.class);
    private static final AtomicBoolean repaintScheduled = new AtomicBoolean();
    private static final AtomicBoolean debugLogged = new AtomicBoolean();

    /** Tab of the editor that has focus: no line. UI thread only. */
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

    /** The editor got focus: its tab loses the line. */
    static void activatedLater(IEditorReference ref) {
        asyncExec(() -> {
            setActiveItem(itemOf(ref));
            paint(ref);
        });
    }

    /** The editor lost focus (to another editor or a view): its tab gets the line back. */
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
            for (IWorkbenchPage page : window.getPages()) {
                repaintPage(page);
            }
        }
    }

    static void repaintPage(IWorkbenchPage page) {
        for (IEditorReference ref : page.getEditorReferences()) {
            paint(ref);
        }
    }

    /** Sets the tab's colors from its project; restores the defaults if it has none. */
    static void paint(IEditorReference ref) {
        try {
            CTabItem item = itemOf(ref);
            if (item == null || item.isDisposed()) {
                return;
            }
            IProject project = EditorProjects.projectOf(ref);
            RGB rgb = project == null ? null : ProjectColorStore.get().colorOf(project);
            apply(item, rgb);
        } catch (RuntimeException e) {
            LOG.error("Cannot color editor tab " + ref.getId(), e);
        }
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
            if (item.getData(COLORED) != null) {
                item.setData(COLORED, null);
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
        RGB text = ColorContrast.readable(rgb, background, ColorContrast.TEXT);
        RGB selectedText = ColorContrast.readable(rgb, selectionBackground, ColorContrast.TEXT);
        RGB line = ColorContrast.readable(rgb, background, ColorContrast.LINE);
        hookLinePainter(folder);

        // CTabItem compares colors by identity, so skip redundant sets to avoid redraws.
        if (item.getData(COLORED) != null && text.equals(rgbOf(item.getForeground()))
                && selectedText.equals(rgbOf(item.getSelectionForeground()))
                && line.equals(item.getData(LINE_COLOR))) {
            return;
        }
        item.setData(COLORED, Boolean.TRUE);
        item.setData(LINE_COLOR, line);
        // device-less Colors: no OS resource, no dispose needed
        item.setForeground(new Color(text));
        item.setSelectionForeground(new Color(selectedText));
        redraw(item);
    }

    private static void hookLinePainter(CTabFolder folder) {
        if (folder.getData(LINE_HOOKED) == null) {
            folder.setData(LINE_HOOKED, Boolean.TRUE);
            // Added after CTabFolder's own paint listener, so it draws on top of the theme.
            folder.addListener(SWT.Paint, TabPainter::paintLines);
        }
    }

    private static void paintLines(Event event) {
        try {
            CTabFolder folder = (CTabFolder) event.widget;
            GC gc = event.gc;
            Color previous = gc.getBackground();
            boolean bottom = folder.getTabPosition() == SWT.BOTTOM;
            for (CTabItem item : folder.getItems()) {
                if (item == activeItem || !item.isShowing() || !(item.getData(LINE_COLOR) instanceof RGB rgb)) {
                    continue;
                }
                Rectangle b = item.getBounds();
                gc.setBackground(new Color(rgb));
                gc.fillRectangle(b.x, bottom ? b.y + b.height - LINE_WIDTH : b.y, b.width, LINE_WIDTH);
            }
            gc.setBackground(previous);
        } catch (RuntimeException e) {
            LOG.error("Cannot draw project tab lines", e);
        }
    }

    private static void setActiveItem(CTabItem item) {
        CTabItem previous = activeItem;
        if (previous == item) {
            return;
        }
        activeItem = item;
        redraw(previous);
        redraw(item);
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

    private static MPart modelPart(IEditorReference ref) {
        IEditorPart editor = ref.getEditor(false);
        if (editor == null) {
            return null; // unrestored: colored once it becomes visible
        }
        MPart part = editor.getSite().getService(MPart.class);
        if (part == null) {
            debugOnce("No MPart from site of restored editor " + ref.getId());
        }
        return part;
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
