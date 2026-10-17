package it.sco.tabcolors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs against a real (headless) workspace so preference and resource events are genuine. */
class ProjectColorStoreTest {

    private static final RGB RED = new RGB(0xE0, 0x6C, 0x75);
    private static final RGB BLUE = new RGB(0x61, 0xAF, 0xEF);

    private final IWorkspace workspace = ResourcesPlugin.getWorkspace();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Consumer<IProject> recorder = p -> events.add(p.getName());
    private ProjectColorStore store;
    private IProject project;

    @BeforeEach
    void setUp() throws CoreException {
        store = new ProjectColorStore(workspace);
        store.addListener(recorder);
        project = createProject("svc-" + System.nanoTime());
    }

    @AfterEach
    void tearDown() throws CoreException {
        store.dispose();
        for (IProject p : workspace.getRoot().getProjects()) {
            p.delete(true, true, null);
        }
    }

    @Test
    void unsetProjectGetsHashedColor() {
        assertEquals(ProjectColorStore.hashed(project.getName()), store.colorOf(project));
        assertFalse(store.hasExplicitColor(project));
    }

    @Test
    void prefetchResolvesOpenProjectsOnly() throws Exception {
        IProject closed = createProject("closed-" + System.nanoTime());
        closed.close(null);
        store.setColor(project, RED);

        assertEquals(1, store.prefetch());
        assertEquals(RED, store.colorOf(project));
        assertNull(store.colorOf(closed));
    }

    @Test
    void nullAndMissingProjectsHaveNoColor() {
        assertNull(store.colorOf(null));
        assertNull(store.colorOf(workspace.getRoot().getProject("does-not-exist")));
    }

    @Test
    void setColorStoresFlushesAndNotifies() throws Exception {
        store.colorOf(project); // warm the cache with the hashed color
        events.clear();

        store.setColor(project, RED);

        awaitEvent(project.getName());
        assertEquals(RED, store.colorOf(project));
        assertTrue(store.hasExplicitColor(project));
        IFile prefs = prefsFile(project);
        assertTrue(prefs.exists(), "prefs file written");
        assertEquals("#E06C75", load(prefs).getProperty("color"));
    }

    @Test
    void editingPrefsFileInvalidatesCache() throws Exception {
        store.setColor(project, RED);
        assertEquals(RED, store.colorOf(project));
        events.clear();

        prefsFile(project).setContents(prefsContent("#61AFEF"), IResource.FORCE, null);

        awaitEvent(project.getName());
        assertEquals(BLUE, store.colorOf(project));
    }

    @Test
    void externalEditPickedUpOnRefresh() throws Exception {
        store.setColor(project, RED);
        assertEquals(RED, store.colorOf(project));
        events.clear();

        // Simulates a git pull: file changes on disk, then the workspace refreshes.
        Files.writeString(prefsFile(project).getLocation().toPath(),
                "eclipse.preferences.version=1\ncolor=#61AFEF\n", StandardCharsets.ISO_8859_1);
        project.refreshLocal(IResource.DEPTH_INFINITE, null);

        awaitEvent(project.getName());
        assertEquals(BLUE, store.colorOf(project));
    }

    @Test
    void invalidExplicitValueFallsBackToHash() throws Exception {
        createPrefsFile(project, "#12345");
        assertEquals(ProjectColorStore.hashed(project.getName()), store.colorOf(project));
        assertFalse(store.hasExplicitColor(project));
    }

    @Test
    void resetFallsBackToHashAndDeletesEmptyPrefsFile() throws Exception {
        store.setColor(project, RED);
        assertEquals(RED, store.colorOf(project));
        events.clear();

        store.resetColor(project);

        awaitEvent(project.getName());
        assertEquals(ProjectColorStore.hashed(project.getName()), store.colorOf(project));
        assertFalse(store.hasExplicitColor(project));
        assertFalse(prefsFile(project).exists(), "empty prefs file must not be left behind");
    }

