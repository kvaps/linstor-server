package com.linbit.linstor.core;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;

import com.linbit.ChildProcessTimeoutException;
import com.linbit.ImplementationError;
import com.linbit.extproc.ExtCmd;
import com.linbit.linstor.ConfFileBuilder;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.Node;
import com.linbit.linstor.ResourceDefinition;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.StorPoolDefinition;
import com.linbit.linstor.annotation.ApiContext;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.ApiModule;
import com.linbit.linstor.api.interfaces.serializer.CtrlStltSerializer;
import com.linbit.linstor.api.pojo.NodePojo;
import com.linbit.linstor.api.pojo.RscPojo;
import com.linbit.linstor.api.pojo.SnapshotPojo;
import com.linbit.linstor.api.pojo.StorPoolPojo;
import com.linbit.linstor.api.prop.WhitelistProps;
import com.linbit.linstor.api.prop.WhitelistPropsReconfigurator;
import com.linbit.linstor.core.StltStorPoolApiCallHandler.ChangedData;
import com.linbit.linstor.event.EventIdentifier;
import com.linbit.linstor.event.EventBroker;
import com.linbit.linstor.event.Watch;
import com.linbit.linstor.logging.ErrorReport;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.logging.StdErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.timer.CoreTimer;
import com.linbit.linstor.transaction.TransactionMgr;
import com.linbit.utils.LockSupport;
import org.slf4j.event.Level;

import static com.linbit.linstor.core.SatelliteCoreModule.SATELLITE_PROPS;

@Singleton
public class StltApiCallHandler
{
    private final ErrorReporter errorReporter;
    private final AccessContext apiCtx;

    private final CoreTimer timer;
    private final ControllerPeerConnector controllerPeerConnector;
    private final UpdateMonitor updateMonitor;
    private final DeviceManager deviceManager;
    private final ApplicationLifecycleManager applicationLifecycleManager;

    private final StltNodeApiCallHandler nodeHandler;
    private final StltRscDfnApiCallHandler rscDfnHandler;
    private final StltRscApiCallHandler rscHandler;
    private final StltStorPoolApiCallHandler storPoolHandler;
    private final StltSnapshotApiCallHandler snapshotHandler;

    private final CtrlStltSerializer interComSerializer;

    private final ReadWriteLock reconfigurationLock;
    private final ReadWriteLock nodesMapLock;
    private final ReadWriteLock rscDfnMapLock;
    private final ReadWriteLock storPoolDfnMapLock;
    private final CoreModule.NodesMap nodesMap;
    private final CoreModule.ResourceDefinitionMap rscDfnMap;
    private final CoreModule.StorPoolDefinitionMap storPoolDfnMap;

    private final TreeMap<Long, ApplyData> dataToApply;

    private final Provider<TransactionMgr> transMgrProvider;
    private final StltSecurityObjects stltSecObj;
    private final StltVlmDfnApiCallHandler vlmDfnHandler;
    private final Props stltConf;
    private final EventBroker eventBroker;

    private WhitelistPropsReconfigurator whiteListPropsReconfigurator;
    private WhitelistProps whitelistProps;

    private final Provider<Integer> msgId;

