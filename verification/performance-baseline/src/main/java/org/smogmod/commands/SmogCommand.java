package org.smogmod.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

import org.smogmod.compat.gt5u.SmogGt5uBridge;
import org.smogmod.world.WorldRuntime;
import org.smogmod.world.Worlds;

import smog.BlockPos;

public final class SmogCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "smog";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/smog status|sample x y z|pulse x y z amount|remove x y z amount|source x y z rate|invalidate x1 y1 z1 x2 y2 z2|save";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        WorldRuntime runtime = Worlds.get(sender.getEntityWorld());
        if (args.length == 1 && "status".equals(args[0])) {
            sender.addChatMessage(
                new ChatComponentText(
                    "SMOG dim=" + runtime.state.dimension
                        + " live="
                        + runtime.state.liveMass()
                        + " parked="
                        + runtime.state.parkedMass()
                        + " pending="
                        + runtime.pending.size()
                        + " accepted="
                        + runtime.acceptedPulses
                        + " balance="
                        + runtime.state.balanceError()));
            return;
        }
        if (args.length == 1 && "save".equals(args[0])) {
            runtime.save();
            sender.addChatMessage(new ChatComponentText("SMOG saved"));
            return;
        }
        if (args.length >= 4) {
            BlockPos point = point(sender, args, 1);
            if (args.length == 4 && "sample".equals(args[0])) {
                sender.addChatMessage(new ChatComponentText("SMOG concentration=" + runtime.state.sample(point)));
                return;
            }
            if (args.length == 5) {
                double amount = parseDouble(sender, args[4]);
                if (!Double.isFinite(amount) || amount < 0) {
                    throw new WrongUsageException("Finite nonnegative amount required");
                }
                if ("source".equals(args[0])) {
                    runtime.state.setSourceRate(point, amount);
                    return;
                }
                if (amount > Long.MAX_VALUE) {
                    throw new WrongUsageException("Amount too large");
                }
                if ("pulse".equals(args[0])) {
                    SmogGt5uBridge.emitPulse(runtime.world, point.x, point.y, point.z, (long) amount);
                    return;
                }
                if ("remove".equals(args[0])) {
                    SmogGt5uBridge.removeAt(runtime.world, point.x, point.y, point.z, (long) amount);
                    return;
                }
            }
            if (args.length == 7 && "invalidate".equals(args[0])) {
                runtime.invalidate(point, point(sender, args, 4));
                return;
            }
        }
        throw new WrongUsageException(getCommandUsage(sender));
    }

    private BlockPos point(ICommandSender sender, String[] args, int offset) {
        return new BlockPos(
            sender.getEntityWorld().provider.dimensionId,
            parseInt(sender, args[offset]),
            parseInt(sender, args[offset + 1]),
            parseInt(sender, args[offset + 2]));
    }
}
