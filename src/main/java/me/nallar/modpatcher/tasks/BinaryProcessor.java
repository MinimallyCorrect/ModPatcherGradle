package me.nallar.modpatcher.tasks;

import com.google.common.io.ByteStreams;
import lombok.SneakyThrows;
import lombok.val;
import lzma.sdk.lzma.Encoder;
import lzma.streams.LzmaOutputStream;
import me.nallar.ModPatcherPlugin;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.omg.CORBA.StringHolder;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

public class BinaryProcessor {
	private static final HashMap<String, String> classExtends = new HashMap<>();

	public static void process(ModPatcherPlugin plugin, File deobfJar) {
		if (!deobfJar.exists()) {
			ModPatcherPlugin.logger.warn("Could not find minecraft code to process. Expected to find it at " + deobfJar);
			return;
		}

		plugin.mixinTransform(deobfJar.toPath());

		if (plugin.extension.generateInheritanceHierarchy)
			generateMappings(deobfJar);

		if (plugin.extension.generateStubMinecraftClasses)
			generateStubMinecraftClasses(deobfJar);
	}

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

	@SneakyThrows
	private static File getGeneratedDirectory() {
		File generatedDirectory = new File("./generated/");
		generatedDirectory = generatedDirectory.getCanonicalFile();

		if (!generatedDirectory.exists()) {
			//noinspection ResultOfMethodCallIgnored
			generatedDirectory.mkdir();
		}
		return generatedDirectory;
	}

	private static Encoder getEncoder() {
		val encoder = new Encoder();
		encoder.setDictionarySize(1 << 23);
		encoder.setEndMarkerMode(true);
		encoder.setMatchFinder(Encoder.EMatchFinderTypeBT4);
		encoder.setNumFastBytes(0x20);
		return encoder;
	}

	@SneakyThrows
	private static void generateMappings(File jar) {
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
		try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(new LzmaOutputStream(new FileOutputStream(new File(getGeneratedDirectory(), "extendsMap.obj.lzma")), getEncoder()))) {
			objectOutputStream.writeObject(classExtends);
		}
	}

	@SneakyThrows
	private static void generateStubMinecraftClasses(File jar) {
		try (val os = new JarOutputStream(new LzmaOutputStream(new FileOutputStream(new File(getGeneratedDirectory(), "minecraft_stubs.jar.lzma")), getEncoder()))) {
			os.setLevel(Deflater.NO_COMPRESSION);
			JarInputStream istream = new JarInputStream(new FileInputStream(jar));
			JarEntry entry;
			while ((entry = istream.getNextJarEntry()) != null) {
				byte[] classBytes = ByteStreams.toByteArray(istream);
				if (entry.getName().endsWith(".class")
					&& entry.getName().startsWith("net/minecraft")
					&& !entry.getName().startsWith("net/minecraft/client")) {
					val reader = new ClassReader(classBytes);
					val node = new ClassNode();
					reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
					val writer = new ClassWriter(0);
					node.accept(writer);
					val stub = writer.toByteArray();
					val jarEntry = new JarEntry(entry.getName());
					os.putNextEntry(jarEntry);
					os.write(stub);
					os.closeEntry();
				}
				istream.closeEntry();
			}
			istream.close();
		}
	}
}
