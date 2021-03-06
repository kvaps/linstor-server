package com.linbit.linstor.api.protobuf.serializer;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.linbit.linstor.Node;
import com.linbit.linstor.Node.NodeApi;
import com.linbit.linstor.NodeName;
import com.linbit.linstor.Resource;
import com.linbit.linstor.Resource.RscApi;
import com.linbit.linstor.ResourceDefinition;
import com.linbit.linstor.ResourceDefinition.RscDfnApi;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.SnapshotDefinition;
import com.linbit.linstor.StorPool;
import com.linbit.linstor.StorPool.StorPoolApi;
import com.linbit.linstor.StorPoolDefinition;
import com.linbit.linstor.StorPoolDefinition.StorPoolDfnApi;
import com.linbit.linstor.VolumeNumber;
import com.linbit.linstor.annotation.ApiContext;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.CtrlClientSerializerBuilderImpl;
import com.linbit.linstor.api.interfaces.serializer.CtrlClientSerializer;
import com.linbit.linstor.api.protobuf.ProtoMapUtils;
import com.linbit.linstor.core.Controller;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.proto.MsgApiVersionOuterClass.MsgApiVersion;
import com.linbit.linstor.proto.MsgLstCtrlCfgPropsOuterClass.MsgLstCtrlCfgProps;
import com.linbit.linstor.proto.MsgLstNodeOuterClass;
import com.linbit.linstor.proto.MsgLstRscDfnOuterClass.MsgLstRscDfn;
import com.linbit.linstor.proto.MsgLstRscOuterClass;
import com.linbit.linstor.proto.MsgLstSnapshotDfnOuterClass;
import com.linbit.linstor.proto.MsgLstStorPoolDfnOuterClass;
import com.linbit.linstor.proto.MsgLstStorPoolOuterClass;
import com.linbit.linstor.proto.RscStateOuterClass;
import com.linbit.linstor.proto.SnapshotDfnOuterClass;
import com.linbit.linstor.proto.VlmStateOuterClass;
import com.linbit.linstor.proto.apidata.NodeApiData;
import com.linbit.linstor.proto.apidata.RscApiData;
import com.linbit.linstor.proto.apidata.RscDfnApiData;
import com.linbit.linstor.proto.apidata.StorPoolApiData;
import com.linbit.linstor.proto.apidata.StorPoolDfnApiData;
import com.linbit.linstor.proto.eventdata.EventSnapshotDeploymentOuterClass;
import com.linbit.linstor.satellitestate.SatelliteResourceState;
import com.linbit.linstor.satellitestate.SatelliteState;
import com.linbit.linstor.satellitestate.SatelliteVolumeState;
import com.linbit.linstor.security.AccessContext;

