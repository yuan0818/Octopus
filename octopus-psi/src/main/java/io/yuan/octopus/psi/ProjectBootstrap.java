package io.yuan.octopus.psi;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.EmptyModuleType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootModificationUtil;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bootstraps an IntelliJ project for analysis.
 * <p>
 * Opens or imports the given directory as an IntelliJ project so that the
 * PSI tree, index, and Spring model become available.
 */
public class ProjectBootstrap {

    private static final Logger LOG = Logger.getInstance(ProjectBootstrap.class);

    /** Maximum time (seconds) to wait for IntelliJ indexing before giving up.
     *  Maven projects need extra time on first run (dependency resolution + JDK indexing). */
    private static final long INDEXING_TIMEOUT_SECONDS = 1200;

    /**
     * Open (or create) an IntelliJ Project from the given directory.
     * <p>
     * If the directory already contains an {@code .idea} folder the project is
     * opened as-is; otherwise a new project is created and the sources are
     * added as a single content root.
     */
    public Project openProject(Path projectDir) throws Exception {
        Path absolutePath = projectDir.toAbsolutePath().normalize();
        String projectName = absolutePath.getFileName().toString();

        AtomicReference<Project> projectRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                LOG.info("Opening or importing IntelliJ project: " + absolutePath);
                OpenProjectTask task = OpenProjectTask.Companion.build()
                    .withProjectName(projectName)
                    .withForceOpenInNewFrame(true);
                projectRef.set(ProjectUtil.openOrImport(absolutePath, task));
            } catch (Throwable t) {
                errorRef.set(t);
            }
        }, ModalityState.defaultModalityState());

        if (errorRef.get() != null) {
            if (errorRef.get() instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(errorRef.get());
        }

        Project project = projectRef.get();
        if (project == null) {
            throw new IllegalStateException("IntelliJ could not open or import project: " + absolutePath);
        }

        // Wait for Maven/Gradle import to complete before proceeding.
        // Poll for module-count stability.  The first 5 iterations use a 1s interval;
        // after that we wait for 3 consecutive stable readings (3 s idle).
        LOG.info("Waiting for Maven module import to stabilise...");
        {
            int prev = ModuleManager.getInstance(project).getModules().length;
            int stable = 0;
            for (int i = 0; i < 30; i++) {
                try { TimeUnit.SECONDS.sleep(1); } catch (InterruptedException e) { break; }
                int cur = ModuleManager.getInstance(project).getModules().length;
                if (cur == prev) {
                    if (++stable >= 3) break;
                } else {
                    prev = cur;
                    stable = 0;
                }
            }
            LOG.info("Module import stable: " + prev + " modules");
        }

        ensureContentModule(project, absolutePath, projectName);
        return project;
    }

    /**
     * Plain source directories may not have an IntelliJ, Gradle, or Maven model.
     * Attach a lightweight content module so PSI indexing still sees their sources.
     */
    private void ensureContentModule(Project project, Path projectDir, String projectName) {
        if (ModuleManager.getInstance(project).getModules().length > 0) {
            return;
        }
        LOG.info("No imported modules found; attaching fallback content module for: " + projectDir);
        ApplicationManager.getApplication().invokeAndWait(() ->
                WriteAction.run(() -> {
                    Module module = ModuleManager.getInstance(project)
                            .newNonPersistentModule(projectName, EmptyModuleType.EMPTY_MODULE);
                    ModuleRootModificationUtil.addContentRoot(module, projectDir.toString());
                }), ModalityState.defaultModalityState());
    }

    /**
     * Blocks until the project is fully loaded: modules discovered, dependencies
     * resolved, and indexing complete.
     * <p>
     * Uses DumbService's dumb-mode → smart-mode transition to detect when indexing
     * finishes.  When a project opens with cached indices it may be smart immediately;
     * in that case we schedule a re-index of all project files to ensure any newly
     * resolved Maven dependencies are also indexed before extraction.
     *
     * @throws RuntimeException if indexing times out
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void waitForIndexing(Project project) throws InterruptedException {
        // First ensure modules are loaded.  Poll for module-count stability with a
        // brief timeout so we don't block extraction on large multi-module imports.
        int prevModules = ModuleManager.getInstance(project).getModules().length;
        int stableCount = 0;
        for (int i = 0; i < 30; i++) {
            TimeUnit.SECONDS.sleep(1);
            int curModules = ModuleManager.getInstance(project).getModules().length;
            if (curModules == prevModules) {
                stableCount++;
            } else {
                prevModules = curModules;
                stableCount = 0;
            }
            if (stableCount >= 3) {
                LOG.info("Module import stable: " + prevModules + " modules");
                break;
            }
        }

        // Now wait for indexing.  Subscribe to the dumb-mode exit event so we
        // know when indexing is done.
        CountDownLatch indexLatch = new CountDownLatch(1);
        DumbService dumbService = DumbService.getInstance(project);

        if (dumbService.isDumb()) {
            LOG.info("Indexing in progress, waiting for completion (timeout "
                     + INDEXING_TIMEOUT_SECONDS + "s)...");
        } else {
            // Already smart — schedule a re-index to catch newly resolved dependencies.
            LOG.info("Project is smart; scheduling re-index for dependency safety...");
            com.intellij.openapi.vfs.VirtualFile projectDir = project.getProjectFile();
            if (projectDir != null) {
                com.intellij.util.indexing.FileBasedIndex.getInstance().requestReindex(projectDir);
            }
            // Wait a few seconds for dumb mode to begin
            for (int i = 0; i < 10; i++) {
                if (dumbService.isDumb()) {
                    LOG.info("Re-index started.");
                    break;
                }
                TimeUnit.SECONDS.sleep(1);
            }
            if (!dumbService.isDumb()) {
                LOG.info("No re-index triggered (dependencies already up-to-date); proceeding.");
                return;
            }
        }

        dumbService.runWhenSmart(indexLatch::countDown);
        boolean indexed = indexLatch.await(INDEXING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!indexed) {
            throw new RuntimeException(
                "Indexing timed out after " + INDEXING_TIMEOUT_SECONDS + "s for project: " + project.getName());
        }
        LOG.info("Indexing complete.");
    }

    /**
     * Closes and disposes a project, freeing associated PSI trees, index
     * data, and virtual file system caches.
     */
    public void closeProject(Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }
        LOG.info("Closing project: " + project.getName());
        ApplicationManager.getApplication().invokeAndWait(() ->
                ProjectUtil.closeAndDispose(project), ModalityState.defaultModalityState());
    }
}
