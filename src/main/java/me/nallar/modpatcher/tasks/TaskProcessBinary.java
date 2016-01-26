package me.nallar.modpatcher.tasks;

import com.google.common.io.ByteStreams;
import lombok.val;
import me.nallar.ModPatcherPlugin;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.omg.CORBA.StringHolder;

import java.io.*;
import java.util.*;
import java.util.jar.*;

public class TaskProcessBinary extends DefaultTask {
	private static final HashMap<String, String> classExtends = new HashMap<>();

	private static void addClassToExtendsMap(byte[] inputCode) {
		ClassReader classReader = new ClassReader(inputCode);

		StringHolder nameHolder = new StringHolder();
		StringHolder superNameHolder = new StringHolder();
		try {
			classReader.accept(new ClassVisitor(Opcodes.ASM5) {
				@Override
				public void visit(int i, int i1, String name, String s1, String superName, String[] strings) {
					nameHolder.value = name;
					superNameHolder.value = superName;
					throw new RuntimeException("visiting aborted");
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (RuntimeException e) {
			if (!e.getMessage().equals("visiting aborted")) {
				throw e;
			}
		}

		String superName = superNameHolder.value == null ? null : superNameHolder.value.replace("/", ".");
		if (superName != null && !superName.equals("java.lang.Object")) {
			classExtends.put(nameHolder.value.replace("/", "."), superName);
		}
	}

	private static void generateMappings(File jar) throws Exception {
		JarInputStream istream = new JarInputStream(new FileInputStream(jar));
		JarEntry entry;
		while ((entry = istream.getNextJarEntry()) != null) {
			byte[] classBytes = ByteStreams.toByteArray(istream);
			if (entry.getName().endsWith(".class")) {
				addClassToExtendsMap(classBytes);
			}

			istream.closeEntry();
		}
		istream.close();

		File generatedDirectory = new File("./generated/");
		generatedDirectory = generatedDirectory.getCanonicalFile();

		if (!generatedDirectory.exists()) {
			//noinspection ResultOfMethodCallIgnored
			generatedDirectory.mkdir();
		}
		try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(new FileOutputStream(new File(generatedDirectory, "extendsMap.obj")))) {
			objectOutputStream.writeObject(classExtends);
		}

	}

	@TaskAction
	public void run() throws Exception {
		val plugin = ModPatcherPlugin.get(getProject());

		File f = getProject().getTasksByName("deobfMcMCP", false).iterator().next().getOutputs().getFiles().iterator().next();

		if (!f.exists()) {
			ModPatcherPlugin.logger.warn("Could not find minecraft code to process. Expected to find it at " + f);
			return;
		}

		if (plugin.extension.generateInheritanceHierarchy)
			generateMappings(f);

		plugin.mixinTransform(f.toPath());
	}
}
