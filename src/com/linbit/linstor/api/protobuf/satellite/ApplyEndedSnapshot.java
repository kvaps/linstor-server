package com.linbit.linstor.api.protobuf.satellite;

import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.api.ApiCall;
import com.linbit.linstor.api.protobuf.ProtobufApiCall;
import com.linbit.linstor.core.StltApiCallHandler;
import com.linbit.linstor.proto.javainternal.MsgIntSnapshotEndedDataOuterClass.MsgIntSnapshotEndedData;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;

@ProtobufApiCall(
    name = InternalApiConsts.API_APPLY_IN_PROGRESS_SNAPSHOT_ENDED,
    description = "Applies an update of a snapshot that is no longer in progress"
)
public class ApplyEndedSnapshot implements ApiCall
{
    private final StltApiCallHandler apiCallHandler;

    @Inject
    public ApplyEndedSnapshot(StltApiCallHandler apiCallHandlerRef)
    {
        apiCallHandler = apiCallHandlerRef;
    }

    @Override
    public void execute(InputStream msgDataIn)
        throws IOException
    {
        MsgIntSnapshotEndedData snapshotEndedData = MsgIntSnapshotEndedData.parseDelimitedFrom(msgDataIn);
        apiCallHandler.applyEndedSnapshotChange(
            snapshotEndedData.getRscName(),
            snapshotEndedData.getSnapshotName(),
            snapshotEndedData.getFullSyncId(),
            snapshotEndedData.getUpdateId()
        );
    }
}
