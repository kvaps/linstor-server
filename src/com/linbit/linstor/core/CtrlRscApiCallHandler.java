package com.linbit.linstor.core;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.ValueOutOfRangeException;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.LinStorException;
import com.linbit.linstor.LinStorRuntimeException;
import com.linbit.linstor.Node;
import com.linbit.linstor.Node.NodeFlag;
import com.linbit.linstor.NodeData;
import com.linbit.linstor.NodeId;
import com.linbit.linstor.NodeName;
import com.linbit.linstor.PriorityProps;
import com.linbit.linstor.Resource;
import com.linbit.linstor.Resource.RscFlags;
import com.linbit.linstor.ResourceData;
import com.linbit.linstor.ResourceDataFactory;
import com.linbit.linstor.ResourceDefinition;
import com.linbit.linstor.ResourceDefinition.RscDfnFlags;
import com.linbit.linstor.ResourceDefinitionData;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.StorPool;
import com.linbit.linstor.StorPoolDefinitionData;
import com.linbit.linstor.Volume;
import com.linbit.linstor.Volume.VlmApi;
import com.linbit.linstor.VolumeData;
import com.linbit.linstor.VolumeDataFactory;
import com.linbit.linstor.VolumeDefinition;
import com.linbit.linstor.VolumeDefinitionData;
import com.linbit.linstor.VolumeDefinitionDataControllerFactory;
import com.linbit.linstor.VolumeNumber;
import com.linbit.linstor.annotation.ApiContext;
import com.linbit.linstor.annotation.PeerContext;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiCallRcImpl.ApiCallRcEntry;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.interfaces.serializer.CtrlClientSerializer;
import com.linbit.linstor.api.interfaces.serializer.CtrlStltSerializer;
import com.linbit.linstor.api.pojo.VlmUpdatePojo;
import com.linbit.linstor.api.prop.WhitelistProps;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.propscon.InvalidValueException;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.satellitestate.SatelliteResourceState;
import com.linbit.linstor.satellitestate.SatelliteState;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.AccessType;
import com.linbit.linstor.security.ControllerSecurityModule;
import com.linbit.linstor.security.ObjectProtection;
import com.linbit.linstor.transaction.TransactionMgr;

import static com.linbit.linstor.api.ApiConsts.API_LST_RSC;
import static com.linbit.linstor.api.ApiConsts.FAIL_INVLD_STOR_POOL_NAME;
import static com.linbit.linstor.api.ApiConsts.FAIL_NOT_FOUND_DFLT_STOR_POOL;
import static com.linbit.linstor.api.ApiConsts.KEY_STOR_POOL_NAME;
import static com.linbit.linstor.api.ApiConsts.MASK_STOR_POOL;
import static com.linbit.linstor.api.ApiConsts.MASK_WARN;
import static java.util.stream.Collectors.toList;

public class CtrlRscApiCallHandler extends CtrlRscCrtApiCallHandler
{
    private String currentNodeName;
    private String currentRscName;
    private final CtrlClientSerializer clientComSerializer;
    private final ObjectProtection rscDfnMapProt;
    private final CoreModule.ResourceDefinitionMap rscDfnMap;
    private final ObjectProtection nodesMapProt;
    private final CoreModule.NodesMap nodesMap;
    private final String defaultStorPoolName;
    private final VolumeDefinitionDataControllerFactory volumeDefinitionDataFactory;

    @Inject
    public CtrlRscApiCallHandler(
        ErrorReporter errorReporterRef,
        CtrlStltSerializer interComSerializer,
        CtrlClientSerializer clientComSerializerRef,
        @ApiContext AccessContext apiCtxRef,
        @Named(ControllerSecurityModule.RSC_DFN_MAP_PROT) ObjectProtection rscDfnMapProtRef,
        CoreModule.ResourceDefinitionMap rscDfnMapRef,
        @Named(ControllerSecurityModule.NODES_MAP_PROT) ObjectProtection nodesMapProtRef,
        CoreModule.NodesMap nodesMapRef,
        @Named(ConfigModule.CONFIG_STOR_POOL_NAME) String defaultStorPoolNameRef,
        CtrlObjectFactories objectFactories,
        @Named(ControllerCoreModule.SATELLITE_PROPS) Props stltConfRef,
        ResourceDataFactory resourceDataFactoryRef,
        VolumeDataFactory volumeDataFactoryRef,
        VolumeDefinitionDataControllerFactory volumeDefinitionDataFactoryRef,
        Provider<TransactionMgr> transMgrProviderRef,
        @PeerContext AccessContext peerAccCtxRef,
        Provider<Peer> peerRef,
        WhitelistProps whitelistPropsRef
    )
    {
        super(
            errorReporterRef,
            apiCtxRef,
            interComSerializer,
            objectFactories,
            transMgrProviderRef,
            peerAccCtxRef,
            peerRef,
            whitelistPropsRef,
            stltConfRef,
            resourceDataFactoryRef,
            volumeDataFactoryRef
        );
        clientComSerializer = clientComSerializerRef;
        rscDfnMapProt = rscDfnMapProtRef;
        rscDfnMap = rscDfnMapRef;
        nodesMapProt = nodesMapProtRef;
        nodesMap = nodesMapRef;
        defaultStorPoolName = defaultStorPoolNameRef;
        volumeDefinitionDataFactory = volumeDefinitionDataFactoryRef;
    }

