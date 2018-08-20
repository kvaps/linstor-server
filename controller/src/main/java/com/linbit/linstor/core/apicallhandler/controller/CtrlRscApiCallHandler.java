package com.linbit.linstor.core.apicallhandler.controller;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.ValueOutOfRangeException;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.LinstorParsingUtils;
import com.linbit.linstor.Node;
import com.linbit.linstor.Node.NodeFlag;
import com.linbit.linstor.NodeData;
import com.linbit.linstor.NodeId;
import com.linbit.linstor.NodeName;
import com.linbit.linstor.NodeRepository;
import com.linbit.linstor.Resource;
import com.linbit.linstor.ResourceData;
import com.linbit.linstor.ResourceDefinition;
import com.linbit.linstor.ResourceDefinition.RscDfnFlags;
import com.linbit.linstor.ResourceDefinitionData;
import com.linbit.linstor.ResourceDefinitionRepository;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.Volume;
import com.linbit.linstor.Volume.VlmApi;
import com.linbit.linstor.VolumeData;
import com.linbit.linstor.VolumeDefinition;
import com.linbit.linstor.VolumeDefinitionData;
import com.linbit.linstor.VolumeNumber;
import com.linbit.linstor.annotation.ApiContext;
import com.linbit.linstor.annotation.PeerContext;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiCallRcWith;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.interfaces.serializer.CtrlClientSerializer;
import com.linbit.linstor.api.interfaces.serializer.CtrlStltSerializer;
import com.linbit.linstor.api.pojo.VlmUpdatePojo;
import com.linbit.linstor.api.prop.LinStorObject;
import com.linbit.linstor.core.apicallhandler.response.ApiAccessDeniedException;
import com.linbit.linstor.core.apicallhandler.response.ApiOperation;
import com.linbit.linstor.core.apicallhandler.response.ApiRcException;
import com.linbit.linstor.core.apicallhandler.response.ApiSQLException;
import com.linbit.linstor.core.apicallhandler.response.ApiSuccessUtils;
import com.linbit.linstor.core.apicallhandler.response.ResponseContext;
import com.linbit.linstor.core.apicallhandler.response.ResponseConverter;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.satellitestate.SatelliteResourceState;
import com.linbit.linstor.satellitestate.SatelliteState;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

import static com.linbit.linstor.api.ApiConsts.API_LST_RSC;
import static com.linbit.linstor.core.apicallhandler.controller.CtrlVlmDfnApiCallHandler.getVlmDfnDescriptionInline;
import static com.linbit.utils.StringUtils.firstLetterCaps;
import static java.util.stream.Collectors.toList;

@Singleton
public class CtrlRscApiCallHandler
{
    private final ErrorReporter errorReporter;
    private final AccessContext apiCtx;
    private final CtrlTransactionHelper ctrlTransactionHelper;
    private final CtrlPropsHelper ctrlPropsHelper;
    private final CtrlRscCrtApiHelper ctrlRscCrtApiHelper;
    private final CtrlVlmCrtApiHelper ctrlVlmCrtApiHelper;
    private final CtrlApiDataLoader ctrlApiDataLoader;
    private final ResourceDefinitionRepository resourceDefinitionRepository;
    private final NodeRepository nodeRepository;
    private final CtrlClientSerializer clientComSerializer;
    private final CtrlStltSerializer ctrlStltSerializer;
    private final CtrlSatelliteUpdater ctrlSatelliteUpdater;
    private final ResponseConverter responseConverter;
    private final Provider<Peer> peer;
    private final Provider<AccessContext> peerAccCtx;