    @Inject
    public StltApiCallHandler(
        ErrorReporter errorReporterRef,
        @ApiContext AccessContext apiCtxRef,
        CoreTimer timerRef,
        ControllerPeerConnector controllerPeerConnectorRef,
        UpdateMonitor updateMonitorRef,
        DeviceManager deviceManagerRef,
        ApplicationLifecycleManager applicationLifecycleManagerRef,
        StltNodeApiCallHandler nodeHandlerRef,
        StltRscDfnApiCallHandler rscDfnHandlerRef,
        StltRscApiCallHandler rscHandlerRef,
        StltStorPoolApiCallHandler storPoolHandlerRef,
        StltSnapshotApiCallHandler snapshotHandlerRef,
        CtrlStltSerializer interComSerializerRef,
        @Named(CoreModule.RECONFIGURATION_LOCK) ReadWriteLock reconfigurationLockRef,
        @Named(CoreModule.NODES_MAP_LOCK) ReadWriteLock nodesMapLockRef,
        @Named(CoreModule.RSC_DFN_MAP_LOCK) ReadWriteLock rscDfnMapLockRef,
        @Named(CoreModule.STOR_POOL_DFN_MAP_LOCK) ReadWriteLock storPoolDfnMapLockRef,
        @Named(SATELLITE_PROPS) Props satellitePropsRef,
        CoreModule.NodesMap nodesMapRef,
        CoreModule.ResourceDefinitionMap rscDfnMapRef,
        CoreModule.StorPoolDefinitionMap storPoolDfnMapRef,
        Provider<TransactionMgr> transMgrProviderRef,
        StltSecurityObjects stltSecObjRef,
        StltVlmDfnApiCallHandler vlmDfnHandlerRef,
        EventBroker eventBrokerRef,
        WhitelistProps whiteListPropsRef,
        WhitelistPropsReconfigurator whiteListPropsReconfiguratorRef,
        @Named(ApiModule.MSG_ID) Provider<Integer> msgIdRef
    )
    {
        errorReporter = errorReporterRef;
        apiCtx = apiCtxRef;
        timer = timerRef;
        controllerPeerConnector = controllerPeerConnectorRef;
        updateMonitor = updateMonitorRef;
        deviceManager = deviceManagerRef;
        applicationLifecycleManager = applicationLifecycleManagerRef;
        nodeHandler = nodeHandlerRef;
        rscDfnHandler = rscDfnHandlerRef;
        rscHandler = rscHandlerRef;
        storPoolHandler = storPoolHandlerRef;
        snapshotHandler = snapshotHandlerRef;
        interComSerializer = interComSerializerRef;
        reconfigurationLock = reconfigurationLockRef;
        nodesMapLock = nodesMapLockRef;
        rscDfnMapLock = rscDfnMapLockRef;
        storPoolDfnMapLock = storPoolDfnMapLockRef;
        nodesMap = nodesMapRef;
        rscDfnMap = rscDfnMapRef;
        storPoolDfnMap = storPoolDfnMapRef;
        transMgrProvider = transMgrProviderRef;
        stltSecObj = stltSecObjRef;
        vlmDfnHandler = vlmDfnHandlerRef;
        stltConf = satellitePropsRef;
        eventBroker = eventBrokerRef;
        whitelistProps = whiteListPropsRef;
        whiteListPropsReconfigurator = whiteListPropsReconfiguratorRef;
        msgId = msgIdRef;

        dataToApply = new TreeMap<>();
    }

    public ApiCallRcImpl authenticate(
        UUID nodeUuid,
        String nodeName,
        UUID disklessStorPoolDfnUuid,
        UUID disklessStorPoolUuid,
        Peer controllerPeer
    )
        throws IOException
    {
        ApiCallRcImpl apiCallRc = null;

        // get satellites current hostname
        final ExtCmd extCommand = new ExtCmd(timer, errorReporter);
        String hostName = "";
        try
        {
            final ExtCmd.OutputData output = extCommand.exec("uname", "-n");
            final String stdOut = new String(output.stdoutData);
            hostName = stdOut.trim();
        }
        catch (ChildProcessTimeoutException ex)
        {
            errorReporter.reportError(ex);
        }

        // Check if satellite hostname is equal to the given nodename
        if (hostName == null || !hostName.toLowerCase().equals(nodeName))
        {
            ApiCallRcImpl.ApiCallRcEntry entry = new ApiCallRcImpl.ApiCallRcEntry();
            entry.setReturnCode(InternalApiConsts.API_AUTH_ERROR_HOST_MISMATCH);
            entry.setMessageFormat("Satellite node name doesn't match hostname.");
            String cause = String.format(
                "Satellite node name '%s' doesn't match nodes hostname '%s'.",
                nodeName,
                hostName
            );
            entry.setCauseFormat(cause);
            apiCallRc = new ApiCallRcImpl();
            apiCallRc.addEntry(entry);

            errorReporter.logError(cause);
        }
        else
        {
            synchronized (dataToApply)
            {
                dataToApply.clear(); // controller should not have sent us anything before the authentication.
                // that means, everything in this map is out-dated data + we should receive a full sync next.
            }

            controllerPeerConnector.setControllerPeer(
                controllerPeer,
                nodeUuid,
                nodeName,
                disklessStorPoolDfnUuid,
                disklessStorPoolUuid
            );

            // FIXME In the absence of any means of identification, assume the identity of the privileged API context
            // for the peer.
            AccessContext curCtx = controllerPeer.getAccessContext();
            try
            {
                AccessContext newCtx = apiCtx.impersonate(
                    apiCtx.subjectId, curCtx.subjectRole, curCtx.subjectDomain
                );
                controllerPeer.setAccessContext(apiCtx, newCtx);
            }
            catch (AccessDeniedException accExc)
            {
                errorReporter.reportError(
                    Level.ERROR,
                    new ImplementationError(
                        "Creation of an access context for a Controller by the " +
                        apiCtx.subjectRole.name.displayValue + " role failed",
                        accExc
                    )
                );
            }
            errorReporter.logInfo("Controller connected and authenticated");
        }

        return apiCallRc;
    }

