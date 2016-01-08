package me.nallar.modpatcher.tasks;

import com.google.common.io.ByteStreams;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.*;
import java.util.*;
import java.util.jar.*;

public class TaskProcessBinary extends DefaultTask {
	private static final HashMap<String, String> classExtends = new HashMap<String, String>();

	@TaskAction
	public void run() throws Exception {
		File f = getProject().getTasksByName("deobfMcMCP", false).iterator().next().getOutputs().getFiles().iterator().next();
		generateMappings(f);
	}

	private static void addClassToExtendsMap(byte[] inputCode) {
		ClassReader classReader = new ClassReader(inputCode);
		ClassNode classNode = new ClassNode();
		classReader.accept(classNode, 0);
		String superName = classNode.superName.replace("/", ".");
		if (superName != null && !superName.equals("java.lang.Object")) {
			classExtends.put(classNode.name.replace("/", "."), superName);
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
			generatedDirectory.mkdir();
		}
		ObjectOutputStream objectOutputStream = new ObjectOutputStream(new FileOutputStream(new File(generatedDirectory, "extendsMap.obj")));
		try {
			objectOutputStream.writeObject(classExtends);
		} finally {
			objectOutputStream.close();
		}

	}
}
