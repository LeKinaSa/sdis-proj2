package messages;

public class ChunkMessage extends Message {
    public static final String name = "CHUNK";

    public final String fileId;
    public final int chunkNumber;

    public ChunkMessage(String protocolVersion, int senderId, String fileId, int chunkNumber, byte[] body) {
        super(protocolVersion, senderId, body);

        this.fileId = fileId;
        this.chunkNumber = chunkNumber;
    }

    @Override
    public String buildHeader() {
        String[] components = { protocolVersion, name, String.valueOf(senderId), fileId, String.valueOf(chunkNumber) };

        return String.join(" ", components);
    }

    public static ChunkMessage parse(String header, byte[] body) {
        // <Version> CHUNK <SenderId> <FileId> <ChunkNo> <CRLF><CRLF><Body>
        String[] headerComponents = header.split(" ");

        if (headerComponents.length != 5 || !headerComponents[1].equals(name)) {
            return null;
        }

        String protocolVersion = headerComponents[0];
        int senderId = Integer.parseInt(headerComponents[2]);
        String fileId = headerComponents[3];
        int chunkNumber = Integer.parseInt(headerComponents[4]);

        return new ChunkMessage(protocolVersion, senderId, fileId, chunkNumber, body);
    }
}
