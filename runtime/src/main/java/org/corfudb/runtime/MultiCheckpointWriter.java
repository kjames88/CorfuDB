package org.corfudb.runtime;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.protocols.logprotocol.CheckpointEntry;
import org.corfudb.runtime.collections.SMRMap;
import org.corfudb.runtime.exceptions.TransactionAbortedException;
import org.corfudb.runtime.object.CorfuCompileProxy;
import org.corfudb.runtime.object.ICorfuSMR;
import org.corfudb.util.Utils;
import org.corfudb.util.serializer.ISerializer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Checkpoint multiple SMRMaps serially as a prerequisite for a later log trim.
 */
@Slf4j
public class MultiCheckpointWriter {
    @Getter
    private List<ICorfuSMR<Map>> maps = new ArrayList<>();
    @Getter
    private List<Long> checkpointLogAddresses = new ArrayList<>();

    /** Add a map to the list of maps to be checkpointed by this class. */
    @SuppressWarnings("unchecked")
    public void addMap(SMRMap map) {
        maps.add((ICorfuSMR<Map>) map);
    }

    /** Add map(s) to the list of maps to be checkpointed by this class. */

    public void addAllMaps(Collection<SMRMap> maps) {
        for (SMRMap map : maps) {
            addMap(map);
        }
    }

    /** Checkpoint multiple SMRMaps serially.
     *
     * @param rt CorfuRuntime
     * @param author Author's name, stored in checkpoint metadata
     * @return Global log address of the first record of
     * @throws Exception
     */
    public long appendCheckpoints(CorfuRuntime rt, String author)
            throws Exception {
        return appendCheckpoints(rt, author, (x,y) -> {});
    }

    /** Checkpoint multiple SMRMaps serially.
     *
     * @param rt CorfuRuntime
     * @param author Author's name, stored in checkpoint metadata
     * @param postAppendFunc User-supplied lambda for post-append action on each
     *                       checkpoint entry type.
     * @return Global log address of the first record of
     * @throws Exception
     */

    public long appendCheckpoints(CorfuRuntime rt, String author,
                                  BiConsumer<CheckpointEntry,Long> postAppendFunc)
            throws Exception {
        long globalAddress = CheckpointWriter.startGlobalSnapshotTxn(rt);
        log.trace("appendCheckpoints: author '{}' at globalAddress {} begins",
                author, globalAddress);

        try {
            for (ICorfuSMR<Map> map : maps) {
                UUID streamID = map.getCorfuStreamID();
                while (true) {
                    CheckpointWriter cpw = new CheckpointWriter(rt, streamID, author, (SMRMap) map);
                    ISerializer serializer =
                            ((CorfuCompileProxy<Map>) map.getCorfuSMRProxy())
                                    .getSerializer();
                    cpw.setSerializer(serializer);
                    cpw.setPostAppendFunc(postAppendFunc);
                    log.trace("appendCheckpoints: checkpoint map {} begin",
                            Utils.toReadableID(map.getCorfuStreamID()));
                    try {
                        List<Long> addresses = cpw.appendCheckpoint();
                        log.trace("appendCheckpoints: checkpoint map {} end",
                                Utils.toReadableID(map.getCorfuStreamID()));
                        checkpointLogAddresses.addAll(addresses);
                        break;
                    } catch (TransactionAbortedException ae) {
                        log.trace("appendCheckpoints: checkpoint map {} TransactionAbortedException, retry",
                                Utils.toReadableID(map.getCorfuStreamID()));
                        // Don't break!
                    }
                }
            }
        } finally {
            log.trace("appendCheckpoints: author '{}' at globalAddress {} finished",
                    author, globalAddress);
            rt.getObjectsView().TXEnd();
        }
        return globalAddress;
    }

}