    @Inject
    public CtrlRscApiCallHandler(
        ErrorReporter errorReporterRef,
        @ApiContext AccessContext apiCtxRef,
        CtrlTransactionHelper ctrlTransactionHelperRef,
        CtrlPropsHelper ctrlPropsHelperRef,
        CtrlRscCrtApiHelper ctrlRscCrtApiHelperRef,
        CtrlVlmCrtApiHelper ctrlVlmCrtApiHelperRef,
        CtrlApiDataLoader ctrlApiDataLoaderRef,
        ResourceDefinitionRepository resourceDefinitionRepositoryRef,
        NodeRepository nodeRepositoryRef,
        CtrlClientSerializer clientComSerializerRef,
        CtrlStltSerializer ctrlStltSerializerRef,
        CtrlSatelliteUpdater ctrlSatelliteUpdaterRef,
        ResponseConverter responseConverterRef,
        Provider<Peer> peerRef,
        @PeerContext Provider<AccessContext> peerAccCtxRef
    )
    {
        errorReporter = errorReporterRef;
        apiCtx = apiCtxRef;
        ctrlTransactionHelper = ctrlTransactionHelperRef;
        ctrlPropsHelper = ctrlPropsHelperRef;
        ctrlRscCrtApiHelper = ctrlRscCrtApiHelperRef;
        ctrlVlmCrtApiHelper = ctrlVlmCrtApiHelperRef;
        ctrlApiDataLoader = ctrlApiDataLoaderRef;
        resourceDefinitionRepository = resourceDefinitionRepositoryRef;
        nodeRepository = nodeRepositoryRef;
        clientComSerializer = clientComSerializerRef;
        ctrlStltSerializer = ctrlStltSerializerRef;
        ctrlSatelliteUpdater = ctrlSatelliteUpdaterRef;
        responseConverter = responseConverterRef;
        peer = peerRef;
        peerAccCtx = peerAccCtxRef;
    }

    /**
     * This method really creates the resource and its volumes.
     *
     * This method does NOT:
     * * commit any transaction
     * * update satellites
     * * create success-apiCallRc entries (only error RC in case of exception)
     *
     * @param nodeNameStr
     * @param rscNameStr
     * @param flagList
     * @param rscPropsMap
     * @param vlmApiList
     * @param nodeIdInt
     *
     * @return the newly created resource
     */
    public ApiCallRcWith<ResourceData> createResourceDb(
        String nodeNameStr,
        String rscNameStr,
        List<String> flagList,
        Map<String, String> rscPropsMap,
        List<VlmApi> vlmApiList,
        Integer nodeIdInt
    )
    {
        ApiCallRcImpl responses = new ApiCallRcImpl();

        NodeData node = ctrlApiDataLoader.loadNode(nodeNameStr, true);
        ResourceDefinitionData rscDfn = ctrlApiDataLoader.loadRscDfn(rscNameStr, true);

        NodeId nodeId = resolveNodeId(nodeIdInt, rscDfn);

        ResourceData rsc = ctrlRscCrtApiHelper.createResource(rscDfn, node, nodeId, flagList);
        Props rscProps = ctrlPropsHelper.getProps(rsc);

        ctrlPropsHelper.fillProperties(LinStorObject.RESOURCE, rscPropsMap, rscProps, ApiConsts.FAIL_ACC_DENIED_RSC);

        Props rscDfnProps = ctrlPropsHelper.getProps(rscDfn);
        Props nodeProps = ctrlPropsHelper.getProps(node);

        for (VlmApi vlmApi : vlmApiList)
        {
            VolumeDefinitionData vlmDfn = loadVlmDfn(rscDfn, vlmApi.getVlmNr(), true);

            VolumeData vlmData = ctrlVlmCrtApiHelper.createVolumeResolvingStorPool(
                rsc, vlmDfn, vlmApi.getBlockDevice(), vlmApi.getMetaDisk()
            ).extractApiCallRc(responses);

            Props vlmProps = ctrlPropsHelper.getProps(vlmData);

            ctrlPropsHelper.fillProperties(
                LinStorObject.VOLUME, vlmApi.getVlmProps(), vlmProps, ApiConsts.FAIL_ACC_DENIED_VLM);
        }

        Iterator<VolumeDefinition> iterateVolumeDfn = ctrlRscCrtApiHelper.getVlmDfnIterator(rscDfn);
        while (iterateVolumeDfn.hasNext())
        {
            VolumeDefinition vlmDfn = iterateVolumeDfn.next();

            // first check if we probably just deployed a vlm for this vlmDfn
            if (rsc.getVolume(vlmDfn.getVolumeNumber()) == null)
            {
                // not deployed yet.

                VolumeData vlm = ctrlVlmCrtApiHelper.createVolumeResolvingStorPool(rsc, vlmDfn)
                    .extractApiCallRc(responses);
            }
        }
        return new ApiCallRcWith<>(responses, rsc);
    }