    public ApiCallRc createResource(
        String nodeNameStr,
        String rscNameStr,
        List<String> flagList,
        Map<String, String> rscPropsMap,
        List<VlmApi> vlmApiList
    )
    {
        return createResource(
            nodeNameStr,
            rscNameStr,
            flagList,
            rscPropsMap,
            vlmApiList,
            true,
            null
        );
    }

    private void checkStorPoolLoaded(
        final Resource rsc,
        StorPool storPool,
        String storPoolNameStr,
        final VolumeDefinition vlmDfn
    )
    {
        if (storPool == null)
        {
            throw asExc(
                new LinStorException("Dependency not found"),
                "The default storage pool '" + storPoolNameStr + "' " +
                    "for resource '" + rsc.getDefinition().getName().displayValue + "' " +
                    "for volume number '" +  vlmDfn.getVolumeNumber().value + "' " +
                    "is not deployed on node '" + rsc.getAssignedNode().getName().displayValue + "'.",
                null, // cause
                "The resource which should be deployed had at least one volume definition " +
                    "(volume number '" + vlmDfn.getVolumeNumber().value + "') which LinStor " +
                    "tried to automatically create. " +
                    "The default storage pool's name for this new volume was looked for in " +
                    "its volume definition's properties, its resource's properties, its node's " +
                    "properties and finally in a system wide default storage pool name defined by " +
                    "the LinStor controller.",
                null, // correction
                FAIL_NOT_FOUND_DFLT_STOR_POOL
            );
        }
    }

    private void checkBackingDiskWithDiskless(final Resource rsc, final StorPool storPool)
        throws AccessDeniedException
    {
        if (storPool != null && storPool.getDriverKind(apiCtx).hasBackingStorage())
        {
            throw asExc(
                new LinStorException("Incorrect storage pool used."),
                "Storage pool with backing disk not allowed with diskless resource.",
                String.format("Resource '%s' flagged as diskless, but a storage pool '%s' " +
                        "with backing disk was specified.",
                    rsc.getDefinition().getName().displayValue,
                    storPool.getName().displayValue),
                null,
                "Use a storage pool with a diskless driver or remove the diskless flag.",
                FAIL_INVLD_STOR_POOL_NAME
            );
        }
    }

    private void warnAndFladDiskless(Resource rsc, final StorPool storPool)
        throws AccessDeniedException, SQLException
    {
        if (storPool != null && !storPool.getDriverKind(apiCtx).hasBackingStorage())
        {
            addAnswer(
                "Resource will be automatically flagged diskless.",
                String.format("Used storage pool '%s' is diskless, " +
                    "but resource was not flagged diskless", storPool.getName().displayValue),
                null,
                null,
                MASK_WARN | MASK_STOR_POOL
            );
            rsc.getStateFlags().enableFlags(apiCtx, RscFlags.DISKLESS);
        }
    }

    /**
     * Resolves the correct storage pool and also handles error/warnings in diskless modes.
     *
     * @param rsc
     * @param prioProps
     * @param vlmDfn
     * @return
     * @throws InvalidKeyException
     * @throws AccessDeniedException
     * @throws InvalidValueException
     * @throws SQLException
     */
    private StorPool resolveStorPool(Resource rsc, final PriorityProps prioProps, final VolumeDefinition vlmDfn)
        throws InvalidKeyException, AccessDeniedException, InvalidValueException, SQLException
    {
        final boolean isRscDiskless = isDiskless(rsc);
        Props rscProps = getProps(rsc);
        String storPoolNameStr = prioProps.getProp(KEY_STOR_POOL_NAME);
        StorPool storPool;
        if (isRscDiskless)
        {
            if (storPoolNameStr == null || "".equals(storPoolNameStr))
            {
                rscProps.setProp(ApiConsts.KEY_STOR_POOL_NAME, LinStor.DISKLESS_STOR_POOL_NAME);
                storPool = rsc.getAssignedNode().getDisklessStorPool(apiCtx);
                storPoolNameStr = LinStor.DISKLESS_STOR_POOL_NAME;
            }
            else
            {
                storPool = rsc.getAssignedNode().getStorPool(
                    apiCtx,
                    asStorPoolName(storPoolNameStr)
                );
            }

            checkBackingDiskWithDiskless(rsc, storPool);
        }
        else
        {
            if (storPoolNameStr == null || "".equals(storPoolNameStr))
            {
                storPoolNameStr = defaultStorPoolName;
            }
            storPool = rsc.getAssignedNode().getStorPool(
                apiCtx,
                asStorPoolName(storPoolNameStr)
            );

            warnAndFladDiskless(rsc, storPool);
        }

        checkStorPoolLoaded(rsc, storPool, storPoolNameStr, vlmDfn);

        return storPool;
    }

