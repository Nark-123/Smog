package smog;

import java.nio.file.Paths;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Inspect instructions actually executed, allowing unused old UTF8 constants in remapped class files. */
public final class RuntimeJarContracts {
    public static void main(String[] args) throws Exception {
        boolean found = false;
        try (ZipFile jar = new ZipFile(Paths.get(args[0]).toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(jar.getInputStream(jar.getEntry("org/smogmod/world/WorldRuntime.class"))).accept(node, 0);
            for (Object value : node.methods) {
                MethodNode method = (MethodNode) value;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (call.owner.startsWith("net/minecraft/") && call.name.equals("getTotalWorldTime")) {
                            throw new AssertionError("Development Minecraft method in runtime JAR");
                        }
                        if (call.owner.startsWith("net/minecraft/") && call.name.equals("func_82737_E")) {
                            found = true;
                        }
                    }
                }
            }
        }
        if (!found) {
            throw new AssertionError("Expected reobfuscated world clock invocation");
        }
        System.out.println("RUNTIME_REOBF_INSTRUCTIONS_PASS");
    }
}
