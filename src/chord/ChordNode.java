package chord;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class ChordNode {
    // With an m-bit key, there can be 2^m nodes, and each has m entries in its finger table
    public static final int keyBits = 16;

    public ChordNodeInfo selfInfo, predecessorInfo;
    // AtomicReferenceArray is used to ensure thread safety
    public AtomicReferenceArray<ChordNodeInfo> fingerTable = new AtomicReferenceArray<>(keyBits);

    public ChordNode(InetSocketAddress address) {
        try {
            String input = address.getHostName() + ":" + address.getPort();
            long key = generateKey(input.getBytes());

            selfInfo = new ChordNodeInfo(key, address);
        }
        catch (NoSuchAlgorithmException ex) {
            System.out.println("Algorithm does not exist: " + ex.getMessage());
        }
    }

    /**
     * Generates an m-bit key to be used for the Chord protocol from a sequence of bytes. The key is
     * generated using a consistent hashing algorithm, in this case SHA-1. The 160-bit SHA-1 has is truncated
     * to a length of m bits, using the least significant bits.
     */
    public static long generateKey(byte[] input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA1");
        byte[] sha1Bytes = digest.digest(input);

        long key = 0;
        // The length of the key in bytes, rounded up (for example, a 20-bit key would need 3 bytes)
        int keyNumBytes = (int) Math.ceil((double) keyBits / 8);

        // Obtain the m least significant bits from the 160-bit SHA-1 hash
        // This is equivalent to obtaining the 160-bit hash modulo 2^m
        for (int i = 0; i < keyNumBytes; ++i) {
            byte b = sha1Bytes[sha1Bytes.length - 1 - i];
            key |= Byte.toUnsignedInt(b) << (8 * i);
        }

        // This bitmask is needed whenever m is not a multiple of 8,
        // since we need to guarantee that the generated keys are always smaller than 2^m
        long mask = (long) Math.pow(2, keyBits) - 1;
        key &= mask;

        return key;
    }

    public void startFingerTable() {
        // Called when the node is creating a new Chord network
        for (int i = 0; i < fingerTable.length(); ++i) {
            fingerTable.set(i, selfInfo);
        }
    }

    /**
     * Returns information from the node in the finger table that corresponds to the specified key.
     */
    public ChordNodeInfo getFinger(long key) {
        long maxNodes = (long) Math.pow(2, keyBits);

        for (int i = 0; i < keyBits; ++i) {
            long start = (selfInfo.id + (long) Math.pow(2, i)) % maxNodes;
            long end = (start + (long) Math.pow(2, i) - 1) % maxNodes;

            if (start <= end) {
                if (key >= start && key <= end) {
                    return fingerTable.get(i);
                }
            }
            else {
                if (key >= start || key <= end) {
                    return fingerTable.get(i);
                }
            }
        }

        return null;
    }

    public ChordNodeInfo getSuccessorInfo() {
        return fingerTable.get(0);
    }

    public void setSuccessorInfo(ChordNodeInfo info) {
        fingerTable.set(0, info);
    }
}
