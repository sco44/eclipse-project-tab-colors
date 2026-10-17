package it.sco.tabcolors;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
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

    static final String ID = "it.sco.tabcolors.projectDecorator";

    private static final ILog LOG = Platform.getLog(ProjectColorDecorator.class);

    /** The live decorator, if enabled; lets the theme background change refresh just its labels. */
    private static volatile ProjectColorDecorator instance;
    private static final RGB NOT_DECORATED = new RGB(0, 0, 0);

    private final Consumer<IProject> storeListener = this::colorChanged;
    /** Background the current labels were adjusted against ({@link #NOT_DECORATED} before any). */
    private volatile RGB decoratedAgainst = NOT_DECORATED;

    public ProjectColorDecorator() {
        ProjectColorStore.get().addListener(storeListener);
        instance = this;
    }

    /**
     * Called by TabPainter (UI thread) when the editor tab background changes, e.g. on
     * startup or a theme switch. Refreshes only the project labels, and only if they
     * were drawn against another background: a global decorator update would rerun
     * every decorator (Git included) on every visible element.
     */
    static void backgroundChanged(RGB background) {
        ProjectColorDecorator decorator = instance;
        if (decorator != null && decorator.decoratedAgainst != NOT_DECORATED
                && !Objects.equals(decorator.decoratedAgainst, background)) {
            decorator.refreshProjects();
        }
    }

    @Override
    public void decorate(Object element, IDecoration decoration) {
        try {
            // Adapt to IResource, not IProject: an adapter mapping children to their
            // project must not color the children.
            if (!(Adapters.adapt(element, IResource.class) instanceof IProject project)) {
                return;
            }
            RGB rgb = ProjectColorStore.get().colorOf(project); // null if closed
            if (rgb != null) {
                // Decoration may run off the UI thread, so use the theme background
                // last seen by TabPainter rather than the tree's own color.
                RGB background = TabPainter.themeBackground();
                decoratedAgainst = background;
                RGB readable = ColorContrast.readable(rgb, background, ColorContrast.TEXT);
                decoration.setForegroundColor(new Color(readable));
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
        if (instance == this) {
            instance = null;
        }
        ProjectColorStore.get().removeListener(storeListener);
        super.dispose();
    }

    private void refreshProjects() {
        List<IProject> open = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen()) {
                open.add(project);
            }
        }
        if (!open.isEmpty()) {
            fireLabelProviderChanged(new LabelProviderChangedEvent(this, open.toArray()));
        }
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