    public ApiCallRc createResource(
        String nodeNameStr,
        String rscNameStr,
        List<String> flagList,
        Map<String, String> rscPropsMap,
        List<VlmApi> vlmApiList,
        boolean autoCloseCurrentTransMgr,
        ApiCallRcImpl apiCallRcRef
    )
    {
        ApiCallRcImpl apiCallRc = apiCallRcRef;
        if (apiCallRc == null)
        {
            apiCallRc = new ApiCallRcImpl();
        }

        try (
            AbsApiCallHandler basicallyThis = setContext(
                ApiCallType.CREATE,
                apiCallRc,
                autoCloseCurrentTransMgr,
                nodeNameStr,
                rscNameStr
            )
        )
        {
            NodeData node = loadNode(nodeNameStr, true);
            ResourceDefinitionData rscDfn = loadRscDfn(rscNameStr, true);

            NodeId nodeId = getNextFreeNodeId(rscDfn);

            ResourceData rsc = createResource(rscDfn, node, nodeId, flagList);
            Props rscProps = getProps(rsc);

            fillProperties(rscPropsMap, rscProps, ApiConsts.FAIL_ACC_DENIED_RSC);

            boolean isRscDiskless = isDiskless(rsc);

            Props rscDfnProps = getProps(rscDfn);
            Props nodeProps = getProps(node);

            Map<Integer, Volume> vlmMap = new TreeMap<>();
            for (VlmApi vlmApi : vlmApiList)
            {
                VolumeDefinitionData vlmDfn = loadVlmDfn(rscDfn, vlmApi.getVlmNr(), true);

                PriorityProps prioProps = new PriorityProps(
                    rscProps,
                    getProps(vlmDfn),
                    rscDfnProps,
                    nodeProps
                );

                StorPool storPool;

                String storPoolNameStr;
                storPoolNameStr = vlmApi.getStorPoolName();
                if (storPoolNameStr != null && !storPoolNameStr.isEmpty())
                {
                    StorPoolDefinitionData storPoolDfn = loadStorPoolDfn(storPoolNameStr, true);
                    storPool = loadStorPool(storPoolDfn, node, true);

                    if (isRscDiskless)
                    {
                        checkBackingDiskWithDiskless(rsc, storPool);
                    }
                    else
                    {
                        warnAndFladDiskless(rsc, storPool);
                    }

                    checkStorPoolLoaded(rsc, storPool, storPoolNameStr, vlmDfn);
                }
                else
                {
                    storPool = resolveStorPool(rsc, prioProps, vlmDfn);
                }

                VolumeData vlmData = createVolume(rsc, vlmDfn, storPool, vlmApi);

                Props vlmProps = getProps(vlmData);

                fillProperties(vlmApi.getVlmProps(), vlmProps, ApiConsts.FAIL_ACC_DENIED_VLM);

                vlmMap.put(vlmDfn.getVolumeNumber().value, vlmData);
            }

            Iterator<VolumeDefinition> iterateVolumeDfn = getVlmDfnIterator(rscDfn);
            while (iterateVolumeDfn.hasNext())
            {
                VolumeDefinition vlmDfn = iterateVolumeDfn.next();

                objRefs.get().put(ApiConsts.KEY_VLM_NR, Integer.toString(vlmDfn.getVolumeNumber().value));
                variables.get().put(ApiConsts.KEY_VLM_NR, Integer.toString(vlmDfn.getVolumeNumber().value));

                // first check if we probably just deployed a vlm for this vlmDfn
                if (rsc.getVolume(vlmDfn.getVolumeNumber()) == null)
                {
                    // not deployed yet.

                    PriorityProps prioProps = new PriorityProps(
                        getProps(vlmDfn),
                        rscProps,
                        nodeProps
                    );

                    StorPool storPool = resolveStorPool(rsc, prioProps, vlmDfn);

                    // storPool is guaranteed to be != null
                    // create missing vlm with default values
                    VolumeData vlm = createVolume(rsc, vlmDfn, storPool, null);
                    vlmMap.put(vlmDfn.getVolumeNumber().value, vlm);
                }
            }

            commit();

            if (rsc.getVolumeCount() > 0)
            {
                // only notify satellite if there are volumes to deploy.
                // otherwise a bug occurs when an empty resource is deleted
                // the controller instantly deletes it (without marking for deletion first)
                // but doesn't tell the satellite...
                // the next time a resource with the same name will get a different UUID and
                // will cause a conflict (and thus, an exception) on the satellite
                updateSatellites(rsc);
            }
            // TODO: if a satellite confirms creation, also log it to controller.info

            reportSuccess(rsc.getUuid());

            for (Entry<Integer, Volume> entry : vlmMap.entrySet())
            {
                ApiCallRcEntry vlmCreatedRcEntry = new ApiCallRcEntry();
                vlmCreatedRcEntry.setMessageFormat(
                    "Volume with number '" + entry.getKey() + "' on resource '" +
                        entry.getValue().getResourceDefinition().getName().displayValue + "' on node '" +
                        entry.getValue().getResource().getAssignedNode().getName().displayValue +
                        "' successfully created"
                );
                vlmCreatedRcEntry.setDetailsFormat(
                    "Volume UUID is: " + entry.getValue().getUuid().toString()
                );
                vlmCreatedRcEntry.setReturnCode(ApiConsts.MASK_CRT | ApiConsts.MASK_VLM | ApiConsts.CREATED);
                vlmCreatedRcEntry.putAllObjRef(objRefs.get());
                vlmCreatedRcEntry.putObjRef(ApiConsts.KEY_VLM_NR, Integer.toString(entry.getKey()));
                vlmCreatedRcEntry.putAllVariables(variables.get());
                vlmCreatedRcEntry.putVariable(ApiConsts.KEY_VLM_NR, Integer.toString(entry.getKey()));

                apiCallRc.addEntry(vlmCreatedRcEntry);
            }
        }
        catch (ApiCallHandlerFailedException apiCallHandlerFailedExc)
        {
            // a report and a corresponding api-response already created.
            // however, if the autoCloseCurrentTransMgr was set to false, we are in the scope of an
            // other api call. because of that we re-throw this exception
            if (!autoCloseCurrentTransMgr)
            {
                throw apiCallHandlerFailedExc;
            }
        }
        catch (Exception | ImplementationError exc)
        {
            if (!autoCloseCurrentTransMgr)
            {
                if (exc instanceof ImplementationError)
                {
                    throw (ImplementationError) exc;
                }
                else
                {
                    throw new LinStorRuntimeException("Unknown Exception", exc);
                }
            }
            else
            {
                reportStatic(
                    exc,
                    ApiCallType.CREATE,
                    getObjectDescriptionInline(nodeNameStr, rscNameStr),
                    getObjRefs(nodeNameStr, rscNameStr),
                    getVariables(nodeNameStr, rscNameStr),
                    apiCallRc
                );
            }
        }

        return apiCallRc;
    }