    @Test
    void resetKeepsPrefsFileWithOtherKeys() throws Exception {
        IEclipsePreferences node = new ProjectScope(project).getNode(ProjectColorStore.QUALIFIER);
        node.put("other", "keep-me");
        store.setColor(project, RED);

        store.resetColor(project);

        assertFalse(store.hasExplicitColor(project));
        assertTrue(prefsFile(project).exists());
        Properties content = load(prefsFile(project));
        assertEquals("keep-me", content.getProperty("other"));
        assertNull(content.getProperty("color"));
    }

    /** After reset removed the node, a recreated prefs file (e.g. from git) must still be noticed. */
    @Test
    void prefsFileRecreatedAfterResetIsNoticed() throws Exception {
        store.setColor(project, RED);
        store.resetColor(project);
        assertEquals(ProjectColorStore.hashed(project.getName()), store.colorOf(project));
        events.clear();

        createPrefsFile(project, "#61AFEF");

        awaitEvent(project.getName());
        assertEquals(BLUE, store.colorOf(project));
    }

    @Test
    void closedProjectHasNoColor() throws Exception {
        store.setColor(project, RED);
        assertEquals(RED, store.colorOf(project));
        events.clear();

        project.close(null);

        awaitEvent(project.getName());
        assertNull(store.colorOf(project));
        assertFalse(store.hasExplicitColor(project));

        project.open(null);
        assertEquals(RED, store.colorOf(project));
    }

    @Test
    void renamedProjectKeepsColorUnderNewName() throws Exception {
        store.setColor(project, RED);
        assertEquals(RED, store.colorOf(project));
        String oldName = project.getName();
        String newName = oldName + "-renamed";
        events.clear();

        project.move(IPath.fromPortableString(newName), true, null);

        awaitEvent(oldName);
        awaitEvent(newName);
        IProject renamed = workspace.getRoot().getProject(newName);
        assertNull(store.colorOf(workspace.getRoot().getProject(oldName)));
        assertEquals(RED, store.colorOf(renamed));
    }

    @Test
    void deletedProjectHasNoColor() throws Exception {
        store.setColor(project, RED);
        assertEquals(RED, store.colorOf(project));
        events.clear();

        project.delete(true, true, null);

        awaitEvent(project.getName());
        assertNull(store.colorOf(project));
    }

    @Test
    void failingListenerDoesNotBreakOthers() throws Exception {
        store.removeListener(recorder);
        store.addListener(p -> {
            throw new IllegalStateException("boom");
        });
        store.addListener(recorder);

        store.setColor(project, RED);

        awaitEvent(project.getName());
    }

    @Test
    void removedListenerIsNotNotified() throws Exception {
        store.removeListener(recorder);
        events.clear(); // drop the ADDED/OPEN events from creating the project
        store.setColor(project, RED);
        Thread.sleep(200);
        assertTrue(events.isEmpty(), events.toString());
    }

    // ---- helpers -----------------------------------------------------------

    private IProject createProject(String name) throws CoreException {
        IProject p = workspace.getRoot().getProject(name);
        p.create(null);
        p.open(null);
        return p;
    }

    private static IFile prefsFile(IProject p) {
        return p.getFile(ProjectColorStore.PREFS_PATH);
    }

    private static void createPrefsFile(IProject p, String color) throws CoreException {
        IFolder settings = p.getFolder(".settings");
        if (!settings.exists()) {
            settings.create(true, true, null);
        }
        IFile file = prefsFile(p);
        if (file.exists()) {
            file.setContents(prefsContent(color), IResource.FORCE, null);
        } else {
            file.create(prefsContent(color), true, null);
        }
    }

    private static ByteArrayInputStream prefsContent(String color) {
        String text = "eclipse.preferences.version=1\ncolor=" + color + "\n";
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Reads the file the way Eclipse does: as a properties file ({@code \#} is an escaped {@code #}). */
    private static Properties load(IFile file) throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file.getLocation().toPath())) {
            props.load(in);
        }
        return props;
    }

    /** Waits until the store has notified the given project name, in any order. */
    private void awaitEvent(String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (events.contains(name)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("No store event for project " + name + " within 5 s; got " + events);
    }
}