    public void applyFullSync(
        Map<String, String> satelliteProps,
        Set<NodePojo> nodes,
        Set<StorPoolPojo> storPools,
        Set<RscPojo> resources,
        Set<SnapshotPojo> snapshots,
        long fullSyncId,
        byte[] cryptKey
    )
    {
        try (
            LockSupport ls = LockSupport.lock(
                reconfigurationLock.writeLock(),
                nodesMapLock.writeLock(),
                rscDfnMapLock.writeLock(),
                storPoolDfnMapLock.writeLock()
            )
        )
        {
            if (updateMonitor.getCurrentFullSyncId() == fullSyncId)
            {
                // only apply this fullSync if it is newer than the last one

                // clear all data
                nodesMap.clear();
                rscDfnMap.clear();
                storPoolDfnMap.clear();

                doApplyControllerChanges(satelliteProps);

                for (NodePojo node : nodes)
                {
                    Node curNode = nodeHandler.applyChanges(node);
                    if (curNode != null)
                    {
                        nodesMap.put(curNode.getName(), curNode);
                    }
                }
                controllerPeerConnector.setControllerPeerToCurrentLocalNode();

                for (StorPoolPojo storPool : storPools)
                {
                    ChangedData appliedChanges = storPoolHandler.applyChanges(storPool);
                    StorPoolDefinition storPoolDfnToRegister = appliedChanges.storPoolDfnToRegister;
                    if (storPoolDfnToRegister != null)
                    {
                        storPoolDfnMap.put(
                            storPoolDfnToRegister.getName(),
                            storPoolDfnToRegister
                        );
                    }
                }

                for (RscPojo rsc : resources)
                {
                    rscHandler.applyChanges(rsc);
                }

                for (SnapshotPojo snapshot : snapshots)
                {
                    snapshotHandler.applyChanges(snapshot);
                }

                transMgrProvider.get().commit();

                for (NodePojo node : nodes)
                {
                    errorReporter.logTrace("Node '" + node.getName() + "' received from Controller.");
                }
                for (StorPoolPojo storPool : storPools)
                {
                    errorReporter.logTrace(
                        "StorPool '" + storPool.getStorPoolName() + "' received from Controller."
                    );
                }
                for (RscPojo rsc : resources)
                {
                    errorReporter.logTrace("Resource '" + rsc.getName() + "' created.");
                }
                for (SnapshotPojo snapshot : snapshots)
                {
                    errorReporter.logTrace("Snapshot '" + snapshot.getSnaphotDfn() + "' created.");
                }
                errorReporter.logTrace("Full sync with controller finished");

                // Atomically notify the DeviceManager to check all resources
                Node localNode = controllerPeerConnector.getLocalNode();
                if (localNode != null)
                {
                    if (deviceManager != null)
                    {
                        deviceManager.fullSyncApplied();
                    }
                }
                else
                {
                    errorReporter.logWarning(
                        "No node object that represents this satellite was received from the controller"
                    );
                }

                if (cryptKey != null && cryptKey.length > 0)
                {
                    stltSecObj.setCryptKey(cryptKey);

                    vlmDfnHandler.decryptAllVlmDfnKeys();
                }

                whiteListPropsReconfigurator.reconfigure();

                updateMonitor.setFullSyncApplied();

                // There are no explicit controller - satellite watches.
                // FullSync implicitly creates a watch for all events.
                createWatchForPeer();
            }
            else
            {
                errorReporter.logWarning(
                    "Ignored an incoming but outdated fullsync"
                );
            }
        }
        catch (Exception | ImplementationError exc)
        {
            errorReporter.reportError(exc);

            Peer controllerPeer = controllerPeerConnector.getControllerPeer();
            controllerPeer.sendMessage(
                interComSerializer.builder(InternalApiConsts.API_FULL_SYNC_FAILED, 0)
                    .build()
            );

            // sending this message should tell the controller to not send us any further data, as
            // updates would be based on an invalid fullSync, and receiving this fullSync again
            // would most likely cause the same exception as now.

            // however, in order to avoid implementation errors of the controller, we additionally
            // increase the fullSyncId but not telling the controller about it.
            // even if the controller still sends us data, we will ignore them as they will look like
            // "out-dated" data.
            // when recreating the connection, and the controller is positive to send us an authentication
            // message, we will again increase the fullSyncId and expect the fullSync from the controller.

            // in other words: if this exception happens, either the controller or this satellite has
            // to drop the connection (e.g. restart) in order to re-enable applying fullSyncs.
            updateMonitor.getNextFullSyncId();
        }
    }