    private boolean isDiskless(Resource rsc)
    {
        boolean isDiskless;
        try
        {
            isDiskless = rsc.getStateFlags().isSet(apiCtx, RscFlags.DISKLESS);
        }
        catch (AccessDeniedException implError)
        {
            throw asImplError(implError);
        }
        return isDiskless;
    }

    public ApiCallRc modifyResource(
        UUID rscUuid,
        String nodeNameStr,
        String rscNameStr,
        Map<String, String> overrideProps,
        Set<String> deletePropKeys
    )
    {
        ApiCallRcImpl apiCallRc = new ApiCallRcImpl();
        try (
            AbsApiCallHandler basicallyThis = setContext(
                ApiCallType.MODIFY,
                apiCallRc,
                true, // autoClose
                nodeNameStr,
                rscNameStr
            );
        )
        {
            ResourceData rsc = loadRsc(nodeNameStr, rscNameStr, true);

            if (rscUuid != null && !rscUuid.equals(rsc.getUuid()))
            {
                addAnswer(
                    "UUID-check failed",
                    ApiConsts.FAIL_UUID_RSC
                );
                throw new ApiCallHandlerFailedException();
            }

            Props props = getProps(rsc);
            Map<String, String> propsMap = props.map();

            fillProperties(overrideProps, props, ApiConsts.FAIL_ACC_DENIED_RSC);

            for (String delKey : deletePropKeys)
            {
                propsMap.remove(delKey);
            }

            commit();

            updateSatellites(rsc);
            reportSuccess(rsc.getUuid());
        }
        catch (ApiCallHandlerFailedException ignore)
        {
            // a report and a corresponding api-response already created. nothing to do here
        }
        catch (Exception | ImplementationError exc)
        {
            reportStatic(
                exc,
                ApiCallType.MODIFY,
                getObjectDescriptionInline(nodeNameStr, rscNameStr),
                getObjRefs(nodeNameStr, rscNameStr),
                getVariables(nodeNameStr, rscNameStr),
                apiCallRc
            );
        }

        return apiCallRc;
    }

