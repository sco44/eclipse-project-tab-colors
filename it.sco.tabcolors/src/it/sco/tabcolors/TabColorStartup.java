package it.sco.tabcolors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Hooks every workbench window and page so editor tabs get their project color.
 * Detached editor windows are E4 child windows of a workbench window, so the
 * owning window's part service already covers them.
 */
public class TabColorStartup implements IStartup {

    private static final ILog LOG = Platform.getLog(TabColorStartup.class);

    private final EditorTabListener partListener = new EditorTabListener();

    private final IPageListener pageListener = new IPageListener() {
        @Override
        public void pageOpened(IWorkbenchPage page) {
            safely(() -> TabPainter.repaintWindow(page.getWorkbenchWindow()));
        }

        @Override
        public void pageActivated(IWorkbenchPage page) {
        }

        @Override
        public void pageClosed(IWorkbenchPage page) {
        }
    };

    private final IWindowListener windowListener = new IWindowListener() {
        @Override
        public void windowOpened(IWorkbenchWindow window) {
            safely(() -> hookWindow(window));
        }

        @Override
        public void windowClosed(IWorkbenchWindow window) {
            safely(() -> {
                window.getPartService().removePartListener(partListener);
                window.removePageListener(pageListener);
            });
        }

        @Override
        public void windowActivated(IWorkbenchWindow window) {
        }

        @Override
        public void windowDeactivated(IWorkbenchWindow window) {
        }
    };

    /**
     * Runs on a background thread while the UI thread is still busy starting the
     * workbench (typically for a second or more). Everything that does not need the
     * UI thread happens here: reading every project's color from disk and loading our
     * classes. The UI thread then only hooks listeners and paints from warm caches.
     */
    @Override
    public void earlyStartup() {
        long start = System.nanoTime();
        int projects = 0;
        int variants = 0;
        try {
            preloadClasses();
            ProjectColorStore store = ProjectColorStore.get();
            projects = store.prefetch();
            variants = TabPainter.warmUp(store);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Project Tab Colors: background warm-up failed", e);
        }
        long warmed = System.nanoTime();
        String warmUp = String.format("background warm-up %.1f ms (%d projects, %d colors)",
                (warmed - start) / 1e6, projects, variants);
        IWorkbench workbench = PlatformUI.getWorkbench();
        Display display = workbench.getDisplay();
        display.asyncExec(() -> {
            long uiStart = System.nanoTime();
            StringBuilder phases = new StringBuilder();
            int[] tabs = { 0 };
            safely(() -> tabs[0] = hookWorkbench(workbench, phases));
            if (Platform.inDebugMode()) {
                // Only the UI hookup blocks the workbench; the warm-up ran in the background
                // and the wait in between is the UI thread finishing its own startup.
                LOG.info(String.format("Project Tab Colors startup: UI thread %.1f ms for %d editor tabs [%s; %s];"
                        + " %s; waited %.0f ms for the UI thread", (System.nanoTime() - uiStart) / 1e6, tabs[0],
                        phases, TabPainter.profile(), warmUp, (uiStart - warmed) / 1e6));
            }
        });
    }

    /** Loads and initializes the classes the UI thread needs, so it does not pay for class loading. */
    private void preloadClasses() {
        ClassLoader loader = getClass().getClassLoader();
        for (String name : new String[] { "it.sco.tabcolors.TabPainter", "it.sco.tabcolors.ColorContrast",
                "it.sco.tabcolors.EditorProjects", "it.sco.tabcolors.ProjectColorDecorator",
                "it.sco.tabcolors.TabPainter$FolderHooks" }) {
            try {
                Class.forName(name, true, loader);
            } catch (ClassNotFoundException e) {
                LOG.error("Project Tab Colors: cannot preload " + name, e);
            }
        }
    }

    /**
     * @param phases receives the time of each phase, for the debug log
     * @return number of editor tabs painted
     */
    private int hookWorkbench(IWorkbench workbench, StringBuilder phases) {
        long t = System.nanoTime();
        TabPainter.initThemeBackground(workbench);
        ProjectColorStore.get().addListener(project -> TabPainter.repaintAllLater());
        TabPainter.subscribeThemeChanges(workbench);
        workbench.addWindowListener(windowListener);
        t = phase(phases, "setup", t);
        int tabs = 0;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            tabs += hookWindow(window);
        }
        t = phase(phases, "tabs", t);
        IWorkbenchWindow active = workbench.getActiveWorkbenchWindow();
        if (active != null) {
            TabPainter.initActive(active.getActivePage());
        }
        phase(phases, "focus", t);
        return tabs;
    }

    private static long phase(StringBuilder phases, String name, long since) {
        long now = System.nanoTime();
        phases.append(phases.length() == 0 ? "" : ", ").append(name)
                .append(String.format(" %.1f", (now - since) / 1e6));
        return now;
    }

    private int hookWindow(IWorkbenchWindow window) {
        window.getPartService().addPartListener(partListener);
        window.addPageListener(pageListener);
        return TabPainter.repaintWindow(window);
    }

    private static void safely(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            LOG.error("Project Tab Colors: hooking the workbench failed", e);
        }
    }
}
