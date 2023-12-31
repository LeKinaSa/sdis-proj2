package protocol;

import chord.ChordNode;
import chord.ChordNodeInfo;
import chord.ChordTask;
import chord.FindSuccessorsThread;
import jsse.ClientThread;
import messages.*;
import utils.Utils;
import workers.RestoreChunkThread;
import workers.StoreChunkThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class HandleReceivedMessageThread extends Thread {
    private final byte[] messageBytes;

    public HandleReceivedMessageThread(byte[] messageBytes) {
        this.messageBytes = messageBytes;
    }

    @Override
    public void run() {
        List<byte[]> headerAndBody = Utils.splitMessage(messageBytes);

        if (headerAndBody.size() == 2) {
            String header = new String(headerAndBody.get(0));
            byte[] body = headerAndBody.get(1);

            String[] headerComponents = header.split(" ");
            if (headerComponents.length >= 2) {
                switch (headerComponents[1]) {
                    case "FIND_SUCCESSOR": {
                        FindSuccessorMessage message = FindSuccessorMessage.parse(header, body);
                        if (message != null) handleFindSuccessorMessage(message);
                        break;
                    }
                    case "SUCCESSOR": {
                        SuccessorMessage message = SuccessorMessage.parse(header, body);
                        if (message != null) handleSuccessorMessage(message);
                        break;
                    }
                    case "GET_PREDECESSOR": {
                        GetPredecessorMessage message = GetPredecessorMessage.parse(header, body);
                        if (message != null) handleGetPredecessorMessage(message);
                        break;
                    }
                    case "PREDECESSOR": {
                        PredecessorMessage message = PredecessorMessage.parse(header, body);
                        if (message != null) handlePredecessorMessage(message);
                        break;
                    }
                    case "NOTIFY": {
                        NotifyMessage message = NotifyMessage.parse(header, body);
                        if (message != null) handleNotifyMessage(message);
                        break;
                    }
                    case "GET_SUCCESSOR": {
                        GetSuccessorMessage message = GetSuccessorMessage.parse(header);
                        if (message != null) handleGetSuccessorMessage(message);
                        break;
                    }
                    case "NODE_SUCCESSOR": {
                        NodeSuccessorMessage message = NodeSuccessorMessage.parse(header);
                        if (message != null) handleNodeSuccessorMessage(message);
                        break;
                    }
                    case "PUT_CHUNK": {
                        PutChunkMessage message = PutChunkMessage.parse(header, body);
                        if (message != null) handlePutChunkMessage(message);
                        break;
                    }
                    case "STORED": {
                        StoredMessage message = StoredMessage.parse(header);
                        if (message != null) handleStoredMessage(message);
                        break;
                    }
                    case "DELETE": {
                        DeleteMessage message = DeleteMessage.parse(header);
                        if (message != null) handleDeleteMessage(message);
                        break;
                    }
                    case "GET_CHUNK": {
                        GetChunkMessage message = GetChunkMessage.parse(header);
                        if (message != null) handleGetChunkMessage(message);
                        break;
                    }
                    case "CHUNK": {
                        ChunkMessage message = ChunkMessage.parse(header, body);
                        if (message != null) handleChunkMessage(message);
                        break;
                    }
                    case "REMOVED": {
                        RemovedMessage message = RemovedMessage.parse(header);
                        if (message != null) handleRemovedMessage(message);
                        break;
                    }
                    case "START_PUT_CHUNK": {
                        StartPutChunkMessage message = StartPutChunkMessage.parse(header);
                        if (message != null) handleStartPutChunkMessage(message);
                        break;
                    }
                    default:
                        break;
                }
            }
        }
    }

    private void handleFindSuccessorMessage(FindSuccessorMessage message) {
        ChordNode chordNode = Peer.state.chordNode;

        long start = chordNode.selfInfo.id;
        long end = chordNode.getSuccessorInfo().id;

        if (ChordNode.isKeyBetween(message.key, start, end, false, true)) {
            try {
                ClientThread thread = new ClientThread(message.initiatorAddress,
                        new SuccessorMessage(Peer.version, Peer.id, message.key, chordNode.getSuccessorInfo()));
                Peer.executor.execute(thread);
            }
            catch (IOException | GeneralSecurityException ex) {
                System.err.println("Exception occurred when handling FIND_SUCCESSOR message: " + ex.getMessage());
            }
            return;
        }

        ChordNodeInfo closestPrecedingNode = chordNode.getClosestPrecedingNode(message.key);
        message.senderId = Peer.id;

        try {
            ClientThread thread = new ClientThread(closestPrecedingNode.address, message);
            Peer.executor.execute(thread);
        }
        catch (IOException | GeneralSecurityException ex) {
            System.err.println("Exception occurred when handling FIND_SUCCESSOR message: " + ex.getMessage());
        }
    }

    public void handleSuccessorMessage(SuccessorMessage message) {
        ChordNode chordNode = Peer.state.chordNode;

        // Since we are working with modular arithmetic, we need to take precautions when calculating keyDifference
        long keyDifference = message.key - chordNode.selfInfo.id;
        if (keyDifference < 0) keyDifference += ChordNode.maxNodes;

        if ((keyDifference & -keyDifference) == keyDifference) {
            // The key difference is a power of two, update the finger table
            int index = (int) Math.round(Math.log(keyDifference) / Math.log(2));
            chordNode.fingerTable.set(index, message.nodeInfo);
        }

        if (chordNode.tasksMap.containsKey(message.key)) {
            Queue<ChordTask> taskQueue = chordNode.tasksMap.get(message.key);

            while (!taskQueue.isEmpty()) {
                taskQueue.remove().execute(message.nodeInfo);
            }
        }
    }

    private void handleGetPredecessorMessage(GetPredecessorMessage message) {
        ChordNode chordNode = Peer.state.chordNode;

        PredecessorMessage predecessorMessage = new PredecessorMessage(Peer.version, Peer.id, chordNode.predecessorInfo);

        try {
            ClientThread thread = new ClientThread(message.nodeInfo.address, predecessorMessage);
            Peer.executor.execute(thread);
        }
        catch (IOException | GeneralSecurityException ex) {
            System.err.println("Exception occurred when handling GET_PREDECESSOR message: " + ex.getMessage());
        }
    }

    private void handlePredecessorMessage(PredecessorMessage message) {
        Peer.state.chordNode.stabilize(message.predecessorInfo);
    }

    private void handleNotifyMessage(NotifyMessage message) {
        ChordNode chordNode = Peer.state.chordNode;

        if (chordNode.predecessorInfo == null
                || ChordNode.isKeyBetween(message.nodeInfo.id, chordNode.predecessorInfo.id, chordNode.selfInfo.id)) {
            chordNode.predecessorInfo = message.nodeInfo;
            System.out.println("Your predecessor is " + chordNode.predecessorInfo);
        }
    }

    private void handleGetSuccessorMessage(GetSuccessorMessage message) {
        NodeSuccessorMessage nodeSuccessorMessage = new NodeSuccessorMessage(Peer.version, Peer.id, Peer.state.chordNode.getSuccessorInfo());

        try {
            ClientThread thread = new ClientThread(message.initiatorAddress, nodeSuccessorMessage);
            Peer.executor.execute(thread);
        }
        catch (IOException | GeneralSecurityException ex) {
            System.err.println("Exception occurred when handling GET_SUCCESSOR message: " + ex.getMessage());
        }
    }

    private void handleNodeSuccessorMessage(NodeSuccessorMessage message) {
        ChordNode chordNode = Peer.state.chordNode;

        if (message.successorInfo.equals(chordNode.selfInfo)) {
            // Traveled around the chord ring, stop the find successors procedure
            FindSuccessorsThread.procedureFinished = true;
            return;
        }

        if (message.successorInfo.equals(chordNode.getSuccessorInfo())) {
            // Finger table entries might not have been updated yet, stop procedure and try again later
            FindSuccessorsThread.procedureFinished = true;
            return;
        }

        chordNode.successorDeque.add(message.successorInfo);
        System.out.println("Deque: " + chordNode.successorDeque);

        if (chordNode.successorDeque.size() < ChordNode.numSuccessors) {
            // Deque is still not full, continue asking for successors
            GetSuccessorMessage getSuccessorMessage = new GetSuccessorMessage(Peer.version, Peer.id, Peer.address);
            try {
                ClientThread thread = new ClientThread(message.successorInfo.address, getSuccessorMessage);
                Peer.executor.execute(thread);
            }
            catch (IOException | GeneralSecurityException ex) {
                System.err.println("Exception occurred when handling NODE_SUCCESSOR message: " + ex.getMessage());
            }
        }
        else {
            FindSuccessorsThread.procedureFinished = true;
        }
    }

    private void handlePutChunkMessage(PutChunkMessage message) {
        // The chunk cannot be stored by the initiator peer
        if (!message.initiatorAddress.equals(Peer.address)) {
            // Store the chunk and send a STORED message to initiator peer
            StoreChunkThread thread = new StoreChunkThread(message);
            Peer.executor.execute(thread);
            return;
        }

        // Message reached initiator peer, don't store chunk and forward it to successor if possible
        message.forwardToSuccessor(false);
    }

    private void handleStoredMessage(StoredMessage message) {
        ChunkIdentifier identifier = new ChunkIdentifier(message.fileId, message.chunkNumber);
        Peer.state.chunkReplicationDegreeMap.get(identifier).add(message.senderAddress);
    }

    private void handleDeleteMessage(DeleteMessage message) {
        // Get folder corresponding to file id
        String fileId = message.fileId;
        File folder = new File("peer" + Peer.id + File.separator + fileId);

        if (folder.exists() && folder.isDirectory()) {
            File[] chunkFiles = folder.listFiles();
            if (chunkFiles != null) {
                for (File chunkFile : chunkFiles) {
                    try {
                        int chunkNumber = Integer.parseInt(chunkFile.getName());
                        ChunkIdentifier identifier = new ChunkIdentifier(fileId, chunkNumber);
                        Peer.state.storedChunksMap.remove(identifier);
                        chunkFile.delete();
                    }
                    catch (NumberFormatException ex) {
                        System.err.println("Chunk file has invalid name: " + ex.getMessage());
                    }
                }
            }
            folder.delete();
        }
    }

    private void handleGetChunkMessage(GetChunkMessage message) {
        ChunkIdentifier identifier = new ChunkIdentifier(message.fileId, message.chunkNumber);

        if (Peer.state.storedChunksMap.containsKey(identifier)) {
            try {
                String path = "peer" + Peer.id + File.separator + message.fileId + File.separator + message.chunkNumber;
                File chunkFile = new File(path);

                byte[] chunkData = new byte[Peer.CHUNK_MAX_SIZE];
                int chunkSize = 0;

                boolean readSuccessfully = true;
                if (chunkFile.length() > 0) {
                    FileInputStream stream = new FileInputStream(chunkFile);

                    if ((chunkSize = stream.read(chunkData)) <= 0) {
                        System.err.println("Error when reading from chunk file " + message.chunkNumber + " of file " +
                                message.fileId);
                        readSuccessfully = false;
                    }

                    stream.close();
                }

                if (readSuccessfully) {
                    byte[] body = new byte[chunkSize];
                    System.arraycopy(chunkData, 0, body, 0, chunkSize);

                    ChunkMessage chunkMessage = new ChunkMessage(Peer.version, Peer.id, message.fileId, message.chunkNumber,
                            body);
                    ClientThread thread = new ClientThread(message.initiatorAddress, chunkMessage);
                    Peer.executor.execute(thread);

                    return;
                }
            }
            catch (IOException | GeneralSecurityException ex) {
                System.err.println("Error when attempting to send CHUNK message: " + ex.getMessage());
            }
        }

        // Peer hasn't stored the chunk or failed to send the CHUNK message, forward request to successor
        message.forwardToSuccessor();
    }

    private void handleChunkMessage(ChunkMessage message) {
        RestoreChunkThread thread = new RestoreChunkThread(message);
        Peer.executor.execute(thread);
    }

    private void handleRemovedMessage(RemovedMessage message) {
        ChunkIdentifier identifier = new ChunkIdentifier(message.fileId, message.chunkNumber);
        Peer.state.chunkReplicationDegreeMap.get(identifier).remove(message.senderAddress);
    }

    private void handleStartPutChunkMessage(StartPutChunkMessage message) {
        ChordNode chordNode = Peer.state.chordNode;
        ChunkIdentifier identifier = new ChunkIdentifier(message.fileId, message.chunkNumber);

        try {
            if (Peer.state.storedChunksMap.containsKey(identifier)) {
                String path = "peer" + Peer.id + File.separator + message.fileId + File.separator + message.chunkNumber;
                File chunkFile = new File(path);

                byte[] chunkData = new byte[Peer.CHUNK_MAX_SIZE];
                int chunkSize = 0;

                boolean readSuccessfully = true;
                if (chunkFile.length() > 0) {
                    FileInputStream stream = new FileInputStream(chunkFile);

                    if ((chunkSize = stream.read(chunkData)) <= 0) {
                        System.err.println("Error when reading from chunk file " + message.chunkNumber + " of file " +
                                message.fileId);
                        stream.close();
                        readSuccessfully = false;
                    }
                }

                if (readSuccessfully) {
                    byte[] body = new byte[chunkSize];
                    System.arraycopy(chunkData, 0, body, 0, chunkSize);

                    PutChunkMessage putChunkMessage = new PutChunkMessage(Peer.version, Peer.id, message.fileId,
                            message.chunkNumber, message.replicationDegree, message.initiatorAddress, body);

                    long key = ChordNode.generateKey((message.fileId + "_" + message.chunkNumber).getBytes());

                    FindSuccessorMessage findSuccessorMessage = new FindSuccessorMessage(Peer.version, Peer.id, key, Peer.address);
                    ChordNodeInfo closestPrecedingNode = chordNode.getClosestPrecedingNode(key);

                    chordNode.tasksMap.putIfAbsent(key, new ConcurrentLinkedQueue<>());
                    chordNode.tasksMap.get(key).add(new ChordTask() {
                        @Override
                        public void performTask(ChordNodeInfo successorInfo) {
                            try {
                                ClientThread putChunkThread = new ClientThread(successorInfo.address, putChunkMessage);
                                Peer.executor.execute(putChunkThread);
                            }
                            catch (Exception ex) {
                                System.err.println("Exception when attempting to sent PUT_CHUNK message: " + ex.getMessage());
                            }
                        }
                    });

                    ClientThread thread = new ClientThread(closestPrecedingNode.address, findSuccessorMessage);
                    Peer.executor.execute(thread);
                }
            }
        }
        catch (Exception ex) {
            System.err.println("Error when handling START_PUT_CHUNK message: " + ex.getMessage());
        }
    }
}