    private NodeId resolveNodeId(Integer nodeIdInt, ResourceDefinitionData rscDfn)
    {
        NodeId nodeId;

        if (nodeIdInt == null)
        {
            nodeId = ctrlRscCrtApiHelper.getNextFreeNodeId(rscDfn);
        }
        else
        {
            try
            {
                NodeId requestedNodeId = new NodeId(nodeIdInt);

                Iterator<Resource> rscIterator = rscDfn.iterateResource(peerAccCtx.get());
                while (rscIterator.hasNext())
                {
                    if (requestedNodeId.equals(rscIterator.next().getNodeId()))
                    {
                        throw new ApiRcException(ApiCallRcImpl.simpleEntry(
                            ApiConsts.FAIL_INVLD_NODE_ID,
                            "The specified node ID is already in use."
                        ));
                    }
                }

                nodeId = requestedNodeId;
            }
            catch (AccessDeniedException accDeniedExc)
            {
                throw new ApiAccessDeniedException(
                    accDeniedExc,
                    "iterate the resources of resource definition '" + rscDfn.getName().displayValue + "'",
                    ApiConsts.FAIL_ACC_DENIED_RSC_DFN
                );
            }
            catch (ValueOutOfRangeException outOfRangeExc)
            {
                throw new ApiRcException(ApiCallRcImpl.simpleEntry(
                    ApiConsts.FAIL_INVLD_NODE_ID,
                    "The specified node ID is out of range."
                ), outOfRangeExc);
            }
        }

        return nodeId;
    }

    public ApiCallRc modifyResource(
        UUID rscUuid,
        String nodeNameStr,
        String rscNameStr,
        Map<String, String> overrideProps,
        Set<String> deletePropKeys
    )
    {
        ApiCallRcImpl responses = new ApiCallRcImpl();
        ResponseContext context = makeRscContext(
            ApiOperation.makeModifyOperation(),
            nodeNameStr,
            rscNameStr
        );

        try
        {
            ResourceData rsc = ctrlApiDataLoader.loadRsc(nodeNameStr, rscNameStr, true);

            if (rscUuid != null && !rscUuid.equals(rsc.getUuid()))
            {
                throw new ApiRcException(ApiCallRcImpl.simpleEntry(
                    ApiConsts.FAIL_UUID_RSC,
                    "UUID-check failed"
                ));
            }

            Props props = ctrlPropsHelper.getProps(rsc);
            Map<String, String> propsMap = props.map();

            ctrlPropsHelper.fillProperties(LinStorObject.RESOURCE, overrideProps, props, ApiConsts.FAIL_ACC_DENIED_RSC);

            for (String delKey : deletePropKeys)
            {
                propsMap.remove(delKey);
            }

            ctrlTransactionHelper.commit();

            responseConverter.addWithDetail(responses, context, ctrlSatelliteUpdater.updateSatellites(rsc));
            responseConverter.addWithOp(responses, context, ApiSuccessUtils.defaultModifiedEntry(
                rsc.getUuid(), getRscDescriptionInline(rsc)));
        }
        catch (Exception | ImplementationError exc)
        {
            responses = responseConverter.reportException(peer.get(), context, exc);
        }

        return responses;
    }

