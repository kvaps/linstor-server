package com.linbit.linstor.api.interfaces.serializer;

import java.util.List;
import java.util.Map;

import com.linbit.linstor.Node;
import com.linbit.linstor.NodeName;
import com.linbit.linstor.Resource;
import com.linbit.linstor.ResourceDefinition;
import com.linbit.linstor.SnapshotDefinition;
import com.linbit.linstor.StorPool;
import com.linbit.linstor.StorPoolDefinition;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.satellitestate.SatelliteState;

public interface CtrlClientSerializer extends CommonSerializer
{
    CtrlClientSerializerBuilder builder();

    CtrlClientSerializerBuilder builder(String apiCall);

    CtrlClientSerializerBuilder builder(String apiCall, Integer msgId);

    public interface CtrlClientSerializerBuilder extends CommonSerializerBuilder
    {
        /*
         * Controller -> Client
         */
        CtrlClientSerializerBuilder nodeList(List<Node.NodeApi> nodes);
        CtrlClientSerializerBuilder storPoolDfnList(List<StorPoolDefinition.StorPoolDfnApi> storPoolDfns);
        CtrlClientSerializerBuilder storPoolList(List<StorPool.StorPoolApi> storPools);
        CtrlClientSerializerBuilder resourceDfnList(List<ResourceDefinition.RscDfnApi> rscDfns);
        CtrlClientSerializerBuilder resourceList(
            List<Resource.RscApi> rscs, Map<NodeName, SatelliteState> satelliteStates);
        CtrlClientSerializerBuilder snapshotDfnList(List<SnapshotDefinition.SnapshotDfnListItemApi> snapshotDfns);

        CtrlClientSerializerBuilder apiVersion(long features, String controllerInfo);

        CtrlClientSerializerBuilder ctrlCfgSingleProp(String namespace, String key, String value);
        CtrlClientSerializerBuilder ctrlCfgProps(Map<String, String> map);

        CtrlClientSerializerBuilder snapshotDeploymentEvent(ApiCallRc apiCallRc);
    }
}
