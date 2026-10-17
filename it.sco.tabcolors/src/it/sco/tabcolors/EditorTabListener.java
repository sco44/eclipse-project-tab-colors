package it.sco.tabcolors;

import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPartReference;

/**
 * Recolors an editor tab whenever its part may have a new or recreated tab item
 * (open, restore on first show, move to another stack) or a new input.
 */
final class EditorTabListener implements IPartListener2 {

    @Override
    public void partOpened(IWorkbenchPartReference ref) {
        paint(ref);
    }

    @Override
    public void partVisible(IWorkbenchPartReference ref) {
        paint(ref);
    }

    @Override
    public void partActivated(IWorkbenchPartReference ref) {
        paint(ref);
    }

    /** The stack loses its active CSS class here; reapply in case styling reset the item. */
    @Override
    public void partDeactivated(IWorkbenchPartReference ref) {
        paint(ref);
    }

    @Override
    public void partInputChanged(IWorkbenchPartReference ref) {
        paint(ref);
    }

    @Override
    public void partBroughtToTop(IWorkbenchPartReference ref) {
        paint(ref);
    }

    private static void paint(IWorkbenchPartReference ref) {
        if (ref instanceof IEditorReference editorRef) {
            TabPainter.paintLater(editorRef);
        }
    }
}
