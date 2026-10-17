package it.sco.tabcolors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
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
            safely(() -> TabPainter.repaintPage(page));
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

    @Override
    public void earlyStartup() {
        long start = System.nanoTime();
        IWorkbench workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().asyncExec(() -> {
            long uiStart = System.nanoTime();
            safely(() -> hookWorkbench(workbench));
            if (Platform.inDebugMode()) {
                LOG.info(String.format("Project Tab Colors startup: %.1f ms (earlyStartup) + %.1f ms (UI hookup)",
                        (uiStart - start) / 1e6, (System.nanoTime() - uiStart) / 1e6));
            }
        });
    }

    private void hookWorkbench(IWorkbench workbench) {
        TabPainter.initThemeBackground(workbench.getDisplay());
        ProjectColorStore.get().addListener(project -> TabPainter.repaintAllLater());
        TabPainter.subscribeThemeChanges(workbench);
        workbench.addWindowListener(windowListener);
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            hookWindow(window);
        }
        IWorkbenchWindow active = workbench.getActiveWorkbenchWindow();
        if (active != null) {
            TabPainter.initActive(active.getActivePage());
        }
        // Explorer labels may have been decorated before the theme background was known.
        workbench.getDecoratorManager().update(ProjectColorDecorator.ID);
    }

    private void hookWindow(IWorkbenchWindow window) {
        window.getPartService().addPartListener(partListener);
        window.addPageListener(pageListener);
        for (IWorkbenchPage page : window.getPages()) {
            TabPainter.repaintPage(page);
        }
    }

    private static void safely(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            LOG.error("Project Tab Colors: hooking the workbench failed", e);
        }
    }
}
