package smog;

import java.io.InputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Inspect the exact compiled dependency, without initializing GT or Minecraft classes.
 */
public final class TargetContracts {

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static ClassNode read(String owner) throws Exception {
        try (InputStream in = TargetContracts.class.getClassLoader()
            .getResourceAsStream(owner + ".class")) {
            check(in != null, owner + " missing");
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    public static void main(String[] args) throws Exception {
        for (String owner : new String[] { "gregtech/api/metatileentity/implementations/MTEHatchMuffler",
            "gtPlusPlus/xmod/gregtech/api/metatileentity/implementations/MTEHatchMufflerAdvanced" }) {
            boolean found = false;
            for (Object value : read(owner).methods) {
                MethodNode method = (MethodNode) value;
                if (!method.name.equals("polluteEnvironment")
                    || !method.desc.equals("(Lgregtech/api/metatileentity/MetaTileEntity;I)Z")) {
                    continue;
                }
                found = true;
                int sinks = 0;
                boolean filter = false;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode)) {
                        continue;
                    }
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.name.equals("calculatePollutionReduction")) {
                        filter = true;
                    }
                    if (call.owner.equals("gregtech/common/pollution/Pollution") && call.name.equals("addPollution")) {
                        check(filter, "Terminal pollution call precedes filter");
                        check(
                            call.desc.equals("(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;I)V"),
                            "Terminal descriptor changed");
                        sinks++;
                    }
                }
                check(sinks == 1, "Exactly one terminal call required");
            }
            check(found, "Muffler method missing");
        }
        System.out.println("SMOG_GT5U_TARGET_CONTRACT_PASS");
    }
}
