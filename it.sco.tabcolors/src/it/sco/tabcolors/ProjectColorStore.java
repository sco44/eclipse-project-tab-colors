package it.sco.tabcolors;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.swt.graphics.RGB;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Color model shared by all features: one color per project, stored in the
 * project's own preferences ({@code <project>/.settings/it.sco.tabcolors.prefs},
 * key {@code color}, value {@code #RRGGBB}) so it can be committed with the project.
 * <p>
 * Projects without an explicit color get a stable color hashed from their name.
 * Closed or missing projects have no color.
 * <p>
 * <b>Threading:</b> listeners are called on whatever thread caused the change
 * (the caller of {@link #setColor}, a resource-change notification thread, ...).
 * UI consumers must hop to the UI thread themselves with {@code Display.asyncExec}.
 * A single edit may notify the same project more than once.
 */
public final class ProjectColorStore {

    static final String QUALIFIER = "it.sco.tabcolors";
    static final String KEY = "color";
    static final IPath PREFS_PATH = IPath.fromPortableString(".settings/" + QUALIFIER + ".prefs");

    static final float HASH_SATURATION = 0.65f;
    static final float HASH_BRIGHTNESS = 0.85f;

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final ILog LOG = Platform.getLog(ProjectColorStore.class);

    private static ProjectColorStore instance;

    private final IWorkspace workspace;
    private final Map<String, RGB> cache = new ConcurrentHashMap<>();
    /** Bumped on every invalidation so a resolve racing with it never caches a stale value. */
    private final AtomicLong generation = new AtomicLong();
    /** Project name -> prefs node we listen to, with its listener. */
    private final Map<String, Watch> watchedNodes = new ConcurrentHashMap<>();
    private final List<Consumer<IProject>> listeners = new CopyOnWriteArrayList<>();
    private final IResourceChangeListener resourceListener = this::resourceChanged;

    /** The shared store, created on first use. */
    public static synchronized ProjectColorStore get() {
        if (instance == null) {
            instance = new ProjectColorStore(ResourcesPlugin.getWorkspace());
        }
        return instance;
    }

    ProjectColorStore(IWorkspace workspace) {
        this.workspace = workspace;
        workspace.addResourceChangeListener(resourceListener, IResourceChangeEvent.POST_CHANGE);
    }

    /** Detaches all workspace and preference listeners. */
    void dispose() {
        workspace.removeResourceChangeListener(resourceListener);
        for (String name : watchedNodes.keySet()) {
            unwatch(name);
        }
        cache.clear();
        listeners.clear();
    }

    // ---- public API --------------------------------------------------------

    /**
     * The project's color: explicit value if valid, else hashed from the name.
     * Returns {@code null} for {@code null}, closed or non-existent projects.
     */
    public RGB colorOf(IProject project) {
        if (project == null || !project.isOpen()) {
            return null;
        }
        String name = project.getName();
        RGB cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        long gen = generation.get();
        RGB explicit = readExplicit(project);
        RGB rgb = explicit != null ? explicit : hashed(name);
        if (generation.get() == gen) {
            cache.put(name, rgb);
        }
        return rgb;
    }

    /** Whether the project is open and has a valid {@code color} value in its prefs. */
    public boolean hasExplicitColor(IProject project) {
        return project != null && project.isOpen() && readExplicit(project) != null;
    }

    /**
     * Stores an explicit color and flushes it to the project's prefs file.
     *
     * @throws BackingStoreException if the prefs file cannot be written
     */
    public void setColor(IProject project, RGB rgb) throws BackingStoreException {
        Objects.requireNonNull(rgb, "rgb");
        requireOpen(project);
        IEclipsePreferences node = node(project);
        node.put(KEY, toHex(rgb));
        node.flush();
        invalidate(project);
    }

    /**
     * Removes the explicit color. If the node is left empty it is removed, so no
     * empty prefs file stays behind.
     *
     * @throws BackingStoreException if the prefs file cannot be written
     */
    public void resetColor(IProject project) throws BackingStoreException {
        requireOpen(project);
        IEclipsePreferences node = node(project);
        node.remove(KEY);
        // An empty node deletes its prefs file on flush; removing the node first would skip that.
        node.flush();
        if (node.keys().length == 0) {
            unwatch(project.getName());
            node.removeNode();
        }
        invalidate(project);
    }

    public void addListener(Consumer<IProject> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(Consumer<IProject> listener) {
        listeners.remove(listener);
    }

    // ---- pure helpers (package-private for tests) ---------------------------

    /** Parses {@code #RRGGBB} (surrounding whitespace ignored); {@code null} if invalid. */
    static RGB parse(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (!HEX.matcher(v).matches()) {
            return null;
        }
        int rgb = Integer.parseInt(v.substring(1), 16);
        return new RGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    /** Formats as upper-case {@code #RRGGBB}. */
    static String toHex(RGB rgb) {
        return String.format("#%02X%02X%02X", rgb.red, rgb.green, rgb.blue);
    }

    /** Stable fallback color derived only from the project name. */
    static RGB hashed(String projectName) {
        int hue = (projectName.hashCode() & 0x7FFFFFFF) % 360;
        return new RGB((float) hue, HASH_SATURATION, HASH_BRIGHTNESS);
    }

    // ---- internals ---------------------------------------------------------

    private static void requireOpen(IProject project) {
        if (project == null || !project.isOpen()) {
            throw new IllegalArgumentException("Project is not open: " + project);
        }
    }

    private static IEclipsePreferences node(IProject project) {
        return new ProjectScope(project).getNode(QUALIFIER);
    }

    private RGB readExplicit(IProject project) {
        try {
            IEclipsePreferences node = node(project);
            watch(project, node);
            return parse(node.get(KEY, null));
        } catch (RuntimeException e) {
            LOG.error("Cannot read tab color of project " + project.getName(), e);
            return null;
        }
    }

    /**
     * Attaches a prefs listener to the project's current node. Re-attached after
     * every invalidation because a removed node never notifies again.
     */
    private void watch(IProject project, IEclipsePreferences node) {
        Watch watch = new Watch(node, event -> preferenceChanged(project, event));
        if (watchedNodes.putIfAbsent(project.getName(), watch) == null) {
            node.addPreferenceChangeListener(watch.listener());
        }
    }

    private void unwatch(String name) {
        Watch watch = watchedNodes.remove(name);
        if (watch != null) {
            try {
                watch.node().removePreferenceChangeListener(watch.listener());
            } catch (IllegalStateException removedNode) {
                // node already removed: its listeners are gone with it
            }
        }
    }

    private void preferenceChanged(IProject project, IEclipsePreferences.PreferenceChangeEvent event) {
        try {
            if (KEY.equals(event.getKey())) {
                invalidate(project);
            }
        } catch (RuntimeException e) {
            LOG.error("Error handling tab color preference change", e);
        }
    }

    private record Watch(IEclipsePreferences node, IPreferenceChangeListener listener) {
    }

    private void resourceChanged(IResourceChangeEvent event) {
        try {
            IResourceDelta root = event.getDelta();
            if (root == null) {
                return;
            }
            for (IResourceDelta delta : root.getAffectedChildren()) {
                if (affectsColor(delta)) {
                    invalidate((IProject) delta.getResource());
                }
            }
        } catch (RuntimeException e) {
            LOG.error("Error handling resource change for tab colors", e);
        }
    }

    /** Project added/removed (incl. rename, delete), opened/closed, or its prefs file changed. */
    private static boolean affectsColor(IResourceDelta delta) {
        if (delta.getResource().getType() != IResource.PROJECT) {
            return false;
        }
        int kind = delta.getKind();
        if (kind == IResourceDelta.ADDED || kind == IResourceDelta.REMOVED) {
            return true;
        }
        int flags = delta.getFlags();
        if ((flags & (IResourceDelta.OPEN | IResourceDelta.MOVED_FROM | IResourceDelta.MOVED_TO)) != 0) {
            return true;
        }
        return delta.findMember(PREFS_PATH) != null;
    }

    private void invalidate(IProject project) {
        String name = project.getName();
        generation.incrementAndGet();
        cache.remove(name);
        unwatch(name);
        for (Consumer<IProject> listener : listeners) {
            try {
                listener.accept(project);
            } catch (RuntimeException e) {
                LOG.error("Tab color listener failed for project " + name, e);
            }
        }
    }
}