    public ApiCallRc deleteResource(
        String nodeNameStr,
        String rscNameStr
    )
    {
        ApiCallRcImpl apiCallRc = new ApiCallRcImpl();

        try (
            AbsApiCallHandler basicallyThis = setContext(
                ApiCallType.DELETE,
                apiCallRc,
                true, // autoClose
                nodeNameStr,
                rscNameStr
            )
        )
        {

            ResourceData rscData = loadRsc(nodeNameStr, rscNameStr, true);

            SatelliteState stltState = rscData.getAssignedNode().getPeer(apiCtx).getSatelliteState();
            SatelliteResourceState rscState = stltState.getResourceStates().get(rscData.getDefinition().getName());

            if (rscState != null && rscState.isInUse() != null && rscState.isInUse())
            {
                addAnswer(
                    String.format("Resource '%s' is still in use.", rscNameStr),
                    "Resource is mounted/in use.",
                    null,
                    String.format("Un-mount resource '%s' on the node '%s'.", rscNameStr, nodeNameStr),
                    ApiConsts.MASK_ERROR | ApiConsts.MASK_RSC | ApiConsts.MASK_DEL
                );
            }
            else
            {
                int volumeCount = rscData.getVolumeCount();
                String successMessage;
                String details;
                if (volumeCount > 0)
                {
                    successMessage = getObjectDescriptionInlineFirstLetterCaps() + " marked for deletion.";
                    details = getObjectDescriptionInlineFirstLetterCaps() + " UUID is: " + rscData.getUuid();
                    markDeleted(rscData);
                }
                else
                {
                    successMessage = getObjectDescriptionInlineFirstLetterCaps() + " deleted.";
                    details = getObjectDescriptionInlineFirstLetterCaps() + " UUID was: " + rscData.getUuid();
                    delete(rscData);
                }

                commit();

                if (volumeCount > 0)
                {
                    // notify satellites
                    updateSatellites(rscData);
                }

                reportSuccess(successMessage, details);
            }
        }
        catch (ApiCallHandlerFailedException ignore)
        {
            // a report and a corresponding api-response already created. nothing to do here
        }
        catch (Exception | ImplementationError exc)
        {
            reportStatic(
                exc,
                ApiCallType.DELETE,
                getObjectDescriptionInline(nodeNameStr, rscNameStr),
                getObjRefs(nodeNameStr, rscNameStr),
                getVariables(nodeNameStr, rscNameStr),
                apiCallRc
            );
        }


        return apiCallRc;
    }



    /**
     * This is called if a satellite has deleted its resource to notify the controller
     * that it can delete the resource.
     *
     * @param nodeNameStr Node name where the resource was deleted.
     * @param rscNameStr Resource name of the deleted resource.
     * @return Apicall response for the call.er
     */
    public ApiCallRc resourceDeleted(
        String nodeNameStr,
        String rscNameStr
    )
    {
        ApiCallRcImpl apiCallRc = new ApiCallRcImpl();

        try (
            AbsApiCallHandler basicallyThis = setContext(
                ApiCallType.DELETE,
                apiCallRc,
                true, // autoClose
                nodeNameStr,
                rscNameStr
            );
        )
        {
            ResourceData rscData = loadRsc(nodeNameStr, rscNameStr, false);

            if (rscData == null)
            {
                addAnswer(
                    getObjectDescriptionInlineFirstLetterCaps() + " not found",
                    ApiConsts.WARN_NOT_FOUND
                );
                throw new ApiCallHandlerFailedException();
            }

            ResourceDefinition rscDfn = rscData.getDefinition();
            Node node = rscData.getAssignedNode();
            UUID rscUuid = rscData.getUuid();

            delete(rscData); // also deletes all of its volumes

            UUID rscDfnUuid = null;
            ResourceName deletedRscDfnName = null;
            UUID nodeUuid = null;
            NodeName deletedNodeName = null;

            // cleanup resource definition if empty and marked for deletion
            if (rscDfn.getResourceCount() == 0)
            {
                // remove primary flag
                errorReporter.logDebug(
                    String.format("Resource definition '%s' empty, deleting primary flag.", rscNameStr)
                );
                rscDfn.getProps(apiCtx).removeProp(InternalApiConsts.PROP_PRIMARY_SET);

                if (isMarkedForDeletion(rscDfn))
                {
                    deletedRscDfnName = rscDfn.getName();
                    rscDfnUuid = rscDfn.getUuid();
                    delete(rscDfn);
                }
            }

            // cleanup node if empty and marked for deletion
            if (node.getResourceCount() == 0 &&
                isMarkedForDeletion(node)
            )
            {
                // TODO check if the remaining storage pools have deployed values left (impl error)


                deletedNodeName = node.getName();
                nodeUuid = node.getUuid();
                delete(node);
            }

            commit();

            reportSuccess(rscUuid);

            if (deletedRscDfnName != null)
            {
                addRscDfnDeletedAnswer(deletedRscDfnName, rscDfnUuid);
                rscDfnMap.remove(deletedRscDfnName);
            }
            if (deletedNodeName != null)
            {
                nodesMap.remove(deletedNodeName);
                node.getPeer(apiCtx).closeConnection();
                addNodeDeletedAnswer(deletedNodeName, nodeUuid);
            }
        }
        catch (ApiCallHandlerFailedException ignore)
        {
            // a report and a corresponding api-response already created. nothing to do here
        }
        catch (Exception | ImplementationError exc)
        {
            reportStatic(
                exc,
                ApiCallType.MODIFY,
                getObjectDescriptionInline(nodeNameStr, rscNameStr),
                getObjRefs(nodeNameStr, rscNameStr),
                getVariables(nodeNameStr, rscNameStr),
                apiCallRc
            );
        }

        return apiCallRc;
    }

