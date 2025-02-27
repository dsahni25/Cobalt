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
 * A model clas that represents an agent
 */
@AllArgsConstructor
@Data
@Accessors(fluent = true)
@Jacksonized
@Builder
@ProtobufName("AgentAction")
public final class AgentAction implements Action {
    /**
     * The agent's name
     */
    @ProtobufProperty(index = 1, name = "name", type = STRING)
    private String name;

    /**
     * The agent's device id
     */
    @ProtobufProperty(index = 2, name = "deviceID", type = INT32)
    private int deviceId;

    /**
     * Whether the agent was deleted
     */
    @ProtobufProperty(index = 3, name = "isDeleted", type = BOOL)
    private boolean deleted;

    /**
     * The name of this action
     *
     * @return a non-null string
     */
    @Override
    public String indexName() {
        return "deviceAgent";
    }

    /**
     * The version of this action
     *
     * @return a non-null string
     */
    @Override
    public int actionVersion() {
        return 7;
    }

    /**
     * The type of this action
     *
     * @return a non-null string
     */
    @Override
    public BinaryPatchType actionType() {
        return null;
    }
}
