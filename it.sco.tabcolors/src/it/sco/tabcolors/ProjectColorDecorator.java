package it.sco.tabcolors;

import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.BaseLabelProvider;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProviderChangedEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.PlatformUI;

/**
 * F3: shows project nodes in Project Explorer / Package Explorer in the project's
 * color, so the explorer doubles as a legend for the tabs. Children are not decorated.
 */
public class ProjectColorDecorator extends BaseLabelProvider implements ILightweightLabelDecorator {

    private static final ILog LOG = Platform.getLog(ProjectColorDecorator.class);

    private final Consumer<IProject> storeListener = this::colorChanged;

    public ProjectColorDecorator() {
        ProjectColorStore.get().addListener(storeListener);
    }

    @Override
    public void decorate(Object element, IDecoration decoration) {
        try {
            IProject project = Adapters.adapt(element, IProject.class);
            RGB rgb = ProjectColorStore.get().colorOf(project); // null if closed
            if (rgb != null) {
                decoration.setForegroundColor(new Color(rgb));
            }
        } catch (RuntimeException e) {
            LOG.error("Cannot decorate " + element, e);
        }
    }

    @Override
    public boolean isLabelProperty(Object element, String property) {
        return false;
    }

    @Override
    public void dispose() {
        ProjectColorStore.get().removeListener(storeListener);
        super.dispose();
    }

    /** Store events arrive on any thread; label updates must run on the UI thread. */
    private void colorChanged(IProject project) {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }
        PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
            try {
                fireLabelProviderChanged(new LabelProviderChangedEvent(this, project));
            } catch (RuntimeException e) {
                LOG.error("Cannot refresh project color label of " + project.getName(), e);
            }
        });
    }
}