    byte[] listResources(
        int msgId,
        List<String> filterNodes,
        List<String> filterResources
    )
    {
        ArrayList<ResourceData.RscApi> rscs = new ArrayList<>();
        Map<NodeName, SatelliteState> satelliteStates = new HashMap<>();
        try
        {
            rscDfnMapProt.requireAccess(peerAccCtx, AccessType.VIEW);
            nodesMapProt.requireAccess(peerAccCtx, AccessType.VIEW);

            final List<String> upperFilterNodes = filterNodes.stream().map(String::toUpperCase).collect(toList());
            final List<String> upperFilterResources =
                filterResources.stream().map(String::toUpperCase).collect(toList());

            rscDfnMap.values().stream()
                .filter(rscDfn -> upperFilterResources.isEmpty() ||
                    upperFilterResources.contains(rscDfn.getName().value))
                .forEach(rscDfn ->
                {
                    try
                    {
                        for (Resource rsc : rscDfn.streamResource(peerAccCtx)
                            .filter(rsc -> upperFilterNodes.isEmpty() ||
                                upperFilterNodes.contains(rsc.getAssignedNode().getName().value))
                            .collect(toList()))
                        {
                            rscs.add(rsc.getApiData(peerAccCtx, null, null));
                            // fullSyncId and updateId null, as they are not going to be serialized anyways
                        }
                    }
                    catch (AccessDeniedException accDeniedExc)
                    {
                        // don't add storpooldfn without access
                    }
                }
                );

            // get resource states of all nodes
            for (final Node node : nodesMap.values())
            {
                final Peer peer = node.getPeer(peerAccCtx);
                if (peer != null)
                {
                    Lock readLock = peer.getSatelliteStateLock().readLock();
                    readLock.lock();
                    try
                    {
                        final SatelliteState satelliteState = peer.getSatelliteState();

                        if (satelliteState != null)
                        {
                            satelliteStates.put(node.getName(), new SatelliteState(satelliteState));
                        }
                    }
                    finally
                    {
                        readLock.unlock();
                    }
                }
            }
        }
        catch (AccessDeniedException accDeniedExc)
        {
            // for now return an empty list.
            errorReporter.reportError(accDeniedExc);
        }

        return clientComSerializer
                .builder(API_LST_RSC, msgId)
                .resourceList(rscs, satelliteStates)
                .build();
    }

    public void respondResource(
        int msgId,
        String nodeNameStr,
        UUID rscUuid,
        String rscNameStr
    )
    {
        try
        {
            NodeName nodeName = new NodeName(nodeNameStr);

            Node node = nodesMap.get(nodeName);

            if (node != null)
            {
                ResourceName rscName = new ResourceName(rscNameStr);
                Resource rsc = !node.isDeleted() ? node.getResource(apiCtx, rscName) : null;

                long fullSyncTimestamp = peer.get().getFullSyncId();
                long updateId = peer.get().getNextSerializerId();
                // TODO: check if the localResource has the same uuid as rscUuid
                if (rsc != null && !rsc.isDeleted())
                {
                    peer.get().sendMessage(
                        internalComSerializer
                            .builder(InternalApiConsts.API_APPLY_RSC, msgId)
                            .resourceData(rsc, fullSyncTimestamp, updateId)
                            .build()
                    );
                }
                else
                {
                    peer.get().sendMessage(
                        internalComSerializer
                        .builder(InternalApiConsts.API_APPLY_RSC_DELETED, msgId)
                        .deletedResourceData(rscNameStr, fullSyncTimestamp, updateId)
                        .build()
                    );
                }
            }
            else
            {
                errorReporter.reportError(
                    new ImplementationError(
                        "Satellite requested resource '" + rscNameStr + "' on node '" + nodeNameStr + "' " +
                            "but that node does not exist.",
                        null
                    )
                );
                peer.get().closeConnection();
            }
        }
        catch (InvalidNameException invalidNameExc)
        {
            errorReporter.reportError(
                new ImplementationError(
                    "Satellite requested data for invalid name (node or rsc name).",
                    invalidNameExc
                )
            );
        }
        catch (AccessDeniedException accDeniedExc)
        {
            errorReporter.reportError(
                new ImplementationError(
                    "Controller's api context has not enough privileges to gather requested resource data.",
                    accDeniedExc
                )
            );
        }
    }

