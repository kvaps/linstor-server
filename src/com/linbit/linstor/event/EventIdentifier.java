package com.linbit.linstor.event;

import com.linbit.linstor.NodeName;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.SnapshotName;
import com.linbit.linstor.VolumeNumber;

import java.util.Objects;

public class EventIdentifier
{
    private final String eventName;

    private final ObjectIdentifier objectIdentifier;

    public static EventIdentifier global(String eventName)
    {
        return new EventIdentifier(eventName, new ObjectIdentifier(null, null, null, null));
    }

    public static EventIdentifier node(String eventName, NodeName nodeName)
    {
        return new EventIdentifier(eventName, new ObjectIdentifier(nodeName, null, null, null));
    }

    /**
     * When used on a satellite, the node name is implicit, so this represents a resource.
     */
    public static EventIdentifier resourceDefinition(String eventName, ResourceName resourceName)
    {
        return new EventIdentifier(eventName, new ObjectIdentifier(null, resourceName, null, null));
    }

    /**
     * When used on a satellite, the node name is implicit, so this represents a volume.
     */
    public static EventIdentifier volumeDefinition(
        String eventName, ResourceName resourceName, VolumeNumber volumeNumber)
    {
        return new EventIdentifier(eventName, new ObjectIdentifier(null, resourceName, volumeNumber, null));
    }

    public static EventIdentifier resource(String eventName, NodeName nodeName, ResourceName resourceName)
    {
        return new EventIdentifier(eventName, new ObjectIdentifier(nodeName, resourceName, null, null));
    }

    public static EventIdentifier volume(
        String eventName, NodeName nodeName, ResourceName resourceName, VolumeNumber volumeNumber)
    {
        return new EventIdentifier(eventName, new ObjectIdentifier(nodeName, resourceName, volumeNumber, null));
    }

    /**
     * When used on a satellite, the node name is implicit, so this represents a snapshot.
     */
    public static EventIdentifier snapshotDefinition(
        String eventName, ResourceName resourceName, SnapshotName snapshotName)
    {
        return new EventIdentifier(eventName, new ObjectIdentifier(null, resourceName, null, snapshotName));
    }

    public static EventIdentifier snapshot(
        String eventName, NodeName nodeName, ResourceName resourceName, SnapshotName snapshotName)
    {
        return new EventIdentifier(eventName, new ObjectIdentifier(nodeName, resourceName, null, snapshotName));
    }

    public EventIdentifier(
        String eventNameRef,
        ObjectIdentifier objectIdentifierRef
    )
    {
        eventName = eventNameRef;
        objectIdentifier = objectIdentifierRef;
    }

    public String getEventName()
    {
        return eventName;
    }

    public NodeName getNodeName()
    {
        return objectIdentifier.getNodeName();
    }

    public ResourceName getResourceName()
    {
        return objectIdentifier.getResourceName();
    }

    public VolumeNumber getVolumeNumber()
    {
        return objectIdentifier.getVolumeNumber();
    }

    public SnapshotName getSnapshotName()
    {
        return objectIdentifier.getSnapshotName();
    }

    public ObjectIdentifier getObjectIdentifier()
    {
        return objectIdentifier;
    }

    @Override
    // Single exit point exception: Automatically generated code
    @SuppressWarnings("DescendantToken")
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null || getClass() != obj.getClass())
        {
            return false;
        }
        EventIdentifier that = (EventIdentifier) obj;
        return Objects.equals(eventName, that.eventName) &&
            Objects.equals(objectIdentifier, that.objectIdentifier);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(eventName, objectIdentifier);
    }

    @Override
    public String toString()
    {
        return eventName + "(" + getObjectIdentifier() + ")";
    }
}