    private void createWatchForPeer()
    {
        Peer controllerPeer = controllerPeerConnector.getControllerPeer();
        eventBroker.createWatch(new Watch(
            UUID.randomUUID(), controllerPeer.getId(), 0, EventIdentifier.global(null)
        ));
    }

    public void applyControllerChanges(
        Map<String, String> satelliteProps,
        long fullSyncId,
        long updateId
    )
    {
        applyChangedData(new ApplyControllerData(satelliteProps, fullSyncId, updateId));
    }

    private void doApplyControllerChanges(Map<String, String> satelliteProps)
    {
        try
        {
            stltConf.clear();
            stltConf.map().putAll(satelliteProps);

            transMgrProvider.get().commit();

            try (
                FileOutputStream commonFileOut = new FileOutputStream(
                    SatelliteCoreModule.CONFIG_PATH + "/linstor_common.conf"
                )
            )
            {
                ConfFileBuilder confFileBuilder = new ConfFileBuilder(
                    errorReporter,
                    whitelistProps
                );
                commonFileOut.write(confFileBuilder.buildCommonConf(stltConf).getBytes());
            }
            catch (IOException ioExc)
            {
                String ioErrorMsg = ioExc.getMessage();
                if (ioErrorMsg == null)
                {
                    ioErrorMsg = "The runtime environment or operating system did not provide a " +
                        "description of the I/O error";
                }

                errorReporter.reportError(
                    new DrbdDeviceHandler.ResourceException(
                        "Creation of the common Linstor DRBD configuration file " +
                        "'linstor_common.conf' failed due to an I/O error",
                        null,
                        "Creation of the DRBD configuration file failed due to an I/O error",
                        "- Check whether enough free space is available for the creation of the file\n" +
                            "- Check whether the application has write access to the target directory\n" +
                            "- Check whether the storage is operating flawlessly",
                        "The error reported by the runtime environment or operating system is:\n" + ioErrorMsg,
                        ioExc
                    )
                );
            }

            Set<ResourceName> slctRsc = new TreeSet<>();
            for (ResourceDefinition curRscDfn : rscDfnMap.values())
            {
                slctRsc.add(curRscDfn.getName());
            }

            deviceManager.getUpdateTracker().checkMultipleResources(slctRsc);
            deviceManager.controllerUpdateApplied();
        }
        catch (AccessDeniedException | SQLException exc)
        {
            // TODO: kill connection?
            errorReporter.reportError(exc);
        }
    }

