package org.minimallycorrect.modpatcher;

import lombok.*;
import net.minecraftforge.gradle.user.UserBasePlugin;
import net.minecraftforge.gradle.util.caching.CachedTask;
import org.apache.log4j.Logger;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskInputs;
import org.minimallycorrect.javatransformer.api.JavaTransformer;
import org.minimallycorrect.mixin.internal.ApplicationType;
import org.minimallycorrect.mixin.internal.MixinApplicator;
import org.minimallycorrect.modpatcher.tasks.BinaryProcessor;
import org.minimallycorrect.modpatcher.tasks.SourceProcessor;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class ModPatcherPlugin implements Plugin<Project> {
	public static final String DEOBF_BINARY_TASK = "deobfMcMCP";
	public static final String REMAP_SOURCE_TASK = "remapMcSources";
	public static final String PLUGIN_FORGE_GRADLE_ID = "net.minecraftforge.gradle.forge";
	public static final Logger logger = Logger.getLogger("ModPatcher");
	public static final String CLASS_GRADLE_TASKACTIONWRAPPER = "org.gradle.api.internal.AbstractTask$TaskActionWrapper";
	public static final boolean DISABLE_CACHING = Boolean.parseBoolean(System.getProperty("ModPatcherGradle.disableCaching", "true"));

	public ModPatcherGradleExtension extension = new ModPatcherGradleExtension();
	private Project project;
	@Getter(lazy = true)
	private final JavaTransformer mixinTransformer = makeMixinTransformer();

	// Add before WriteCacheAction, or cache will be invalidated every time
	private static Task doBeforeWriteCacheAction(Task t, Action<Task> action) {
		val actions = t.getActions();

		int writeCachePosition = actions.size();
		for (int i = 0; i < actions.size(); i++) {
			if (innerAction(actions.get(i)).getClass().getName().endsWith("WriteCacheAction")) {
				writeCachePosition = i;
			}
		}

		if (!disableCaching(t) && writeCachePosition == actions.size()) {
			logger.warn("Failed to find WriteCacheAction in " + actions);
			actions.forEach(it -> logger.warn(innerAction(it)));
		}

		actions.add(writeCachePosition, action);

		return t;
	}

	private static boolean disableCaching(Task t) {
		if (DISABLE_CACHING && t instanceof CachedTask) {
			((CachedTask) t).setDoesCache(false);
			return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static Action<? super Task> innerAction(Action<? super Task> action) {
		Class<?> actionClass = action.getClass();
		if (actionClass.getName().equals(CLASS_GRADLE_TASKACTIONWRAPPER)) {
			try {
				val innerField = actionClass.getDeclaredField("action");
				innerField.setAccessible(true);
				val inner = (Action<? super Task>) innerField.get(action);
				if (inner != null)
					return inner;
			} catch (Exception e) {
				logger.warn("Failed to extract inner action from wrapper", e);
			}
		}
		return action;
	}

	@SneakyThrows
	@Override
	public void apply(Project project) {
		this.project = project;
		project.getPlugins().apply(PLUGIN_FORGE_GRADLE_ID);

		// Ensure ForgeGradle useLocalCache -> true
		val forgeGradle = ((UserBasePlugin<?>) project.getPlugins().getPlugin(PLUGIN_FORGE_GRADLE_ID));

		val blank_at = new File(project.getBuildDir(), "blank_at.cfg");
		if (!blank_at.exists() && (blank_at.getParentFile().isDirectory() || blank_at.getParentFile().mkdir()))
			Files.write(blank_at.toPath(), new byte[0]);

		forgeGradle.getExtension().at(blank_at);

		project.getExtensions().add("modpatcher", extension);

		project.afterEvaluate(this::afterEvaluate);
	}

	public JavaTransformer makeMixinTransformer() {
		val applicator = new MixinApplicator();
		applicator.setApplicationType(ApplicationType.PRE_PATCH);
		applicator.setNoMixinIsError(extension.noMixinIsError);
		for (SourceSetAndMixinDir entry : sourceDirsWithMixins(true)) {
			val sourceSet = entry.sourceSet;
			val mixinDir = entry.mixinDir;

			String configurationName = null;
			try {
				configurationName = sourceSet.getCompileClasspathConfigurationName();
			} catch (AbstractMethodError | NoSuchMethodError ignored) {
			}
			if (configurationName == null || configurationName.isEmpty())
				configurationName = "compile";
			project.getConfigurations().getByName(configurationName).getResolvedConfiguration().getLenientConfiguration().getFiles(Specs.satisfyAll())
				.forEach(it -> applicator.addSearchPath(it.toPath()));
			applicator.addSource(mixinDir.toPath(), extension.getMixinPackageToUse());
		}
		return applicator.getMixinTransformer();
	}

	public void mixinTransform(Path toTransform) {
		if (extension.shouldMixin()) {
			Path old = toTransform.resolveSibling("bak_" + toTransform.getFileName().toString());
			try {
				Files.deleteIfExists(old);
				Files.move(toTransform, old);
				try {
					getMixinTransformer().transform(old, toTransform);
				} finally {
					if (!Files.exists(toTransform)) {
						Files.move(old, toTransform);
					} else {
						Files.delete(old);
					}
				}
			} catch (IOException e) {
				throw new IOError(e);
			}
		}
	}

	@SuppressWarnings("unchecked")
	@SneakyThrows
	private List<SourceSetAndMixinDir> sourceDirsWithMixins(boolean root) {
		val results = new ArrayList<SourceSetAndMixinDir>();

		val mixinPackage = extension.getMixinPackageToUse().replace('.', '/');

		for (SourceSet s : (Iterable<SourceSet>) project.getProperties().get("sourceSets")) {
			for (File javaDir : s.getJava().getSrcDirs()) {
				File mixinDir = fileWithChild(javaDir, mixinPackage);
				if (mixinDir.isDirectory())
					results.add(new SourceSetAndMixinDir(s, root ? javaDir : mixinDir));
			}
		}

		if (results.isEmpty())
			throw new FileNotFoundException("Couldn't find any mixin packages! Searched for: " + mixinPackage);

		return results;
	}

	@RequiredArgsConstructor
	private static class SourceSetAndMixinDir {
		final SourceSet sourceSet;
		final File mixinDir;
	}

	private File fileWithChild(File javaDir, String mixinPackageToUse) {
		return mixinPackageToUse == null ? javaDir : new File(javaDir, mixinPackageToUse);
	}

	public void afterEvaluate(Project project) {
		val tasks = project.getTasks();

		val mixinDirs = sourceDirsWithMixins(false).stream().map(it -> it.mixinDir).collect(Collectors.toList()).toArray();

		addInputs(tasks.getByName(DEOBF_BINARY_TASK), mixinDirs);
		tasks.getByName(REMAP_SOURCE_TASK).getInputs().files(mixinDirs);
		doBeforeWriteCacheAction(tasks.getByName(DEOBF_BINARY_TASK),
			task -> BinaryProcessor.process(ModPatcherPlugin.this, task.getOutputs().getFiles().iterator().next())
		);
		doBeforeWriteCacheAction(tasks.getByName(REMAP_SOURCE_TASK),
			task -> SourceProcessor.process(ModPatcherPlugin.this, task.getOutputs().getFiles().iterator().next())
		);
	}

	private void addInputs(Task t, Object[] mixinDirs) {
		val inputs = t.getInputs();
		inputs.files(mixinDirs);
		addVersion(inputs, "ModPatcherGradle", this.getClass());
		addVersion(inputs, "Mixin", MixinApplicator.class);
		addVersion(inputs, "JavaTransformer", JavaTransformer.class);
	}

	private void addVersion(TaskInputs inputs, String name, Class<?> c) {
		inputs.property(name + "Version", c.getPackage().getImplementationVersion());
	}

	@Data
	public static class ModPatcherGradleExtension {
		public String mixinPackage = "";
		public boolean noMixinIsError = true;
		public boolean extractGeneratedSources = false;
		public boolean generateInheritanceHierarchy = false;
		public boolean generateStubMinecraftClasses = false;

		public String getMixinPackageToUse() {
			return Objects.equals(mixinPackage, "all") ? null : mixinPackage;
		}

		public boolean shouldMixin() {
			return !"".equals(mixinPackage);
		}
	}
}
