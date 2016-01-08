package me.nallar;

import lombok.Data;
import lombok.val;
import me.nallar.modpatcher.tasks.TaskProcessSource;
import me.nallar.modpatcher.tasks.TaskProcessBinary;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class ModPatcherPlugin implements Plugin<Project> {
	public static ModPatcherGradleExtension extension = new ModPatcherGradleExtension();
	public static String BINARY_PROCESSING_TASK = "processMcBin";
	public static String SRC_PROCESSING_TASK = "processMcSrc";

	@Override
	public void apply(Project project) {
		project.getPlugins().apply("net.minecraftforge.gradle.forge");

		project.getExtensions().add("modpatcher", extension);

		val tasks = project.getTasks();
		tasks.create(BINARY_PROCESSING_TASK, TaskProcessBinary.class);
		tasks.create(SRC_PROCESSING_TASK, TaskProcessSource.class);

		project.afterEvaluate(this::afterEvaluate);
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
		public boolean extractGeneratedSources = false;
		public boolean generateInheritanceHierarchy = false;
	}
}
