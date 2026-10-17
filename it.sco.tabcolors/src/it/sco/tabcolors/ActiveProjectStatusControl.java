package it.sco.tabcolors;

import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
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

    private Composite root;
    private Canvas swatch;
    private Label label;
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
        root = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginHeight = 0;
        layout.marginWidth = 4;
        layout.horizontalSpacing = 5;
        root.setLayout(layout);

        swatch = new Canvas(root, SWT.NONE);
        GridData swatchData = new GridData(SWT.CENTER, SWT.CENTER, false, true);
        swatchData.widthHint = SWATCH;
        swatchData.heightHint = SWATCH;
        swatch.setLayoutData(swatchData);
        swatch.addPaintListener(e -> {
            if (currentRgb != null) {
                e.gc.setBackground(new Color(currentRgb));
                e.gc.fillRectangle(0, 0, SWATCH, SWATCH);
            }
        });

        label = new Label(root, SWT.NONE);
        GridData labelData = new GridData(SWT.FILL, SWT.CENTER, true, true);
        labelData.widthHint = labelWidth();
        label.setLayoutData(labelData);

        Listener reveal = this::reveal;
        for (Control control : new Control[] { root, swatch, label }) {
            control.addListener(SWT.MouseUp, reveal);
            control.setCursor(control.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        }
        root.addDisposeListener(e -> unhook());

        IWorkbenchWindow window = getWorkbenchWindow();
        if (window != null) {
            partService = window.getPartService();
            partService.addPartListener(partListener);
        }
        ProjectColorStore.get().addListener(storeListener);
        refreshFromActiveEditor();
        return root;
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
        if (label == null || label.isDisposed()) {
            return;
        }
        RGB rgb = ProjectColorStore.get().colorOf(project);
        current = rgb == null ? null : project; // closed/missing projects show nothing
        currentRgb = rgb == null ? null
                : ColorContrast.readable(rgb, root.getBackground().getRGB(), ColorContrast.LINE);
        String name = current == null ? "" : current.getName();
        label.setText(truncate(name));
        String tooltip = current == null ? null : name + " (click to show in Project Explorer)";
        root.setToolTipText(tooltip);
        swatch.setToolTipText(tooltip);
        label.setToolTipText(tooltip);
        swatch.setVisible(current != null);
        swatch.redraw();
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
    private int labelWidth() {
        GC gc = new GC(label);
        try {
            int width = gc.textExtent("MMMMMMMM").x;
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                width = Math.max(width, gc.textExtent(truncate(project.getName())).x);
            }
            return width + 4;
        } finally {
            gc.dispose();
        }
    }

    private static String truncate(String name) {
        return name.length() <= MAX_CHARS ? name : name.substring(0, MAX_CHARS - 1) + "…";
    }

    private void asyncExec(Runnable runnable) {
        if (root == null || root.isDisposed()) {
            return;
        }
        root.getDisplay().asyncExec(() -> {
            try {
                if (!root.isDisposed()) {
                    runnable.run();
                }
            } catch (RuntimeException e) {
                LOG.error("Cannot update active project in status bar", e);
            }
        });
    }
}