    private AbsApiCallHandler setContext(
        ApiCallType type,
        ApiCallRcImpl apiCallRc,
        boolean autoCloseCurrentTransMgr,
        String nodeNameStr,
        String rscNameStr
    )
    {
        super.setContext(
            type,
            apiCallRc,
            autoCloseCurrentTransMgr,
            getObjRefs(nodeNameStr, rscNameStr),
            getVariables(nodeNameStr, rscNameStr)
        );
        currentNodeName = nodeNameStr;
        currentRscName = rscNameStr;
        return this;
    }

    @Override
    protected String getObjectDescription()
    {
        return "Node: " + currentNodeName + ", Resource: " + currentRscName;
    }

    @Override
    protected String getObjectDescriptionInline()
    {
        return getObjectDescriptionInline(currentNodeName, currentRscName);
    }

    private String getObjectDescriptionInline(String nodeNameStr, String rscNameStr)
    {
        return "resource '" + rscNameStr + "' on node '" + nodeNameStr + "'";
    }

    private Map<String, String> getObjRefs(String nodeNameStr, String rscNameStr)
    {
        Map<String, String> map = new TreeMap<>();
        map.put(ApiConsts.KEY_NODE, nodeNameStr);
        map.put(ApiConsts.KEY_RSC_DFN, rscNameStr);
        return map;
    }

    private Map<String, String> getVariables(String nodeNameStr, String rscNameStr)
    {
        Map<String, String> map = new TreeMap<>();
        map.put(ApiConsts.KEY_NODE_NAME, nodeNameStr);
        map.put(ApiConsts.KEY_RSC_NAME, rscNameStr);
        return map;
    }

    protected final VolumeDefinitionData loadVlmDfn(
        ResourceDefinitionData rscDfn,
        int vlmNr,
        boolean failIfNull
    )
        throws ApiCallHandlerFailedException
    {
        return loadVlmDfn(rscDfn, asVlmNr(vlmNr), failIfNull);
    }

