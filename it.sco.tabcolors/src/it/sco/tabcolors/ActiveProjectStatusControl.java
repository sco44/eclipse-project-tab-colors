package it.sco.tabcolors;

import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPartService;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;
import org.eclipse.ui.part.ISetSelectionTarget;

/**
 * F6: status bar label with the active editor's project, next to a swatch in the
 * project's color. Activating a view leaves it unchanged; closing the last editor
 * clears it. Clicking it reveals the project in Project Explorer. One instance per
 * workbench window, listening to that window's part service only.
 */
public class ActiveProjectStatusControl extends WorkbenchWindowControlContribution {

    private static final ILog LOG = Platform.getLog(ActiveProjectStatusControl.class);
    private static final String PROJECT_EXPLORER = "org.eclipse.ui.navigator.ProjectExplorer";
    private static final int MAX_CHARS = 30;
    private static final int SWATCH = 12;
    private static final int GAP = 5;
    private static final int PADDING = 4;

    private Canvas canvas;
    private Point preferredSize;
    private boolean geometryLogged;
    private IPartService partService;
    private IProject current;
    private RGB currentRgb;

    private final IPartListener2 partListener = new IPartListener2() {
        @Override
        public void partActivated(IWorkbenchPartReference ref) {
            editorChanged(ref);
        }

        @Override
        public void partBroughtToTop(IWorkbenchPartReference ref) {
            editorChanged(ref);
        }

        @Override
        public void partInputChanged(IWorkbenchPartReference ref) {
            editorChanged(ref);
        }

        @Override
        public void partClosed(IWorkbenchPartReference ref) {
            if (ref instanceof IEditorReference) {
                // After the close completes, so the closed editor is no longer listed.
                asyncExec(() -> {
                    IWorkbenchPage page = ref.getPage();
                    if (page != null && page.getEditorReferences().length == 0) {
                        show(null);
                    }
                });
            }
        }
    };

    private final Consumer<IProject> storeListener = project -> asyncExec(this::refreshFromActiveEditor);

    public ActiveProjectStatusControl() {
    }

    public ActiveProjectStatusControl(String id) {
        super(id);
    }

    @Override
    protected Control createControl(Composite parent) {
        // One self-drawn canvas: a Label is not reliably styled by the CSS theme in the
        // trim (dark text on a dark status bar), and the trim may give less height than
        // asked, so everything is drawn vertically centered in the space we get.
        canvas = new Canvas(parent, SWT.NONE) {
            @Override
            public Point computeSize(int widthHint, int heightHint, boolean changed) {
                return preferredSize;
            }
        };
        preferredSize = preferredSize();
        canvas.addPaintListener(this::paint);
        canvas.addListener(SWT.MouseUp, this::reveal);
        canvas.setCursor(canvas.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        canvas.addDisposeListener(e -> unhook());

        IWorkbenchWindow window = getWorkbenchWindow();
        if (window != null) {
            partService = window.getPartService();
            partService.addPartListener(partListener);
        }
        ProjectColorStore.get().addListener(storeListener);
        refreshFromActiveEditor();
        return canvas;
    }

    @Override
    public void dispose() {
        unhook();
        super.dispose();
    }

    private void unhook() {
        if (partService != null) {
            partService.removePartListener(partListener);
            partService = null;
        }
        ProjectColorStore.get().removeListener(storeListener);
    }

    private void editorChanged(IWorkbenchPartReference ref) {
        if (ref instanceof IEditorReference editorRef) {
            try {
                show(EditorProjects.projectOf(editorRef));
            } catch (RuntimeException e) {
                LOG.error("Cannot update active project in status bar", e);
            }
        }
    }

    private void refreshFromActiveEditor() {
        IWorkbenchWindow window = getWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        IEditorPart editor = page == null ? null : page.getActiveEditor();
        show(EditorProjects.projectOf(editor));
    }

    private void show(IProject project) {
        if (canvas == null || canvas.isDisposed()) {
            return;
        }
        RGB rgb = ProjectColorStore.get().colorOf(project);
        current = rgb == null ? null : project; // closed/missing projects show nothing
        currentRgb = rgb;
        canvas.setToolTipText(current == null ? null
                : current.getName() + " (click to show in Project Explorer)");
        canvas.redraw();
    }

    /** Swatch and name in the project color, adjusted for contrast against the actual background. */
    private void paint(PaintEvent event) {
        if (current == null || currentRgb == null) {
            return;
        }
        GC gc = event.gc;
        Rectangle area = visibleArea();
        RGB background = canvas.getBackground().getRGB();
        int x = PADDING;
        gc.setBackground(new Color(ColorContrast.readable(currentRgb, background, ColorContrast.LINE)));
        gc.fillRectangle(x, area.y + (area.height - SWATCH) / 2, SWATCH, SWATCH);
        x += SWATCH + GAP;
        String text = truncate(current.getName());
        Point extent = gc.textExtent(text);
        gc.setForeground(new Color(ColorContrast.readable(currentRgb, background, ColorContrast.TEXT)));
        gc.drawText(text, x, area.y + (area.height - extent.y) / 2, true);
        if (Platform.inDebugMode() && !geometryLogged) {
            geometryLogged = true;
            LOG.info("Project Tab Colors status bar: bounds=" + canvas.getBounds() + " client="
                    + canvas.getClientArea() + " visible=" + area + " text=" + extent);
        }
    }

    /**
     * The part of the canvas not clipped by any ancestor. The status trim can place
     * the control taller or lower than the strip that is actually shown, so content is
     * centered in what is visible rather than in the canvas.
     */
    private Rectangle visibleArea() {
        Rectangle visible = canvas.getClientArea();
        Display display = canvas.getDisplay();
        for (Composite ancestor = canvas.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            visible = visible.intersection(display.map(ancestor, canvas, ancestor.getClientArea()));
            if (ancestor instanceof Shell) {
                break;
            }
        }
        return visible.isEmpty() ? canvas.getClientArea() : visible;
    }

    private void reveal(Event event) {
        if (current == null || !current.isAccessible()) {
            return;
        }
        try {
            IWorkbenchPage page = getWorkbenchWindow().getActivePage();
            if (page == null) {
                return;
            }
            IViewPart view = page.showView(PROJECT_EXPLORER);
            ISetSelectionTarget target = Adapters.adapt(view, ISetSelectionTarget.class);
            if (target != null) {
                target.selectReveal(new StructuredSelection(current));
            }
        } catch (PartInitException | RuntimeException e) {
            LOG.error("Cannot show project " + current.getName() + " in Project Explorer", e);
        }
    }

    /** Wide enough for the longest project name in the workspace, capped at {@value #MAX_CHARS} chars. */
    private Point preferredSize() {
        GC gc = new GC(canvas);
        try {
            int textWidth = gc.textExtent("MMMMMMMM").x;
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                textWidth = Math.max(textWidth, gc.textExtent(truncate(project.getName())).x);
            }
            int height = Math.max(SWATCH, gc.textExtent("Ag").y);
            return new Point(PADDING + SWATCH + GAP + textWidth + PADDING, height);
        } finally {
            gc.dispose();
        }
    }

    private static String truncate(String name) {
        return name.length() <= MAX_CHARS ? name : name.substring(0, MAX_CHARS - 1) + "…";
    }

    private void asyncExec(Runnable runnable) {
        if (canvas == null || canvas.isDisposed()) {
            return;
        }
        canvas.getDisplay().asyncExec(() -> {
            try {
                if (!canvas.isDisposed()) {
                    runnable.run();
                }
            } catch (RuntimeException e) {
                LOG.error("Cannot update active project in status bar", e);
            }
        });
    }
}
