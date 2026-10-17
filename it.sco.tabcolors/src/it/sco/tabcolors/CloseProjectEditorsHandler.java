package it.sco.tabcolors;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * F5: closes every editor of the target project in the active page in one step.
 * Dirty editors prompt to save; unrestored editors close without being restored.
 */
public class CloseProjectEditorsHandler extends AbstractHandler {

    private static final ILog LOG = Platform.getLog(CloseProjectEditorsHandler.class);

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(EditorProjects.targetProject(evaluationContext) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        IProject target = EditorProjects.targetProject(event.getApplicationContext());
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        if (target == null || page == null) {
            return null;
        }
        try {
            List<IEditorReference> toClose = new ArrayList<>();
            for (IEditorReference ref : page.getEditorReferences()) {
                if (closes(EditorProjects.projectOf(ref), target)) {
                    toClose.add(ref);
                }
            }
            if (!toClose.isEmpty()) {
                page.closeEditors(toClose.toArray(IEditorReference[]::new), true);
            }
        } catch (RuntimeException e) {
            LOG.error("Cannot close editors for project " + target.getName(), e);
        }
        return null;
    }

    /** Whether an editor of {@code editorProject} closes for {@code target}. */
    protected boolean closes(IProject editorProject, IProject target) {
        return target.equals(editorProject);
    }
}
