package me.nallar.modpatcher.tasks;

import com.google.common.io.ByteStreams;
import lombok.val;
import me.nallar.ModPatcherPlugin;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.jar.*;

public class TaskProcessSource extends DefaultTask {
	@SuppressWarnings("ResultOfMethodCallIgnored")
	private static void extractGeneratedSources(File jar) throws Exception {
		File generatedDirectory = new File("./generated/");
		generatedDirectory = generatedDirectory.getCanonicalFile();
		final File generatedSrcDirectory = new File(generatedDirectory, "src");

		if (generatedSrcDirectory.exists()) {
			deleteDirectory(generatedSrcDirectory.toPath());
		}
		generatedSrcDirectory.mkdirs();

		final File mainSrcDirectory = new File("./src/main/java/");
		jar = jar.getCanonicalFile();

		JarInputStream istream = new JarInputStream(new FileInputStream(jar));
		JarEntry entry;
		while ((entry = istream.getNextJarEntry()) != null) {
			String part = entry.getName();
			byte[] sourceBytes = ByteStreams.toByteArray(istream);
			if (part.endsWith(".java")) {
				// Source file
				if (new File(mainSrcDirectory, part).exists()) {
					continue;
				}
				File dest = new File(generatedSrcDirectory, part);
				dest.getParentFile().mkdirs();
				Files.write(dest.toPath(), sourceBytes);
			}

			istream.closeEntry();
		}
		istream.close();
	}

	private static void deleteDirectory(Path path) throws IOException {
		java.nio.file.Files.walkFileTree(path, new PathSimpleFileVisitor());
	}

	@TaskAction
	public void run() throws Exception {
		val plugin = ModPatcherPlugin.get(getProject());

		File f = getProject().getTasksByName("remapMcSources", false).iterator().next().getOutputs().getFiles().iterator().next();
		if (!f.exists()) {
			ModPatcherPlugin.logger.warn("Could not find minecraft source to process. Expected to find it at " + f);
			return;
		}

		if (plugin.extension.extractGeneratedSources)
			extractGeneratedSources(f);

		plugin.mixinTransform(f.toPath());
	}

	private static class PathSimpleFileVisitor extends SimpleFileVisitor<Path> {
		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
			throws IOException {
			Files.delete(file);
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
			// try to delete the file anyway, even if its attributes
			// could not be read, since delete-only access is
			// theoretically possible
			Files.delete(file);
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
			if (exc == null) {
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			} else {
				// directory iteration failed; propagate exception
				throw exc;
			}
		}
	}
}
