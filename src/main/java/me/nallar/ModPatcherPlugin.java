package me.nallar;

import lombok.Data;
import lombok.Getter;
import lombok.val;
import me.nallar.javatransformer.api.JavaTransformer;
import me.nallar.mixin.internal.MixinApplicator;
import me.nallar.modpatcher.tasks.TaskProcessBinary;
import me.nallar.modpatcher.tasks.TaskProcessSource;
import org.apache.log4j.Logger;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSet;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ModPatcherPlugin implements Plugin<Project> {
	public static Logger logger = Logger.getLogger("ModPatcher");
	public static String BINARY_PROCESSING_TASK = "processMcBin";
	public static String SRC_PROCESSING_TASK = "processMcSrc";

	public ModPatcherGradleExtension extension = new ModPatcherGradleExtension();
	private Project project;
	@Getter(lazy = true)
	private final JavaTransformer mixinTransformer = makeMixinTransformer();

	public static ModPatcherPlugin get(Project project) {
		return (ModPatcherPlugin) project.getPlugins().findPlugin("me.nallar.ModPatcherGradle");
	}

	@Override
	public void apply(Project project) {
		this.project = project;
		project.getPlugins().apply("net.minecraftforge.gradle.forge");

		project.getExtensions().add("modpatcher", extension);

		val tasks = project.getTasks();
		tasks.create(BINARY_PROCESSING_TASK, TaskProcessBinary.class);
		tasks.create(SRC_PROCESSING_TASK, TaskProcessSource.class);

		project.afterEvaluate(this::afterEvaluate);
	}

	public JavaTransformer makeMixinTransformer() {
		val applicator = new MixinApplicator();
		applicator.setNoMixinIsError(extension.noMixinIsError);
		return applicator.getMixinTransformer(getSourceSet().getJava().getSrcDirs().iterator().next().toPath(), extension.getMixinPackageToUse());
	}

	public void mixinTransform(Path toTransform) {
		if (extension.shouldMixin()) {
			Path old = toTransform.resolveSibling("bak_" + toTransform.getFileName().toString());
			try {
				Files.deleteIfExists(old);
				Files.move(toTransform, old);
				getMixinTransformer().transform(old, toTransform);
				Files.delete(old);
			} catch (IOException e) {
				throw new IOError(e);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private SourceSet getSourceSet() {
		for (SourceSet s : (Iterable<SourceSet>) project.getProperties().get("sourceSets")) {
			if (s.getName().equals("main")) {
				return s;
			}
		}

		throw new RuntimeException("Can't find main sourceSet");
	}

	public void afterEvaluate(Project project) {
		val tasks = project.getTasks();

		// generateInheritanceHierarchy required for setupCiWorkspace. Runs after deobfMcMCP
		tasks.getByName(BINARY_PROCESSING_TASK).mustRunAfter("deobfMcMCP");
		tasks.getByName("compileJava").dependsOn(BINARY_PROCESSING_TASK);
		tasks.getByName("setupCiWorkspace").dependsOn(BINARY_PROCESSING_TASK);

		tasks.getByName(SRC_PROCESSING_TASK).mustRunAfter("remapMcSources");
		tasks.getByName("recompileMc").dependsOn(SRC_PROCESSING_TASK);
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
