package com.linbit.linstor.dbdrivers.satellite;

import com.linbit.linstor.VolumeConnectionData;
import com.linbit.linstor.annotation.SystemContext;
import com.linbit.linstor.dbdrivers.interfaces.VolumeConnectionDataDatabaseDriver;
import com.linbit.linstor.security.AccessContext;
import javax.inject.Inject;

public class SatelliteVolConDfnDriver implements VolumeConnectionDataDatabaseDriver
{
    private final AccessContext dbCtx;

    @Inject
    public SatelliteVolConDfnDriver(@SystemContext AccessContext dbCtxRef)
    {
        dbCtx = dbCtxRef;
    }

    @Override
    public void create(VolumeConnectionData conDfnData)
    {
        // no-op
    }

    @Override
    public void delete(VolumeConnectionData conDfnData)
    {
        // no-op
    }
}
