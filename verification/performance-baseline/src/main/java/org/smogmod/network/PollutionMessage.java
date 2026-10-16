package org.smogmod.network;

import org.smogmod.SmogMod;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class PollutionMessage implements IMessage {

    public int dimension, x, z, amount;

    public boolean local;

    public PollutionMessage() {}

    public PollutionMessage(int dimension, int x, int z, int amount, boolean local) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
        this.amount = amount;
        this.local = local;
    }

    @Override
    public void fromBytes(ByteBuf b) {
        dimension = b.readInt();
        x = b.readInt();
        z = b.readInt();
        amount = b.readInt();
        local = b.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf b) {
        b.writeInt(dimension);
        b.writeInt(x);
        b.writeInt(z);
        b.writeInt(amount);
        b.writeBoolean(local);
    }

    public static final class Handler implements IMessageHandler<PollutionMessage, IMessage> {

        @Override
        public IMessage onMessage(PollutionMessage m, MessageContext c) {
            SmogMod.proxy.receive(m);
            return null;
        }
    }
}
