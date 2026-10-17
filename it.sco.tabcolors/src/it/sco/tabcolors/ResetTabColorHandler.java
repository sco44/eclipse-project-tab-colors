package it.sco.tabcolors;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.osgi.service.prefs.BackingStoreException;

/**
 * F2: removes the explicit color so the project falls back to its hashed color.
 * Enabled only when the project has an explicit color.
 */
public class ResetTabColorHandler extends AbstractHandler {

    private static final ILog LOG = Platform.getLog(ResetTabColorHandler.class);

    @Override
    public void setEnabled(Object evaluationContext) {
        IProject project = EditorProjects.selectedProject(evaluationContext);
        setBaseEnabled(ProjectColorStore.get().hasExplicitColor(project));
    }

    @Override
    public Object execute(ExecutionEvent event) {
        IProject project = EditorProjects.projectOf(HandlerUtil.getCurrentSelection(event));
        ProjectColorStore store = ProjectColorStore.get();
        if (!store.hasExplicitColor(project)) {
            return null;
        }
        try {
            store.resetColor(project);
        } catch (BackingStoreException | RuntimeException e) {
            LOG.error("Cannot reset tab color of project " + project.getName(), e);
            MessageDialog.openError(HandlerUtil.getActiveShell(event), "Project Tab Colors",
                    "Could not reset the tab color of project '" + project.getName() + "':\n" + e.getMessage());
        }
        return null;
    }
}
