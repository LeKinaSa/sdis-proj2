package chord;

import jsse.ClientThread;
import messages.FindSuccessorMessage;
import protocol.Peer;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class FixFingersThread extends Thread {
    public static int nextFinger = 0;

    @Override
    public void run() {
        ChordNode chordNode = Peer.state.chordNode;
        long startKey = (chordNode.selfInfo.id + (long) Math.pow(2, nextFinger)) % ChordNode.maxNodes;

        FindSuccessorMessage message = new FindSuccessorMessage(Peer.version, Peer.id, startKey,
                chordNode.selfInfo.address);

        try {
            ClientThread thread = new ClientThread(chordNode.selfInfo.address, message);
            Peer.executor.execute(thread);
            nextFinger = (nextFinger + 1) % ChordNode.keyBits;
        }
        catch (IOException | GeneralSecurityException ex) {
            System.out.println("Exception when trying to fix finger table: " + ex.getMessage());
        }
    }
}
