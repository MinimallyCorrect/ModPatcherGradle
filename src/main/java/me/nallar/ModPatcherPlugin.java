package me.nallar;

import lombok.Data;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.val;
import me.nallar.javatransformer.api.JavaTransformer;
import me.nallar.mixin.internal.MixinApplicator;
import me.nallar.modpatcher.tasks.BinaryProcessor;
import me.nallar.modpatcher.tasks.SourceProcessor;
import net.minecraftforge.gradle.user.UserBaseExtension;
import net.minecraftforge.gradle.user.UserBasePlugin;
import net.minecraftforge.gradle.util.caching.CachedTask;
import org.apache.log4j.Logger;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.SourceSet;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ModPatcherPlugin implements Plugin<Project> {
	public static final String RECOMPILE_MC_TASK = "recompileMc";
	public static final String SETUP_CI_WORKSPACE_TASK = "setupCiWorkspace";
	public static final String COMPILE_JAVA_TASK = "compileJava";
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

		if (DISABLE_CACHING && t instanceof CachedTask) {
			((CachedTask) t).setDoesCache(false);
		} else if (writeCachePosition == actions.size()) {
			logger.warn("Failed to find WriteCacheAction in " + actions);
			actions.forEach(it -> logger.warn(innerAction(it)));
		}

		actions.add(writeCachePosition, action);

		return t;
	}

	@SuppressWarnings("unchecked")
	private static Action<? super Task> innerAction(Action<? super Task> action) {
		val actionClass = action.getClass();
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

	@Override
	public void apply(Project project) {
		this.project = project;
		project.getPlugins().apply(PLUGIN_FORGE_GRADLE_ID);

		// Ensure ForgeGradle useLocalCache -> true
		val forgeGradle = ((UserBasePlugin) project.getPlugins().getPlugin(PLUGIN_FORGE_GRADLE_ID));
		((UserBaseExtension) forgeGradle.getExtension()).setUseDepAts(true);

		project.getExtensions().add("modpatcher", extension);

		project.afterEvaluate(this::afterEvaluate);
	}

	public JavaTransformer makeMixinTransformer() {
		val applicator = new MixinApplicator();
		applicator.setNoMixinIsError(extension.noMixinIsError);
		for (File file : sourceDirsWithMixins(true)) {
			applicator.addSource(file.toPath(), extension.getMixinPackageToUse());
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
	private List<File> sourceDirsWithMixins(boolean root) {
		val results = new ArrayList<File>();

		val mixinPackage = extension.getMixinPackageToUse().replace('.', '/');

		for (SourceSet s : (Iterable<SourceSet>) project.getProperties().get("sourceSets")) {
			for (File javaDir : s.getJava().getSrcDirs()) {
				File mixinDir = fileWithChild(javaDir, mixinPackage);
				if (mixinDir.isDirectory()) {
					if (root)
						results.add(javaDir);
					else
						results.add(mixinDir);
				}
			}
		}

		if (results.isEmpty())
			throw new FileNotFoundException("Couldn't find any mixin packages! Searched for: " + mixinPackage);

		return results;
	}

	private File fileWithChild(File javaDir, String mixinPackageToUse) {
		return mixinPackageToUse == null ? javaDir : new File(javaDir, mixinPackageToUse);
	}

	public void afterEvaluate(Project project) {
		val tasks = project.getTasks();

		val mixinDirs = sourceDirsWithMixins(true).toArray();

		tasks.getByName(DEOBF_BINARY_TASK).getInputs().files(mixinDirs);
		tasks.getByName(REMAP_SOURCE_TASK).getInputs().files(mixinDirs);
		doBeforeWriteCacheAction(tasks.getByName(DEOBF_BINARY_TASK), new Action<Task>() {
			@SneakyThrows
			@Override
			public void execute(Task task) {
				File f = task.getOutputs().getFiles().iterator().next();

				BinaryProcessor.process(ModPatcherPlugin.this, f);
			}
		});
		doBeforeWriteCacheAction(tasks.getByName(REMAP_SOURCE_TASK), new Action<Task>() {
			@SneakyThrows
			@Override
			public void execute(Task task) {
				File f = task.getOutputs().getFiles().iterator().next();

				SourceProcessor.process(ModPatcherPlugin.this, f);
			}
		});
	}

	@Data
	public static class ModPatcherGradleExtension {
		public String mixinPackage = "";
		public boolean noMixinIsError = false;
		public boolean extractGeneratedSources = false;
		public boolean generateInheritanceHierarchy = false;

		public String getMixinPackageToUse() {
			return Objects.equals(mixinPackage, "all") ? null : mixinPackage;
		}

		public boolean shouldMixin() {
			return !"".equals(mixinPackage);
		}
	}
}
