package me.nallar.modpatcher;

import me.nallar.modpatcher.tasks.TaskGenerateInheritanceHierarchy;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class ModPatcherPlugin implements Plugin<Project> {
	@Override
	public void apply(Project project) {
		project.getPlugins().apply("net.minecraftforge.gradle.forge");

		project.getTasks().create("generateInheritanceHierarchy", TaskGenerateInheritanceHierarchy.class);
	}
}
