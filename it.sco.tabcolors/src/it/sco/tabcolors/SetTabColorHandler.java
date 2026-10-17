package it.sco.tabcolors;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.osgi.service.prefs.BackingStoreException;

/**
 * F2: picks a color for the target project: the selected resource's project from the
 * explorer menu, the active editor's project from the status bar button. The store event drives
 * the repaint of tabs, explorer labels and status bar; this handler never paints.
 */
public class SetTabColorHandler extends AbstractHandler {

    private static final ILog LOG = Platform.getLog(SetTabColorHandler.class);

    @Override
    public void setEnabled(Object evaluationContext) {
        IProject project = EditorProjects.targetProject(evaluationContext);
        setBaseEnabled(project != null && project.isOpen());
    }

    @Override
    public Object execute(ExecutionEvent event) {
        IProject project = EditorProjects.targetProject(event.getApplicationContext());
        if (project == null || !project.isOpen()) {
            return null;
        }
        Shell shell = HandlerUtil.getActiveShell(event);
        ProjectColorStore store = ProjectColorStore.get();

        ColorDialog dialog = new ColorDialog(shell);
        dialog.setText("Tab Color for " + project.getName());
        dialog.setRGB(store.colorOf(project));
        RGB rgb = dialog.open();
        if (rgb == null) {
            return null; // cancelled
        }
        try {
            store.setColor(project, rgb);
        } catch (BackingStoreException | RuntimeException e) {
            LOG.error("Cannot save tab color of project " + project.getName(), e);
            MessageDialog.openError(shell, "Project Tab Colors",
                    "Could not save the tab color of project '" + project.getName() + "':\n" + e.getMessage());
        }
        return null;
    }
}
