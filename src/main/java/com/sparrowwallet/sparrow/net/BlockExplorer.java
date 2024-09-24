package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.sparrow.io.Server;

public enum BlockExplorer {
    EXPLORER_FUJICOIN_ORG("https://explorer.fujicoin.org"),
    EXPLORER2_FUJICOIN_ORG("https://explorer2.fujicoin.org"),
    NONE("http://none");

    private final Server server;

    BlockExplorer(String url) {
        this.server = new Server(url);
    }

    public Server getServer() {
        return server;
    }
}