    public void applyNodeChanges(NodePojo nodePojo)
    {
        applyChangedData(new ApplyNodeData(nodePojo));
    }

    public void applyDeletedNodeChange(
        String nodeName,
        long fullSyncId,
        long updateId
    )
    {
        applyChangedData(new ApplyNodeData(nodeName, fullSyncId, updateId));
    }

    public void applyResourceChanges(RscPojo rscRawData)
    {
        applyChangedData(new ApplyRscData(rscRawData));
    }

    public void applyDeletedResourceChange(
        String rscNameStr,
        long fullSyncId,
        long updateId
    )
    {
        applyChangedData(new ApplyRscData(rscNameStr, fullSyncId, updateId));
    }

    public void applyStorPoolChanges(StorPoolPojo storPoolRaw)
    {
        applyChangedData(new ApplyStorPoolData(storPoolRaw));
    }

    public void applyDeletedStorPoolChange(
        String storPoolNameStr,
        long fullSyncId,
        long updateId
    )
    {
        applyChangedData(new ApplyStorPoolData(storPoolNameStr, fullSyncId, updateId));
    }

    public void applySnapshotChanges(SnapshotPojo snapshotRaw)
    {
        applyChangedData(new ApplySnapshotData(snapshotRaw));
    }

    public void applyEndedSnapshotChange(String rscName, String snapshotName, long fullSyncId, long updateId)
    {
        applyChangedData(new ApplyEndedSnapshotData(rscName, snapshotName, fullSyncId, updateId));
    }

    public void setCryptKey(byte[] key, long fullSyncId, long updateId)
    {
        applyChangedData(new ApplyCryptKey(key, fullSyncId, updateId));
    }


    private void applyChangedData(ApplyData data)
    {
        synchronized (dataToApply)
        {
            if (data.getFullSyncId() == updateMonitor.getCurrentFullSyncId())
            {
                try
                {
                    ApplyData overriddenData = dataToApply.put(data.getUpdateId(), data);
                    if (overriddenData != null)
                    {
                        errorReporter.reportError(
                            new ImplementationError(
                                "We have overridden data which we did not update yet.",
                                null
                            )
                        );
                        // critical error. shutdown and fix this implementation error
                        applicationLifecycleManager.shutdown(apiCtx);
                    }

                    Entry<Long, ApplyData> nextEntry;
                    nextEntry = dataToApply.firstEntry();
                    while (
                        nextEntry != null &&
                        nextEntry.getKey() == updateMonitor.getCurrentAwaitedUpdateId()
                    )
                    {
                        errorReporter.logTrace("Applying update " + nextEntry.getKey());

                        ApplyData applyData = nextEntry.getValue();
                        try (
                            LockSupport ls = LockSupport.lock(
                                applyData.needReconfigurationWriteLock() ?
                                    reconfigurationLock.writeLock() : reconfigurationLock.readLock()
                            )
                        )
                        {
                            applyData.applyChange();
                        }

                        dataToApply.remove(nextEntry.getKey());
                        updateMonitor.awaitedUpdateApplied();

                        nextEntry = dataToApply.firstEntry();
                    }
                    for (Entry<Long, ApplyData> remainingDataToApply : dataToApply.entrySet())
                    {
                        errorReporter.logDebug("Update " + remainingDataToApply.getKey() +
                            " queued until update " + updateMonitor.getCurrentAwaitedUpdateId() + " received");
                    }
                }
                catch (ImplementationError | Exception exc)
                {
                    errorReporter.reportError(exc);
                    try
                    {
                        controllerPeerConnector.getLocalNode().getPeer(apiCtx).closeConnection();
                        // there is nothing else we can safely do.
                        // skipping the update might cause data-corruption
                        // not skipping will queue the new data packets but will not apply those as the
                        // awaitedUpdateId will never increment.
                    }
                    catch (AccessDeniedException exc1)
                    {
                        errorReporter.reportError(new ImplementationError(exc));
                    }
                }
            }
            else
            {
                errorReporter.logWarning("Ignoring received outdated update. ");
            }
        }
    }