@Singleton
public class ProtoCtrlClientSerializer extends ProtoCommonSerializer
    implements CtrlClientSerializer, CtrlClientSerializerBuilderImpl.CtrlClientSerializationWriter
{
    @Inject
    public ProtoCtrlClientSerializer(
        final ErrorReporter errReporterRef,
        final @ApiContext AccessContext serializerCtxRef
    )
    {
        super(errReporterRef, serializerCtxRef);
    }

    @Override
    public CtrlClientSerializerBuilder builder()
    {
        return builder(null);
    }

    @Override
    public CtrlClientSerializerBuilder builder(String apiCall)
    {
        return builder(null, null);
    }

    @Override
    public CtrlClientSerializerBuilder builder(String apiCall, Integer msgId)
    {
        return new CtrlClientSerializerBuilderImpl(errorReporter, this, apiCall, msgId);
    }

    @Override
    public void writeNodeList(List<NodeApi> nodes, ByteArrayOutputStream baos) throws IOException
    {
        MsgLstNodeOuterClass.MsgLstNode.Builder msgListNodeBuilder = MsgLstNodeOuterClass.MsgLstNode.newBuilder();

        for (Node.NodeApi apiNode: nodes)
        {
            msgListNodeBuilder.addNodes(NodeApiData.toNodeProto(apiNode));
        }

        msgListNodeBuilder.build().writeDelimitedTo(baos);
    }

    @Override
    public void writeStorPoolDfnList(List<StorPoolDfnApi> storPoolDfns, ByteArrayOutputStream baos)
        throws IOException
    {
        MsgLstStorPoolDfnOuterClass.MsgLstStorPoolDfn.Builder msgListStorPoolDfnsBuilder =
            MsgLstStorPoolDfnOuterClass.MsgLstStorPoolDfn.newBuilder();

        for (StorPoolDefinition.StorPoolDfnApi apiDfn: storPoolDfns)
        {
            msgListStorPoolDfnsBuilder.addStorPoolDfns(StorPoolDfnApiData.fromStorPoolDfnApi(apiDfn));
        }

        msgListStorPoolDfnsBuilder.build().writeDelimitedTo(baos);
    }

    @Override
    public void writeStorPoolList(List<StorPoolApi> storPools, ByteArrayOutputStream baos)
        throws IOException
    {
        MsgLstStorPoolOuterClass.MsgLstStorPool.Builder msgListBuilder =
            MsgLstStorPoolOuterClass.MsgLstStorPool.newBuilder();
        for (StorPool.StorPoolApi apiStorPool: storPools)
        {
            msgListBuilder.addStorPools(StorPoolApiData.toStorPoolProto(apiStorPool));
        }

        msgListBuilder.build().writeDelimitedTo(baos);
    }

    @Override
    public void writeRscDfnList(List<RscDfnApi> rscDfns, ByteArrayOutputStream baos) throws IOException
    {
        MsgLstRscDfn.Builder msgListRscDfnsBuilder = MsgLstRscDfn.newBuilder();

        for (ResourceDefinition.RscDfnApi apiRscDfn: rscDfns)
        {
            msgListRscDfnsBuilder.addRscDfns(RscDfnApiData.fromRscDfnApi(apiRscDfn));
        }

        msgListRscDfnsBuilder.build().writeDelimitedTo(baos);
    }

    @Override
    public void writeRscList(
        List<RscApi> rscs,
        Map<NodeName, SatelliteState> satelliteStates,
        ByteArrayOutputStream baos
    )
        throws IOException
    {
        MsgLstRscOuterClass.MsgLstRsc.Builder msgListRscsBuilder = MsgLstRscOuterClass.MsgLstRsc.newBuilder();

        for (Resource.RscApi apiRsc: rscs)
        {
            msgListRscsBuilder.addResources(RscApiData.toRscProto(apiRsc));
        }

        for (Map.Entry<NodeName, SatelliteState> satelliteEntry : satelliteStates.entrySet())
        {
            for (Map.Entry<ResourceName, SatelliteResourceState> resourceEntry :
                satelliteEntry.getValue().getResourceStates().entrySet())
            {
                msgListRscsBuilder.addResourceStates(
                    buildResourceState(
                        satelliteEntry.getKey(),
                        resourceEntry.getKey(),
                        resourceEntry.getValue()
                    )
                );
            }
        }

        msgListRscsBuilder.build().writeDelimitedTo(baos);
    }

    @Override
    public void writeSnapshotDfnList(
        List<SnapshotDefinition.SnapshotDfnListItemApi> snapshotDfns,
        ByteArrayOutputStream baos
    )
        throws IOException
    {
        MsgLstSnapshotDfnOuterClass.MsgLstSnapshotDfn.Builder msgListSnapshotBuilder =
            MsgLstSnapshotDfnOuterClass.MsgLstSnapshotDfn.newBuilder();

        for (SnapshotDefinition.SnapshotDfnListItemApi snapshotDfnApi : snapshotDfns)
        {
            SnapshotDfnOuterClass.SnapshotDfn.Builder snapshotDfnBuilder =
                SnapshotDfnOuterClass.SnapshotDfn.newBuilder();

            snapshotDfnBuilder
                .setUuid(snapshotDfnApi.getUuid().toString())
                .setSnapshotName(snapshotDfnApi.getSnapshotName())
                .setRscDfnUuid(snapshotDfnApi.getUuid().toString())
                .setRscName(snapshotDfnApi.getRscDfn().getResourceName())
                .addAllSnapshotDfnFlags(SnapshotDefinition.SnapshotDfnFlags.toStringList(snapshotDfnApi.getFlags()))
                .addAllSnapshots(snapshotDfnApi.getNodeNames().stream()
                    .map(nodeName -> SnapshotDfnOuterClass.Snapshot.newBuilder().setNodeName(nodeName).build())
                    .collect(Collectors.toList()))
                .addAllSnapshotVlmDfns(snapshotDfnApi.getSnapshotVlmDfnList().stream()
                    .map(snapshotVlmDfnApi -> SnapshotDfnOuterClass.SnapshotVlmDfn.newBuilder()
                        .setVlmNr(snapshotVlmDfnApi.getVolumeNr())
                        .setVlmSize(snapshotVlmDfnApi.getSize())
                        .build())
                    .collect(Collectors.toList()));

            msgListSnapshotBuilder.addSnapshotDfns(snapshotDfnBuilder.build());
        }

        msgListSnapshotBuilder.build().writeDelimitedTo(baos);
    }

    @Override
    public void writeApiVersion(
        final long features,
        final String controllerInfo,
        ByteArrayOutputStream baos) throws IOException
    {
        MsgApiVersion.Builder msgApiVersion = MsgApiVersion.newBuilder();

        // set features
        msgApiVersion.setVersion(Controller.API_VERSION);
        msgApiVersion.setMinVersion(Controller.API_MIN_VERSION);
        msgApiVersion.setFeatures(features);
        msgApiVersion.setControllerInfo(controllerInfo);
        msgApiVersion.build().writeDelimitedTo(baos);
    }

    @Override
    public void writeCtrlCfgSingleProp(String namespace, String key, String value, ByteArrayOutputStream baos)
        throws IOException
    {
        Map<String, String> map = new HashMap<>();
        map.put(getFullKey(namespace, key), value);
        writeCtrlCfgProps(map, baos);
    }

    @Override
    public void writeCtrlCfgProps(Map<String, String> map, ByteArrayOutputStream baos) throws IOException
    {
        MsgLstCtrlCfgProps.newBuilder()
            .addAllProps(
                ProtoMapUtils.fromMap(map)
            )
            .build()
            .writeDelimitedTo(baos);
    }

    @Override
    public void writeSnapshotDeploymentEvent(ApiCallRc apiCallRc, ByteArrayOutputStream baos)
        throws IOException
    {
        EventSnapshotDeploymentOuterClass.EventSnapshotDeployment.newBuilder()
            .addAllResponses(serializeApiCallRc(apiCallRc))
            .build()
            .writeDelimitedTo(baos);
    }

    private String getFullKey(String namespace, String key)
    {
        String fullKey;
        if (namespace != null && !namespace.trim().equals(""))
        {
            fullKey = namespace + "/" + key;
        }
        else
        {
            fullKey = key;
        }
        return fullKey;
    }

    private static VlmStateOuterClass.VlmState buildVolumeState(
        VolumeNumber volumeNumber, SatelliteVolumeState volumeState)
    {
        VlmStateOuterClass.VlmState.Builder vlmStateBuilder = VlmStateOuterClass.VlmState.newBuilder();

        vlmStateBuilder
            .setVlmNr(volumeNumber.value);

        if (volumeState.getDiskState() != null)
        {
            vlmStateBuilder.setDiskState(volumeState.getDiskState());
        }

        return vlmStateBuilder.build();
    }

    private static RscStateOuterClass.RscState buildResourceState(
        final NodeName nodeName,
        final ResourceName resourceName,
        final SatelliteResourceState resourceState
    )
    {
        RscStateOuterClass.RscState.Builder rscStateBuilder = RscStateOuterClass.RscState.newBuilder();

        rscStateBuilder
            .setNodeName(nodeName.displayValue)
            .setRscName(resourceName.displayValue);

        if (resourceState.isInUse() != null)
        {
            rscStateBuilder.setInUse(resourceState.isInUse());
        }

        // volumes
        for (Map.Entry<VolumeNumber, SatelliteVolumeState> volumeEntry : resourceState.getVolumeStates().entrySet())
        {
            rscStateBuilder.addVlmStates(buildVolumeState(volumeEntry.getKey(), volumeEntry.getValue()));
        }

        return rscStateBuilder.build();
    }
}
