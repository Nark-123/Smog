package org.smogmod;

import org.smogmod.network.PollutionMessage;

public class CommonProxy {

    public void init() {}

    public void receive(PollutionMessage message) {}

    public int readClient(int dimension, int x, int z) {
        return 0;
    }

    public int localClient() {
        return 0;
    }
}
