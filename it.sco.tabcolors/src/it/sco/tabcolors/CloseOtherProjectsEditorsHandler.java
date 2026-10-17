package it.sco.tabcolors;

import org.eclipse.core.resources.IProject;

/**
 * F5: closes every editor whose project differs from the target project. Editors
 * with no project (external files, compare editors) stay open.
 */
public class CloseOtherProjectsEditorsHandler extends CloseProjectEditorsHandler {

    @Override
    protected boolean closes(IProject editorProject, IProject target) {
        return editorProject != null && !target.equals(editorProject);
    }
}