    protected final VolumeDefinitionData loadVlmDfn(
        ResourceDefinitionData rscDfn,
        VolumeNumber vlmNr,
        boolean failIfNull
    )
        throws ApiCallHandlerFailedException
    {
        VolumeDefinitionData vlmDfn;
        try
        {
            vlmDfn = volumeDefinitionDataFactory.load(
                peerAccCtx,
                rscDfn,
                vlmNr
            );

            if (failIfNull && vlmDfn == null)
            {
                String rscName = rscDfn.getName().displayValue;
                throw asExc(
                    null,
                    "Volume definition with number '" + vlmNr.value + "' on resource definition '" +
                        rscName + "' not found.",
                    "The specified volume definition with number '" + vlmNr.value + "' on resource definition '" +
                        rscName + "' could not be found in the database",
                    null, // details
                    "Create a volume definition with number '" + vlmNr.value + "' on resource definition '" +
                        rscName + "' first.",
                    ApiConsts.FAIL_NOT_FOUND_VLM_DFN
                );
            }

        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                "load " + getObjectDescriptionInline(),
                ApiConsts.FAIL_ACC_DENIED_VLM_DFN
            );
        }
        return vlmDfn;
    }

    protected final Props getProps(Resource rsc) throws ApiCallHandlerFailedException
    {
        Props props;
        try
        {
            props = rsc.getProps(peerAccCtx);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                "access properties for resource '" + rsc.getDefinition().getName().displayValue + "' on node '" +
                rsc.getAssignedNode().getName().displayValue + "'.",
                ApiConsts.FAIL_ACC_DENIED_RSC
            );
        }
        return props;
    }

    protected final Props getProps(Volume vlm) throws ApiCallHandlerFailedException
    {
        Props props;
        try
        {
            props = vlm.getProps(peerAccCtx);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                "access properties for volume with number '" + vlm.getVolumeDefinition().getVolumeNumber().value +
                    "' on resource '" + vlm.getResourceDefinition().getName().displayValue + "' " +
                "on node '" + vlm.getResource().getAssignedNode().getName().displayValue + "'.",
                ApiConsts.FAIL_ACC_DENIED_VLM
            );
        }
        return props;
    }

    private void markDeleted(ResourceData rscData)
    {
        try
        {
            rscData.markDeleted(peerAccCtx);
            Iterator<Volume> volumesIterator = rscData.iterateVolumes();
            while (volumesIterator.hasNext())
            {
                Volume vlm = volumesIterator.next();
                vlm.markDeleted(peerAccCtx);
            }
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                "mark " + getObjectDescriptionInline() + " as deleted",
                ApiConsts.FAIL_ACC_DENIED_RSC
            );
        }
        catch (SQLException sqlExc)
        {
            throw asSqlExc(
                sqlExc,
                "marking " + getObjectDescriptionInline() + " as deleted"
            );
        }
    }

    private void delete(ResourceData rscData)
    {
        try
        {
            rscData.delete(peerAccCtx); // also deletes all of its volumes
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                "delete " + getObjectDescriptionInline(),
                ApiConsts.FAIL_ACC_DENIED_RSC
            );
        }
        catch (SQLException sqlExc)
        {
            throw asSqlExc(
                sqlExc,
                "deleting " + getObjectDescriptionInline()
            );
        }
    }

    private void delete(ResourceDefinition rscDfn)
    {
        try
        {
            rscDfn.delete(apiCtx);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asImplError(accDeniedExc);
        }
        catch (SQLException sqlExc)
        {
            throw asSqlExc(
                sqlExc,
                "deleting " + CtrlRscDfnApiCallHandler.getObjectDescriptionInline(rscDfn.getName().displayValue)
            );
        }
    }

    private void delete(Node node)
    {
        try
        {
            node.delete(apiCtx);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asImplError(accDeniedExc);
        }
        catch (SQLException sqlExc)
        {
            throw asSqlExc(
                sqlExc,
                "deleting " + CtrlNodeApiCallHandler.getObjectDescriptionInline(node.getName().displayValue)
            );
        }
    }

    private boolean isMarkedForDeletion(ResourceDefinition rscDfn)
    {
        boolean isMarkedForDeletion;
        try
        {
            isMarkedForDeletion = rscDfn.getFlags().isSet(apiCtx, RscDfnFlags.DELETE);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asImplError(accDeniedExc);
        }
        return isMarkedForDeletion;
    }

    private boolean isMarkedForDeletion(Node node)
    {
        boolean isMarkedForDeletion;
        try
        {
            isMarkedForDeletion = node.getFlags().isSet(apiCtx, NodeFlag.DELETE);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asImplError(accDeniedExc);
        }
        return isMarkedForDeletion;
    }

    private void addRscDfnDeletedAnswer(ResourceName rscName, UUID rscDfnUuid)
    {
        ApiCallRcEntry entry = new ApiCallRcEntry();

        String rscDeletedMsg = CtrlRscDfnApiCallHandler.getObjectDescriptionInline(rscName.displayValue) +
            " deleted.";
        rscDeletedMsg = rscDeletedMsg.substring(0, 1).toUpperCase() + rscDeletedMsg.substring(1);
        entry.setMessageFormat(rscDeletedMsg);
        entry.setReturnCode(ApiConsts.MASK_RSC_DFN | ApiConsts.DELETED);
        entry.putObjRef(ApiConsts.KEY_RSC_DFN, rscName.displayValue);
        entry.putObjRef(ApiConsts.KEY_UUID, rscDfnUuid.toString());
        entry.putVariable(ApiConsts.KEY_RSC_NAME, rscName.displayValue);

        apiCallRc.get().addEntry(entry);
        errorReporter.logInfo(rscDeletedMsg);
    }

    private void addNodeDeletedAnswer(NodeName nodeName, UUID nodeUuid)
    {
        ApiCallRcEntry entry = new ApiCallRcEntry();

        String rscDeletedMsg = CtrlNodeApiCallHandler.getObjectDescriptionInline(nodeName.displayValue) +
            " deleted.";
        entry.setMessageFormat(rscDeletedMsg);
        entry.setReturnCode(ApiConsts.MASK_NODE | ApiConsts.DELETED);
        entry.putObjRef(ApiConsts.KEY_NODE, nodeName.displayValue);
        entry.putObjRef(ApiConsts.KEY_UUID, nodeUuid.toString());
        entry.putVariable(ApiConsts.KEY_NODE_NAME, nodeName.displayValue);

        apiCallRc.get().addEntry(entry);
        errorReporter.logInfo(rscDeletedMsg);
    }

    void updateVolumeData(Peer satellitePeer, String resourceName, List<VlmUpdatePojo> vlmUpdates)
    {
        try
        {
            NodeName nodeName = satellitePeer.getNode().getName();
            ResourceDefinition rscDfn = rscDfnMap.get(new ResourceName(resourceName));
            Resource rsc = rscDfn.getResource(apiCtx, nodeName);

            for (VlmUpdatePojo vlmUpd : vlmUpdates)
            {
                try
                {
                    Volume vlm = rsc.getVolume(new VolumeNumber(vlmUpd.getVolumeNumber()));
                    if (vlm != null)
                    {
                        vlm.setBackingDiskPath(apiCtx, vlmUpd.getBlockDevicePath());
                        vlm.setMetaDiskPath(apiCtx, vlmUpd.getMetaDiskPath());
                    }
                    else
                    {
                        errorReporter.logWarning(
                            String.format(
                                "Tried to update a non existing volume. Node: %s, Resource: %s, VolumeNr: %d",
                                nodeName.displayValue,
                                rscDfn.getName().displayValue,
                                vlmUpd.getVolumeNumber()
                            )
                        );
                    }
                }
                catch (ValueOutOfRangeException ignored)
                {
                }
            }
        }
        catch (InvalidNameException | AccessDeniedException exc)
        {
            throw asImplError(exc);
        }
    }
}