    public void handlePrimaryResource(
        String rscNameStr,
        UUID rscUuid
    )
    {
        try (LockSupport ls = LockSupport.lock(rscDfnMapLock.writeLock()))
        {
            rscDfnHandler.primaryResource(rscNameStr, rscUuid);
        }

    }

    public byte[] listErrorReports(
        final Set<String> nodes,
        boolean withContent,
        final Optional<Date> since,
        final Optional<Date> to,
        final Set<String> ids
    )
    {
        Set<ErrorReport> errorReports = StdErrorReporter.listReports(
            controllerPeerConnector.getLocalNode().getName().getDisplayName(),
            Paths.get(StdErrorReporter.LOG_DIRECTORY),
            withContent,
            since,
            to,
            ids
        );

        return interComSerializer.builder(ApiConsts.API_LST_ERROR_REPORTS, msgId.get())
            .errorReports(errorReports).build();
    }

    private interface ApplyData
    {
        long getFullSyncId();
        long getUpdateId();

        default boolean needReconfigurationWriteLock()
        {
            return false;
        }

        void applyChange();
    }

    private class ApplyControllerData implements ApplyData
    {
        private final Map<String, String> satelliteProps;
        private long fullSyncId;
        private long updateId;

        ApplyControllerData(Map<String, String> satellitePropsRef, long fullSyncIdRef, long updateIdRef)
        {
            satelliteProps = satellitePropsRef;
            fullSyncId = fullSyncIdRef;
            updateId = updateIdRef;
        }

        @Override
        public long getFullSyncId()
        {
            return fullSyncId;
        }

        @Override
        public long getUpdateId()
        {
            return updateId;
        }

        @Override
        public boolean needReconfigurationWriteLock()
        {
            return true;
        }

        @Override
        public void applyChange()
        {
            doApplyControllerChanges(satelliteProps);
        }
    }

    private class ApplyNodeData implements ApplyData
    {
        private NodePojo nodePojo;
        private String deletedNodeName;
        private long fullSyncId;
        private long updateId;

        ApplyNodeData(NodePojo nodePojoRef)
        {
            nodePojo = nodePojoRef;
            deletedNodeName = null;
            this.fullSyncId = nodePojoRef.getFullSyncId();
            this.updateId = nodePojoRef.getUpdateId();
        }

        ApplyNodeData(String nodeNameRef, long fullSyncIdRef, long updateIdRef)
        {
            nodePojo = null;
            deletedNodeName = nodeNameRef;
            fullSyncId = fullSyncIdRef;
            updateId = updateIdRef;
        }

        @Override
        public long getFullSyncId()
        {
            return fullSyncId;
        }

        @Override
        public long getUpdateId()
        {
            return updateId;
        }

        @Override
        public void applyChange()
        {
            try (LockSupport ls = LockSupport.lock(nodesMapLock.writeLock()))
            {
                if (nodePojo != null)
                {
                    nodeHandler.applyChanges(nodePojo);
                }
                else
                {
                    nodeHandler.applyDeletedNode(deletedNodeName);
                }
            }
        }
    }

    private class ApplyRscData implements ApplyData
    {
        private RscPojo rscPojo;
        private String deletedRscName;
        private long fullSyncId;
        private long updateId;

        ApplyRscData(RscPojo rscPojoRef)
        {
            rscPojo = rscPojoRef;
            fullSyncId = rscPojo.getFullSyncId();
            updateId = rscPojo.getUpdateId();
        }

        ApplyRscData(
            String rscNameRef,
            long fullSyncIdRef,
            long updateIdRef
        )
        {
            deletedRscName = rscNameRef;
            this.fullSyncId = fullSyncIdRef;
            this.updateId = updateIdRef;
        }

        @Override
        public long getFullSyncId()
        {
            return fullSyncId;
        }

        @Override
        public long getUpdateId()
        {
            return updateId;
        }

        @Override
        public void applyChange()
        {
            try (
                LockSupport ls = LockSupport.lock(
                    nodesMapLock.writeLock(),
                    rscDfnMapLock.writeLock()
                )
            )
            {
                if (rscPojo != null)
                {
                    rscHandler.applyChanges(rscPojo);
                }
                else
                {
                    rscHandler.applyDeletedRsc(deletedRscName);
                }
            }
        }
    }

