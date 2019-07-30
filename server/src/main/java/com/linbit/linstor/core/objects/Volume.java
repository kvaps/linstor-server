package com.linbit.linstor.core.objects;

import com.linbit.linstor.DbgInstanceUuid;
import com.linbit.linstor.NodeName;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.VolumeNumber;
import com.linbit.linstor.api.interfaces.VlmLayerDataApi;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.stateflags.Flags;
import com.linbit.linstor.stateflags.FlagsHelper;
import com.linbit.linstor.stateflags.StateFlags;
import com.linbit.linstor.storage.kinds.DeviceProviderKind;
import com.linbit.linstor.transaction.TransactionObject;
import com.linbit.utils.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 *
 * @author Robert Altnoeder &lt;robert.altnoeder@linbit.com&gt;
 */
public interface Volume extends TransactionObject, DbgInstanceUuid, Comparable<Volume>
{
    UUID getUuid();

    Resource getResource();

    ResourceDefinition getResourceDefinition();

    VolumeDefinition getVolumeDefinition();

    Props getProps(AccessContext accCtx) throws AccessDeniedException;

    StateFlags<VlmFlags> getFlags();

    Stream<VolumeConnection> streamVolumeConnections(AccessContext accCtx)
        throws AccessDeniedException;

    VolumeConnection getVolumeConnection(AccessContext dbCtx, Volume otherVol)
        throws AccessDeniedException;

    void setVolumeConnection(AccessContext accCtx, VolumeConnection volumeConnection)
        throws AccessDeniedException;

    void removeVolumeConnection(AccessContext accCtx, VolumeConnection volumeConnection)
        throws AccessDeniedException;

    String getDevicePath(AccessContext accCtx) throws AccessDeniedException;

    void markDeleted(AccessContext accCtx) throws AccessDeniedException, DatabaseException;

    void setDevicePath(AccessContext accCtx, String path) throws AccessDeniedException;

    boolean isUsableSizeSet(AccessContext accCtx) throws AccessDeniedException;

    void setUsableSize(AccessContext accCtx, long size) throws AccessDeniedException;

    long getUsableSize(AccessContext accCtx) throws AccessDeniedException;

    boolean isAllocatedSizeSet(AccessContext accCtx) throws AccessDeniedException;

    void setAllocatedSize(AccessContext accCtx, long size) throws AccessDeniedException;

    long getAllocatedSize(AccessContext accCtx) throws AccessDeniedException;

    long getEstimatedSize(AccessContext accCtx) throws AccessDeniedException;

    boolean isDeleted();

    void delete(AccessContext accCtx) throws AccessDeniedException, DatabaseException;

    /**
     * Returns the identification key without checking if "this" is already deleted
     */
    Key getKey();

    @Override
    default int compareTo(Volume otherVlm)
    {
        int eq = getResource().getAssignedNode().compareTo(
            otherVlm.getResource().getAssignedNode()
        );
        if (eq == 0)
        {
            eq = getVolumeDefinition().compareTo(otherVlm.getVolumeDefinition()); // also contains rscName comparison
        }
        return eq;
    }

    static String getVolumeKey(Volume volume)
    {
        NodeName nodeName = volume.getResource().getAssignedNode().getName();
        ResourceName rscName = volume.getResourceDefinition().getName();
        VolumeNumber volNr = volume.getVolumeDefinition().getVolumeNumber();
        return nodeName.value + "/" + rscName.value + "/" + volNr.value;
    }

    enum VlmFlags implements Flags
    {
        DELETE(2L),
        RESIZE(4L),
        DRBD_RESIZE(8L);

        public final long flagValue;

        VlmFlags(long value)
        {
            flagValue = value;
        }

        @Override
        public long getFlagValue()
        {
            return flagValue;
        }

        public static VlmFlags[] restoreFlags(long vlmFlags)
        {
            List<VlmFlags> flagList = new ArrayList<>();
            for (VlmFlags flag : VlmFlags.values())
            {
                if ((vlmFlags & flag.flagValue) == flag.flagValue)
                {
                    flagList.add(flag);
                }
            }
            return flagList.toArray(new VlmFlags[flagList.size()]);
        }

        public static List<String> toStringList(long flagsMask)
        {
            return FlagsHelper.toStringList(VlmFlags.class, flagsMask);
        }

        public static long fromStringList(List<String> listFlags)
        {
            return FlagsHelper.fromStringList(VlmFlags.class, listFlags);
        }
    }

    VlmApi getApiData(Long allocated, AccessContext accCtx) throws AccessDeniedException;

    interface VlmApi
    {
        UUID getVlmUuid();
        UUID getVlmDfnUuid();
        String getDevicePath();
        int getVlmNr();
        long getFlags();
        Map<String, String> getVlmProps();
        Optional<Long> getAllocatedSize();
        Optional<Long> getUsableSize();
        List<Pair<String, VlmLayerDataApi>> getVlmLayerData();

        // the following methods should be removed, but will stay for a while for client-compatibility
        @Deprecated
        /** returns the name of the storage pool of the vlmLayerObject with "" as resource name suffix */
        String getStorPoolName();
        @Deprecated
        /** returns the DeviceProviderKind of the storage pool of the vlmLayerObject with "" as resource name suffix */
        DeviceProviderKind getStorPoolDeviceProviderKind();
    }


    /**
     * Identifies a volume globally.
     */
    class Key implements Comparable<Key>
    {
        private final NodeName nodeName;
        private final ResourceName resourceName;
        private final VolumeNumber volumeNumber;

        public Key(Volume volume)
        {
            this(
                volume.getResource().getAssignedNode().getName(),
                volume.getResourceDefinition().getName(),
                volume.getVolumeDefinition().getVolumeNumber()
            );
        }

        public Key(NodeName nodeNameRef, ResourceName resourceNameRef, VolumeNumber volumeNumberRef)
        {
            nodeName = nodeNameRef;
            resourceName = resourceNameRef;
            volumeNumber = volumeNumberRef;
        }

        public NodeName getNodeName()
        {
            return nodeName;
        }

        public ResourceName getResourceName()
        {
            return resourceName;
        }

        public VolumeNumber getVolumeNumber()
        {
            return volumeNumber;
        }

        @Override
        // Code style exception: Automatically generated code
        @SuppressWarnings({"DescendantToken", "ParameterName"})
        public boolean equals(Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (o == null || getClass() != o.getClass())
            {
                return false;
            }
            Key that = (Key) o;
            return Objects.equals(nodeName, that.nodeName) &&
                Objects.equals(resourceName, that.resourceName) &&
                Objects.equals(volumeNumber, that.volumeNumber);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(nodeName, resourceName, volumeNumber);
        }

        @SuppressWarnings("unchecked")
        @Override
        public int compareTo(Key other)
        {
            int eq = nodeName.compareTo(other.nodeName);
            if (eq == 0)
            {
                eq = resourceName.compareTo(other.resourceName);
                if (eq == 0)
                {
                    eq = volumeNumber.compareTo(other.volumeNumber);
                }
            }
            return eq;
        }

        @Override
        public String toString()
        {
            return "Volume.Key [nodeName=" + nodeName + ", resourceName=" + resourceName + ", volumeNumber=" +
                volumeNumber + "]";
        }
    }

    public interface InitMaps
    {
        Map<Volume.Key, VolumeConnection> getVolumeConnections();
    }
}