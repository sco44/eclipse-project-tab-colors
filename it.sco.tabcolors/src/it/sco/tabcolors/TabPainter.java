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
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
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

    private static final ILog LOG = Platform.getLog(TabPainter.class);
    private static final AtomicBoolean repaintScheduled = new AtomicBoolean();

    private TabPainter() {
    }

    /** Colors one editor tab after the current UI event (e.g. CSS restyling) has run. */
    static void paintLater(IEditorReference ref) {
        asyncExec(() -> paint(ref));
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

    /** Sets the tab's text color to its project's color; restores the default if it has none. */
    static void paint(IEditorReference ref) {
        try {
            MPart part = modelPart(ref);
            if (part == null) {
                return;
            }
            CTabItem item = tabItem(part);
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

    /** Repaints all tabs when the CSS theme changes, since the engine may reset item colors. */
    static void subscribeThemeChanges(IWorkbench workbench) {
        IEventBroker broker = workbench.getService(IEventBroker.class);
        if (broker == null) {
            LOG.warn("No IEventBroker; tab colors are not reapplied on theme change");
            return;
        }
        broker.subscribe(IThemeEngine.Events.THEME_CHANGED, event -> repaintAllLater());
    }

    private static void apply(CTabItem item, RGB rgb) {
        if (rgb == null) {
            if (item.getData(COLORED) != null) {
                item.setData(COLORED, null);
                item.setForeground(null); // null restores the theme default
                item.setSelectionForeground(null);
            }
            return;
        }
        // CTabItem compares colors by identity, so skip redundant sets to avoid redraws.
        if (item.getData(COLORED) != null && rgb.equals(rgbOf(item.getForeground()))
                && rgb.equals(rgbOf(item.getSelectionForeground()))) {
            return;
        }
        Color color = new Color(rgb); // device-less Color: no OS resource, no dispose needed
        item.setData(COLORED, Boolean.TRUE);
        item.setForeground(color);
        item.setSelectionForeground(color);
    }

    private static RGB rgbOf(Color color) {
        return color == null || color.isDisposed() ? null : color.getRGB();
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

    private static final AtomicBoolean debugLogged = new AtomicBoolean();

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
