package it.sco.tabcolors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PartInitException;

/**
 * Resolves the project an editor's input belongs to, without restoring editors.
 */
public final class EditorProjects {

    private static final ILog LOG = Platform.getLog(EditorProjects.class);

    private EditorProjects() {
    }

    /**
     * Project of the editor's input, or {@code null} for inputs outside any project
     * (external files, compare editors, non-resource inputs). Never restores an
     * unrestored editor.
     */
    public static IProject projectOf(IEditorReference ref) {
        if (ref == null) {
            return null;
        }
        try {
            IEditorPart editor = ref.getEditor(false);
            IEditorInput input = editor != null ? editor.getEditorInput() : ref.getEditorInput();
            return projectOf(input);
        } catch (PartInitException e) {
            return null;
        } catch (RuntimeException e) {
            LOG.error("Cannot resolve project of editor " + ref.getId(), e);
            return null;
        }
    }

    /** Project of a restored editor's input, or {@code null}. */
    public static IProject projectOf(IEditorPart editor) {
        return editor == null ? null : projectOf(editor.getEditorInput());
    }

    /** Project of an editor input, or {@code null}. */
    public static IProject projectOf(IEditorInput input) {
        if (input == null) {
            return null;
        }
        IResource resource = Adapters.adapt(input, IResource.class);
        if (resource == null && input instanceof IFileEditorInput fileInput) {
            resource = fileInput.getFile();
        }
        return resource == null ? null : resource.getProject();
    }
}