    private class ApplyStorPoolData implements ApplyData
    {
        private StorPoolPojo storPoolPojo;
        private String deletedStorPoolName;
        private long fullSyncId;
        private long updateId;

        ApplyStorPoolData(StorPoolPojo storPoolPojoRef)
        {
            storPoolPojo = storPoolPojoRef;
            fullSyncId = storPoolPojo.getFullSyncId();
            updateId = storPoolPojo.getUpdateId();
        }

        ApplyStorPoolData(String storPoolNameRef, long fullSyncIdRef, long updateIdRef)
        {
            deletedStorPoolName = storPoolNameRef;
            fullSyncId = fullSyncIdRef;
            updateId = updateIdRef;
        }

        @Override
        public long getFullSyncId()
        {
            return fullSyncId;
        }

        @Override
        public long getUpdateId()
        {
            return updateId;
        }

        @Override
        public void applyChange()
        {
            try (
                LockSupport ls = LockSupport.lock(
                    nodesMapLock.writeLock(),
                    storPoolDfnMapLock.writeLock()
                )
            )
            {
                if (storPoolPojo != null)
                {
                    storPoolHandler.applyChanges(storPoolPojo);
                }
                else
                {
                    storPoolHandler.applyDeletedStorPool(deletedStorPoolName);
                }
            }
        }
    }

    private class ApplySnapshotData implements ApplyData
    {
        private final SnapshotPojo snapshotPojo;

        ApplySnapshotData(SnapshotPojo snapshotPojoRef)
        {
            snapshotPojo = snapshotPojoRef;
        }

        @Override
        public long getFullSyncId()
        {
            return snapshotPojo.getFullSyncId();
        }

        @Override
        public long getUpdateId()
        {
            return snapshotPojo.getUpdateId();
        }

        @Override
        public void applyChange()
        {
            try (
                LockSupport ls = LockSupport.lock(
                    rscDfnMapLock.writeLock()
                )
            )
            {
                snapshotHandler.applyChanges(snapshotPojo);
            }
        }
    }

    private class ApplyEndedSnapshotData implements ApplyData
    {
        private final String rscName;
        private final String snapshotName;
        private final long fullSyncId;
        private final long updateId;

        ApplyEndedSnapshotData(
            String rscNameRef,
            String snapshotNameRef,
            long fullSyncIdRef,
            long updateIdRef
        )
        {
            rscName = rscNameRef;
            snapshotName = snapshotNameRef;
            fullSyncId = fullSyncIdRef;
            updateId = updateIdRef;
        }

        @Override
        public long getFullSyncId()
        {
            return fullSyncId;
        }

        @Override
        public long getUpdateId()
        {
            return updateId;
        }

        @Override
        public void applyChange()
        {
            try (
                LockSupport ls = LockSupport.lock(
                    rscDfnMapLock.writeLock()
                )
            )
            {
                snapshotHandler.applyEndedSnapshot(rscName, snapshotName);
            }
        }
    }

    private class ApplyCryptKey implements ApplyData
    {
        private final byte[] cryptKey;
        private final long fullSyncId;
        private final long updateId;

        ApplyCryptKey(byte[] cryptKeyRef, long fullSyncIdRef, long updateIdRef)
        {
            cryptKey = cryptKeyRef;
            fullSyncId = fullSyncIdRef;
            updateId = updateIdRef;
        }

        @Override
        public long getFullSyncId()
        {
            return fullSyncId;
        }

        @Override
        public long getUpdateId()
        {
            return updateId;
        }

        @Override
        public void applyChange()
        {
            try (
                LockSupport ls = LockSupport.lock(
                    nodesMapLock.writeLock(),
                    rscDfnMapLock.writeLock(),
                    storPoolDfnMapLock.writeLock()
                )
            )
            {
                stltSecObj.setCryptKey(cryptKey);

                vlmDfnHandler.decryptAllVlmDfnKeys();
            }
        }


    }
}
