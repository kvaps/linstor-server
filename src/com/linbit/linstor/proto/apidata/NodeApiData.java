package com.linbit.linstor.proto.apidata;

import com.linbit.linstor.Node;
import com.linbit.linstor.api.protobuf.ProtoMapUtils;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.proto.LinStorMapEntryOuterClass;
import com.linbit.linstor.proto.NodeOuterClass;
import com.linbit.linstor.NetInterface;
import com.linbit.linstor.proto.NetInterfaceOuterClass;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 *
 * @author rpeinthor
 */
public class NodeApiData implements Node.NodeApi
{
    private NodeOuterClass.Node node;

    public NodeApiData(NodeOuterClass.Node nodeRef)
    {
        node = nodeRef;
    }

    @Override
    public String getName()
    {
        return node.getName();
    }

    @Override
    public UUID getUuid()
    {
        UUID uuid = null;
        if (node.hasUuid())
        {
            uuid = UUID.fromString(node.getUuid());
        }
        return uuid;
    }

    @Override
    public String getType()
    {
        return node.getType();
    }

    @Override
    public Map<String, String> getProps()
    {
        Map<String, String> ret = new HashMap<>();
        for (LinStorMapEntryOuterClass.LinStorMapEntry entry : node.getPropsList())
        {
            ret.put(entry.getKey(), entry.getValue());
        }
        return ret;
    }

    @Override
    public Peer.ConnectionStatus connectionStatus()
    {
        Peer.ConnectionStatus connectionStatus = Peer.ConnectionStatus.UNKNOWN;
        if (node.hasConnectionStatus())
        {
            connectionStatus = Peer.ConnectionStatus.fromInt(node.getConnectionStatus());
        }
        return connectionStatus;
    }

    @Override
    public long getFlags()
    {
        return Node.NodeFlag.fromStringList(node.getFlagsList());
    }

    @Override
    public List<NetInterface.NetInterfaceApi> getNetInterfaces()
    {
        ArrayList<NetInterface.NetInterfaceApi> netInterfaces = new ArrayList<>();
        for (NetInterfaceOuterClass.NetInterface netinter : node.getNetInterfacesList())
        {
            netInterfaces.add(new NetInterfaceApiData(netinter));
        }

        return netInterfaces;
    }

    public static NodeOuterClass.Node toNodeProto(Node.NodeApi nodeApi)
    {
        NodeOuterClass.Node.Builder bld = NodeOuterClass.Node.newBuilder();

        bld.setName(nodeApi.getName());
        bld.setType(nodeApi.getType());
        bld.setUuid(nodeApi.getUuid().toString());
        bld.addAllProps(ProtoMapUtils.fromMap(nodeApi.getProps()));
        bld.addAllFlags(Node.NodeFlag.toStringList(nodeApi.getFlags()));
        bld.addAllNetInterfaces(NetInterfaceApiData.toNetInterfaceProtoList(nodeApi.getNetInterfaces()));
        bld.setConnectionStatus(nodeApi.connectionStatus().value());

        return bld.build();
    }

    @Override
    public UUID getDisklessStorPoolUuid()
    {
        return UUID.fromString(node.getDisklessStorPoolUuid());
    }
}
