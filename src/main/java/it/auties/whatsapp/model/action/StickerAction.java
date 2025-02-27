package it.auties.whatsapp.model.action;

import it.auties.protobuf.base.ProtobufName;
import it.auties.protobuf.base.ProtobufProperty;
import it.auties.whatsapp.binary.BinaryPatchType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import static it.auties.protobuf.base.ProtobufType.*;

/**
 * A model clas that represents a sticker
 */
@AllArgsConstructor
@Data
@Accessors(fluent = true)
@Jacksonized
@Builder
@ProtobufName("StickerAction")
public final class StickerAction implements Action {
    @ProtobufProperty(index = 1, name = "url", type = STRING)
    private String url;

    @ProtobufProperty(index = 2, name = "fileEncSha256", type = BYTES)
    private byte[] fileEncSha256;

    @ProtobufProperty(index = 3, name = "mediaKey", type = BYTES)
    private byte[] mediaKey;

    @ProtobufProperty(index = 4, name = "mimetype", type = STRING)
    private String mimetype;

    @ProtobufProperty(index = 5, name = "height", type = UINT32)
    private int height;

    @ProtobufProperty(index = 6, name = "width", type = UINT32)
    private int width;

    @ProtobufProperty(index = 7, name = "directPath", type = STRING)
    private String directPath;

    @ProtobufProperty(index = 8, name = "fileLength", type = UINT64)
    private long fileLength;

    @ProtobufProperty(index = 9, name = "isFavorite", type = BOOL)
    private boolean favorite;

    @ProtobufProperty(index = 10, name = "deviceIdHint", type = UINT32)
    private Integer deviceIdHint;

    /**
     * Always throws an exception as this action cannot be serialized
     *
     * @return an exception
     */
    @Override
    public String indexName() {
        throw new UnsupportedOperationException("Cannot send action");
    }

    /**
     * Always throws an exception as this action cannot be serialized
     *
     * @return an exception
     */
    @Override
    public int actionVersion() {
        throw new UnsupportedOperationException("Cannot send action");
    }

    /**
     * Always throws an exception as this action cannot be serialized
     *
     * @return an exception
     */
    @Override
    public BinaryPatchType actionType() {
        throw new UnsupportedOperationException("Cannot send action");
    }
}