    public ApiCallRc deleteResource(
        String nodeNameStr,
        String rscNameStr
    )
    {
        ApiCallRcImpl responses = new ApiCallRcImpl();
        ResponseContext context = makeRscContext(
            ApiOperation.makeDeleteOperation(),
            nodeNameStr,
            rscNameStr
        );

        try
        {
            ResourceData rscData = ctrlApiDataLoader.loadRsc(nodeNameStr, rscNameStr, true);

            SatelliteState stltState = rscData.getAssignedNode().getPeer(apiCtx).getSatelliteState();
            SatelliteResourceState rscState = stltState.getResourceStates().get(rscData.getDefinition().getName());

            if (rscState != null && rscState.isInUse() != null && rscState.isInUse())
            {
                responseConverter.addWithOp(responses, context, ApiCallRcImpl
                    .entryBuilder(
                        ApiConsts.FAIL_IN_USE,
                        String.format("Resource '%s' is still in use.", rscNameStr)
                    )
                    .setCause("Resource is mounted/in use.")
                    .setCorrection(String.format("Un-mount resource '%s' on the node '%s'.", rscNameStr, nodeNameStr))
                    .build()
                );
            }
            else
            {
                int volumeCount = rscData.getVolumeCount();
                String successMessage;
                String details;
                String descriptionFirstLetterCaps = firstLetterCaps(getRscDescription(nodeNameStr, rscNameStr));
                if (volumeCount > 0)
                {
                    successMessage = descriptionFirstLetterCaps + " marked for deletion.";
                    details = descriptionFirstLetterCaps + " UUID is: " + rscData.getUuid();
                    markDeleted(rscData);
                }
                else
                {
                    successMessage = descriptionFirstLetterCaps + " deleted.";
                    details = descriptionFirstLetterCaps + " UUID was: " + rscData.getUuid();
                    delete(rscData);
                }

                ctrlTransactionHelper.commit();

                if (volumeCount > 0)
                {
                    // notify satellites
                    responseConverter.addWithDetail(responses, context, ctrlSatelliteUpdater.updateSatellites(rscData));
                }

                responseConverter.addWithOp(responses, context,
                    ApiCallRcImpl.entryBuilder(ApiConsts.DELETED, successMessage).setDetails(details).build());
            }
        }
        catch (Exception | ImplementationError exc)
        {
            responses = responseConverter.reportException(peer.get(), context, exc);
        }

        return responses;
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
        ApiCallRcImpl responses = new ApiCallRcImpl();
        ResponseContext context = makeRscContext(
            ApiOperation.makeDeleteOperation(),
            nodeNameStr,
            rscNameStr
        );

        try
        {
            ResourceData rscData = ctrlApiDataLoader.loadRsc(nodeNameStr, rscNameStr, false);

            if (rscData == null)
            {
                throw new ApiRcException(ApiCallRcImpl.simpleEntry(
                    ApiConsts.WARN_NOT_FOUND,
                    firstLetterCaps(getRscDescription(nodeNameStr, rscNameStr)) + " not found"
                ));
            }

            ResourceDefinition rscDfn = rscData.getDefinition();
            Node node = rscData.getAssignedNode();
            UUID rscUuid = rscData.getUuid();

            delete(rscData); // also deletes all of its volumes

            UUID rscDfnUuid = null;
            ResourceName deletedRscDfnName = null;
            UUID nodeUuid = null;
            NodeName deletedNodeName = null;

            // check if only diskfull resources are left in the rscDfn that is to be deleted
            // and if so, mark the diskfull as deleted
            if (!rscDfn.hasDiskless(apiCtx) && isMarkedForDeletion(rscDfn))
            {
                for (Resource rsc : rscDfn.streamResource(apiCtx).collect(toList()))
                {
                    rsc.markDeleted(apiCtx);
                }
            }

            // cleanup resource definition if empty and marked for deletion
            if (rscDfn.getResourceCount() == 0)
            {
                if (isMarkedForDeletion(rscDfn))
                {
                    deletedRscDfnName = rscDfn.getName();
                    rscDfnUuid = rscDfn.getUuid();
                    delete(rscDfn);
                }
                else
                {
                    // remove primary flag
                    errorReporter.logDebug(
                        String.format("Resource definition '%s' empty, deleting primary flag.", rscNameStr)
                    );
                    rscDfn.getProps(apiCtx).removeProp(InternalApiConsts.PROP_PRIMARY_SET);
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

            ctrlTransactionHelper.commit();

            responseConverter.addWithOp(responses, context, ApiSuccessUtils.defaultDeletedEntry(
                rscUuid, getRscDescriptionInline(nodeNameStr, rscNameStr)));

            if (deletedRscDfnName != null)
            {
                responseConverter.addWithOp(
                    responses, context, makeRscDfnDeletedResponse(deletedRscDfnName, rscDfnUuid));
                resourceDefinitionRepository.remove(apiCtx, deletedRscDfnName);
            }
            if (deletedNodeName != null)
            {
                nodeRepository.remove(apiCtx, deletedNodeName);
                node.getPeer(apiCtx).closeConnection();
                responseConverter.addWithOp(responses, context, makeNodeDeletedResponse(deletedNodeName, nodeUuid));
            }
        }
        catch (Exception | ImplementationError exc)
        {
            responses = responseConverter.reportException(peer.get(), context, exc);
        }

        return responses;
    }

    byte[] listResources(
        long apiCallId,
        List<String> filterNodes,
        List<String> filterResources
    )
    {
        ArrayList<ResourceData.RscApi> rscs = new ArrayList<>();
        Map<NodeName, SatelliteState> satelliteStates = new HashMap<>();
        try
        {
            final List<String> upperFilterNodes = filterNodes.stream().map(String::toUpperCase).collect(toList());
            final List<String> upperFilterResources =
                filterResources.stream().map(String::toUpperCase).collect(toList());

            resourceDefinitionRepository.getMapForView(peerAccCtx.get()).values().stream()
                .filter(rscDfn -> upperFilterResources.isEmpty() ||
                    upperFilterResources.contains(rscDfn.getName().value))
                .forEach(rscDfn ->
                {
                    try
                    {
                        for (Resource rsc : rscDfn.streamResource(peerAccCtx.get())
                            .filter(rsc -> upperFilterNodes.isEmpty() ||
                                upperFilterNodes.contains(rsc.getAssignedNode().getName().value))
                            .collect(toList()))
                        {
                            rscs.add(rsc.getApiData(peerAccCtx.get(), null, null));
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
            for (final Node node : nodeRepository.getMapForView(peerAccCtx.get()).values())
            {
                if (upperFilterNodes.isEmpty() || upperFilterNodes.contains(node.getName().value))
                {
                    final Peer peer = node.getPeer(peerAccCtx.get());
                    if (peer != null)
                    {
                        Lock readLock = peer.getSatelliteStateLock().readLock();
                        readLock.lock();
                        try
                        {
                            final SatelliteState satelliteState = peer.getSatelliteState();

                            if (satelliteState != null)
                            {
                                final SatelliteState filterStates = new SatelliteState(satelliteState);

                                // states are already complete, we remove all resource that are not interesting from
                                // our clone
                                Set<ResourceName> removeSet = new TreeSet<>();
                                for (ResourceName rscName : filterStates.getResourceStates().keySet())
                                {
                                    if (!(upperFilterResources.isEmpty() ||
                                          upperFilterResources.contains(rscName.value)))
                                    {
                                        removeSet.add(rscName);
                                    }
                                }
                                removeSet.forEach(rscName -> filterStates.getResourceStates().remove(rscName));
                                satelliteStates.put(node.getName(), filterStates);
                            }
                        }
                        finally
                        {
                            readLock.unlock();
                        }
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
                .answerBuilder(API_LST_RSC, apiCallId)
                .resourceList(rscs, satelliteStates)
                .build();
    }

    public void respondResource(
        long apiCallId,
        String nodeNameStr,
        UUID rscUuid,
        String rscNameStr
    )
    {
        try
        {
            NodeName nodeName = new NodeName(nodeNameStr);

            Node node = nodeRepository.get(apiCtx, nodeName);

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
                        ctrlStltSerializer
                            .onewayBuilder(InternalApiConsts.API_APPLY_RSC)
                            .resourceData(rsc, fullSyncTimestamp, updateId)
                            .build()
                    );
                }
                else
                {
                    peer.get().sendMessage(
                        ctrlStltSerializer
                            .onewayBuilder(InternalApiConsts.API_APPLY_RSC_DELETED)
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

    private VolumeDefinitionData loadVlmDfn(
        ResourceDefinitionData rscDfn,
        int vlmNr,
        boolean failIfNull
    )
    {
        return loadVlmDfn(rscDfn, LinstorParsingUtils.asVlmNr(vlmNr), failIfNull);
    }

    private VolumeDefinitionData loadVlmDfn(
        ResourceDefinitionData rscDfn,
        VolumeNumber vlmNr,
        boolean failIfNull
    )
    {
        VolumeDefinitionData vlmDfn;
        try
        {
            vlmDfn = (VolumeDefinitionData) rscDfn.getVolumeDfn(peerAccCtx.get(), vlmNr);

            if (failIfNull && vlmDfn == null)
            {
                String rscName = rscDfn.getName().displayValue;
                throw new ApiRcException(ApiCallRcImpl
                    .entryBuilder(
                        ApiConsts.FAIL_NOT_FOUND_VLM_DFN,
                        "Volume definition with number '" + vlmNr.value + "' on resource definition '" +
                            rscName + "' not found."
                    )
                    .setCause("The specified volume definition with number '" + vlmNr.value + "' on resource definition '" +
                        rscName + "' could not be found in the database")
                    .setCorrection("Create a volume definition with number '" + vlmNr.value + "' on resource definition '" +
                        rscName + "' first.")
                    .build()
                );
            }

        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "load " + getVlmDfnDescriptionInline(rscDfn.getName().displayValue, vlmNr.value),
                ApiConsts.FAIL_ACC_DENIED_VLM_DFN
            );
        }
        return vlmDfn;
    }

    private void markDeleted(ResourceData rscData)
    {
        try
        {
            rscData.markDeleted(peerAccCtx.get());
            Iterator<Volume> volumesIterator = rscData.iterateVolumes();
            while (volumesIterator.hasNext())
            {
                Volume vlm = volumesIterator.next();
                vlm.markDeleted(peerAccCtx.get());
            }
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "mark " + getRscDescription(rscData) + " as deleted",
                ApiConsts.FAIL_ACC_DENIED_RSC
            );
        }
        catch (SQLException sqlExc)
        {
            throw new ApiSQLException(sqlExc);
        }
    }

    private void delete(ResourceData rscData)
    {
        try
        {
            rscData.delete(peerAccCtx.get()); // also deletes all of its volumes
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "delete " + getRscDescription(rscData),
                ApiConsts.FAIL_ACC_DENIED_RSC
            );
        }
        catch (SQLException sqlExc)
        {
            throw new ApiSQLException(sqlExc);
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
            throw new ImplementationError(accDeniedExc);
        }
        catch (SQLException sqlExc)
        {
            throw new ApiSQLException(sqlExc);
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
            throw new ImplementationError(accDeniedExc);
        }
        catch (SQLException sqlExc)
        {
            throw new ApiSQLException(sqlExc);
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
            throw new ImplementationError(accDeniedExc);
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
            throw new ImplementationError(accDeniedExc);
        }
        return isMarkedForDeletion;
    }

    private ApiCallRc.RcEntry makeRscDfnDeletedResponse(ResourceName rscName, UUID rscDfnUuid)
    {
        String rscDeletedMsg = CtrlRscDfnApiCallHandler.getRscDfnDescriptionInline(rscName.displayValue) +
            " deleted.";
        rscDeletedMsg = rscDeletedMsg.substring(0, 1).toUpperCase() + rscDeletedMsg.substring(1);

        errorReporter.logInfo(rscDeletedMsg);

        return ApiCallRcImpl.entryBuilder(ApiConsts.MASK_RSC_DFN | ApiConsts.DELETED, rscDeletedMsg)
            .putObjRef(ApiConsts.KEY_RSC_DFN, rscName.displayValue)
            .putObjRef(ApiConsts.KEY_UUID, rscDfnUuid.toString())
            .build();
    }

    private ApiCallRc.RcEntry makeNodeDeletedResponse(NodeName nodeName, UUID nodeUuid)
    {
        String rscDeletedMsg = CtrlNodeApiCallHandler.getNodeDescriptionInline(nodeName.displayValue) +
            " deleted.";

        errorReporter.logInfo(rscDeletedMsg);

        return ApiCallRcImpl.entryBuilder(ApiConsts.MASK_NODE | ApiConsts.DELETED, rscDeletedMsg)
            .putObjRef(ApiConsts.KEY_NODE, nodeName.displayValue)
            .putObjRef(ApiConsts.KEY_UUID, nodeUuid.toString())
            .build();
    }

    void updateVolumeData(String resourceName, List<VlmUpdatePojo> vlmUpdates)
    {
        try
        {
            NodeName nodeName = peer.get().getNode().getName();
            ResourceDefinition rscDfn = resourceDefinitionRepository.get(apiCtx, new ResourceName(resourceName));
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
            throw new ImplementationError(exc);
        }
    }

    public static String getRscDescription(Resource resource)
    {
        return getRscDescription(
            resource.getAssignedNode().getName().displayValue, resource.getDefinition().getName().displayValue);
    }

    public static String getRscDescription(String nodeNameStr, String rscNameStr)
    {
        return "Node: " + nodeNameStr + ", Resource: " + rscNameStr;
    }

    public static String getRscDescriptionInline(Resource rsc)
    {
        return getRscDescriptionInline(rsc.getAssignedNode(), rsc.getDefinition());
    }

    public static String getRscDescriptionInline(Node node, ResourceDefinition rscDfn)
    {
        return getRscDescriptionInline(node.getName().displayValue, rscDfn.getName().displayValue);
    }

    public static String getRscDescriptionInline(String nodeNameStr, String rscNameStr)
    {
        return "resource '" + rscNameStr + "' on node '" + nodeNameStr + "'";
    }

    static ResponseContext makeRscContext(
        ApiOperation operation,
        String nodeNameStr,
        String rscNameStr
    )
    {
        Map<String, String> objRefs = new TreeMap<>();
        objRefs.put(ApiConsts.KEY_NODE, nodeNameStr);
        objRefs.put(ApiConsts.KEY_RSC_DFN, rscNameStr);

        return new ResponseContext(
            operation,
            getRscDescription(nodeNameStr, rscNameStr),
            getRscDescriptionInline(nodeNameStr, rscNameStr),
            ApiConsts.MASK_RSC,
            objRefs
        );
    }
}
