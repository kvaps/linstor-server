package com.linbit.linstor.storage.layer.provider.swordfish;

import com.linbit.ChildProcessTimeoutException;
import com.linbit.ImplementationError;
import com.linbit.extproc.ExtCmd;
import com.linbit.extproc.ExtCmdFactory;
import com.linbit.extproc.ExtCmd.OutputData;
import com.linbit.linstor.StorPool;
import com.linbit.linstor.Volume;
import com.linbit.linstor.annotation.DeviceManagerContext;
import com.linbit.linstor.core.StltConfigAccessor;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.propscon.ReadOnlyProps;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.StorageConstants;
import com.linbit.linstor.storage.StorageException;
import com.linbit.linstor.storage.layer.DeviceLayer.NotificationListener;
import com.linbit.linstor.storage.layer.provider.utils.ProviderUtils;
import com.linbit.linstor.storage.utils.DeviceLayerUtils;
import com.linbit.linstor.storage.utils.HttpHeader;
import com.linbit.linstor.storage.utils.RestHttpClient;
import com.linbit.linstor.storage.utils.RestResponse;
import com.linbit.linstor.storage.utils.RestClient.RestOp;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_ALLOWABLE_VALUES;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_CONNECTED_ENTITIES;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_DURABLE_NAME;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_DURABLE_NAME_FORMAT;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_ENDPOINTS;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_ENTITY_ROLE;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_IDENTIFIERS;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_INTEL_RACK_SCALE;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_LINKS;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_ODATA_ID;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_OEM;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_PARAMETERS;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_KEY_RESOURCE;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_VALUE_NQN;
import static com.linbit.linstor.storage.utils.SwordfishConsts.JSON_VALUE_TARGET;
import static com.linbit.linstor.storage.utils.SwordfishConsts.PATTERN_NQN;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_ACTIONS;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_ATTACH_RESOURCE_ACTION_INFO;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_BASE;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_COMPOSED_NODE_ATTACH_RESOURCE;
import static com.linbit.linstor.storage.utils.SwordfishConsts.SF_NODES;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import com.fasterxml.jackson.jr.ob.impl.MapBuilder;

@Singleton
public class SwordfishInitiatorProvider extends AbsSwordfishProvider
{
    private static final long POLL_VLM_ATTACH_TIMEOUT_DEFAULT = 1000;
    private static final long POLL_VLM_ATTACH_MAX_TRIES_DEFAULT = 290;
    private static final long POLL_GREP_TIMEOUT_DEFAULT = 1000;
    private static final long POLL_GREP_MAX_TRIES_DEFAULT = 290;

    private String composedNodeId;
    private final ExtCmdFactory extCmdFactory;

    @Inject
    public SwordfishInitiatorProvider(
        @DeviceManagerContext AccessContext sysCtx,
        ErrorReporter errorReporter,
        Provider<NotificationListener> notificationListenerProvider,
        StltConfigAccessor stltConfigAccessor,
        ExtCmdFactory extCmdFactoryRef
    )
    {
        super(
            sysCtx,
            errorReporter,
            new RestHttpClient(errorReporter), // TODO: maybe use guice here?
            notificationListenerProvider,
            stltConfigAccessor,
            "SFI",
            "attached",
            "detached"
        );
        extCmdFactory = extCmdFactoryRef;
    }

    @Override
    protected void createImpl(Volume vlm) throws StorageException, AccessDeniedException, SQLException
    {
        try
        {
            if (!isSfVolumeAttached(vlm))
            {
                waitUntilSfVlmIsAttachable(vlm);
                attachSfVolume(vlm);
            }
            else
            {
                clearAndSet((SfVlmDataStlt) vlm.getLayerData(sysCtx), SfVlmDataStlt.ATTACHABLE);
            }

            // TODO implement health check on composed node

            String volumePath = getVolumePath(vlm);

            vlm.setDevicePath(sysCtx, volumePath);
            ProviderUtils.updateSize(vlm, extCmdFactory.create(), sysCtx);
        }
        catch (InvalidKeyException exc)
        {
            throw new ImplementationError(exc);
        }
        catch (InterruptedException exc)
        {
            throw new StorageException("poll timeout interrupted", exc);
        }
        catch (IOException exc)
        {
            clearAndSet((SfVlmDataStlt) vlm.getLayerData(sysCtx), SfVlmDataStlt.IO_EXC);
            throw new StorageException("IO Exception", exc);
        }
    }

    @Override
    protected void deleteImpl(Volume vlm) throws StorageException, AccessDeniedException, SQLException
    {
        // TODO Auto-generated method stub
        throw new ImplementationError("Not implemented yet");
    }

    @Override
    public long getPoolCapacity(StorPool storPool) throws StorageException, AccessDeniedException
    {
        return Long.MAX_VALUE;
    }

    @Override
    public long getPoolFreeSpace(StorPool storPool) throws StorageException, AccessDeniedException
    {
        return Long.MAX_VALUE;
    }

    @Override
    public void setLocalNodeProps(Props localNodePropsRef)
    {
        super.setLocalNodeProps(localNodePropsRef);
        Props storDriverNamespace = DeviceLayerUtils.getNamespaceStorDriver(localNodePropsRef);

        try
        {
            composedNodeId = storDriverNamespace.getProp(StorageConstants.CONFIG_SF_COMPOSED_NODE_NAME_KEY);
        }
        catch (InvalidKeyException exc)
        {
            throw new ImplementationError("Invalid hardcoded key", exc);
        }
    }

    private boolean isSfVolumeAttached(Volume vlm) throws StorageException, AccessDeniedException, SQLException
    {
        return getSfVolumeEndpointDurableNameNqn(vlm) != null;
    }

    @SuppressWarnings("unchecked")
    private String getSfVolumeEndpointDurableNameNqn(Volume vlm)
        throws StorageException, AccessDeniedException, SQLException
    {
        RestResponse<Map<String, Object>> vlmInfo = getSfVlm(vlm);

        Map<String, Object> vlmData = vlmInfo.getData();
        Map<String, Object> vlmLinks = (Map<String, Object>) vlmData.get(JSON_KEY_LINKS);
        Map<String, Object> linksOem = (Map<String, Object>) vlmLinks.get(JSON_KEY_OEM);
        Map<String, Object> oemIntelRackscale = (Map<String, Object>) linksOem.get(JSON_KEY_INTEL_RACK_SCALE);
        ArrayList<Object> intelRackscaleEndpoints =
            (ArrayList<Object>) oemIntelRackscale.get(JSON_KEY_ENDPOINTS);

        String nqn = null;
        for (Object endpointObj : intelRackscaleEndpoints)
        {
            Map<String, Object> endpoint = (Map<String, Object>) endpointObj;

            String endpointOdataId = (String) endpoint.get(JSON_KEY_ODATA_ID);

            RestResponse<Map<String, Object>> endpointInfo = getSwordfishResource(
                vlm,
                endpointOdataId,
                false
            );
            Map<String, Object> endpointData = endpointInfo.getData();

            List<Map<String, Object>> connectedEntities =
                (List<Map<String, Object>>) endpointData.get(JSON_KEY_CONNECTED_ENTITIES);
            for (Map<String, Object> connectedEntity : connectedEntities)
            {
                if (connectedEntity.get(JSON_KEY_ENTITY_ROLE).equals(JSON_VALUE_TARGET))
                {
                    List<Object> endpointIdentifiers =
                        (List<Object>) endpointData.get(JSON_KEY_IDENTIFIERS);
                    for (Object endpointIdentifier : endpointIdentifiers)
                    {
                        Map<String, Object> endpointIdMap = (Map<String, Object>) endpointIdentifier;

                        if (JSON_VALUE_NQN.equals(endpointIdMap.get(JSON_KEY_DURABLE_NAME_FORMAT)))
                        {
                            nqn = (String) endpointIdMap.get(JSON_KEY_DURABLE_NAME);
                            break;
                        }
                    }
                    if (nqn != null)
                    {
                        break;
                    }
                }
            }
        }
        return nqn;
    }

    @SuppressWarnings("unchecked")
    private void waitUntilSfVlmIsAttachable(Volume vlm)
        throws InterruptedException, IOException, StorageException, AccessDeniedException,
        InvalidKeyException, SQLException
    {
        SfVlmDataStlt vlmData = (SfVlmDataStlt) vlm.getLayerData(sysCtx);

        String attachInfoAction = SF_BASE + SF_NODES + "/" + getComposedNodeId() + SF_ACTIONS +
            SF_ATTACH_RESOURCE_ACTION_INFO;
        boolean attachable = false;

        clearAndSet(vlmData, SfVlmDataStlt.WAITING_ATTACHABLE);

        ReadOnlyProps stltRoProps = stltConfigAccessor.getReadonlyProps();
        long pollAttachVlmTimeout = prioStorDriverPropsAsLong(
            StorageConstants.CONFIG_SF_POLL_TIMEOUT_ATTACH_VLM_KEY,
            POLL_VLM_ATTACH_TIMEOUT_DEFAULT,
            vlm.getStorPool(sysCtx).getProps(sysCtx),
            localNodeProps,
            stltRoProps
        );
        long pollAttachVlmMaxTries = prioStorDriverPropsAsLong(
            StorageConstants.CONFIG_SF_POLL_RETRIES_ATTACH_VLM_KEY,
            POLL_VLM_ATTACH_MAX_TRIES_DEFAULT,
            vlm.getStorPool(sysCtx).getProps(sysCtx),
            localNodeProps,
            stltRoProps
        );


        int pollAttachRscTries = 0;
        while (!attachable)
        {
            errorReporter.logTrace(
                "waiting %d ms before polling node's Actions/AttachResourceActionInfo",
                pollAttachVlmTimeout
            );
            Thread.sleep(pollAttachVlmTimeout);

            RestResponse<Map<String, Object>> attachRscInfoResp = restClient.execute(
                null,
                vlm,
                RestOp.GET,
                sfUrl  + attachInfoAction,
                getDefaultHeader().noContentType().build(),
                (String) null,
                Arrays.asList(HttpHeader.HTTP_OK)
            );
            Map<String, Object> attachRscInfoData = attachRscInfoResp.getData();
            ArrayList<Object> attachInfoParameters = (ArrayList<Object>) attachRscInfoData.get(JSON_KEY_PARAMETERS);
            for (Object attachInfoParameter : attachInfoParameters)
            {
                Map<String, Object> attachInfoParamMap = (Map<String, Object>) attachInfoParameter;
                ArrayList<Object> paramAllowableValues =
                    (ArrayList<Object>) attachInfoParamMap.get(JSON_KEY_ALLOWABLE_VALUES);
                if (paramAllowableValues != null)
                {
                    for (Object paramAllowableValue : paramAllowableValues)
                    {
                        if (paramAllowableValue instanceof Map)
                        {
                            Map<String, Object> paramAllowableValueMap = (Map<String, Object>) paramAllowableValue;
                            String attachableVlmId = (String) paramAllowableValueMap.get(JSON_KEY_ODATA_ID);
                            if (vlmData.vlmDfnData.vlmOdata.equals(attachableVlmId))
                            {
                                attachable = true;
                                break;
                            }
                        }
                    }
                }
            }

            if (++pollAttachRscTries >= pollAttachVlmMaxTries)
            {
                clearAndSet(vlmData, SfVlmDataStlt.WAITING_ATTACHABLE_TIMEOUT);
                throw new StorageException(
                    String.format(
                        "Volume could not be attached after %d x %dms. \n" +
                        "Volume did not show up in %s -> %s from GET %s",
                        pollAttachRscTries,
                        pollAttachVlmTimeout,
                        JSON_KEY_PARAMETERS,
                        JSON_KEY_ALLOWABLE_VALUES,
                        sfUrl  + attachInfoAction
                    )
                );
            }
        }
        clearAndSet(vlmData, SfVlmDataStlt.ATTACHABLE);
    }

    private void attachSfVolume(Volume vlm) throws IOException, StorageException, AccessDeniedException, SQLException
    {
        String attachAction = SF_BASE + SF_NODES + "/" + getComposedNodeId() + SF_ACTIONS +
            SF_COMPOSED_NODE_ATTACH_RESOURCE;
        SfVlmDataStlt vlmData = (SfVlmDataStlt) vlm.getLayerData(sysCtx);

        restClient.execute(
            null,
            vlm, // compatibility only...
            RestOp.POST,
            sfUrl + attachAction,
            getDefaultHeader().build(),
            MapBuilder.defaultImpl().start()
                .put(
                    JSON_KEY_RESOURCE,
                    MapBuilder.defaultImpl().start()
                        .put(JSON_KEY_ODATA_ID, vlmData.vlmDfnData.vlmOdata)
                        .build()
                )
                .build(),
            Arrays.asList(HttpHeader.HTTP_NO_CONTENT)
        );
        clearAndSet(vlmData, SfVlmDataStlt.ATTACHING);
    }

    public String getVolumePath(Volume vlm)
        throws StorageException, AccessDeniedException, InvalidKeyException, SQLException
    {
        SfVlmDataStlt vlmData = (SfVlmDataStlt) vlm.getLayerData(sysCtx);

        ReadOnlyProps stltRoProps = stltConfigAccessor.getReadonlyProps();
        long pollGrepNvmeUuidTimeout = prioStorDriverPropsAsLong(
            StorageConstants.CONFIG_SF_POLL_TIMEOUT_GREP_NVME_UUID_KEY,
            POLL_GREP_TIMEOUT_DEFAULT,
            vlm.getStorPool(sysCtx).getProps(sysCtx),
            localNodeProps,
            stltRoProps
        );
        long pollGrepNvmeUuidMaxTries = prioStorDriverPropsAsLong(
            StorageConstants.CONFIG_SF_POLL_RETRIES_GREP_NVME_UUID_KEY,
            POLL_GREP_MAX_TRIES_DEFAULT,
            vlm.getStorPool(sysCtx).getProps(sysCtx),
            localNodeProps,
            stltRoProps
        );

        String path = null;
        try
        {
            String nqnUuid = null;

            String nqn = getSfVolumeEndpointDurableNameNqn(vlm);

            if (nqn != null)
            {
                nqnUuid = nqn.substring(nqn.lastIndexOf("uuid:") + "uuid:".length());

                int grepTries = 0;
                boolean grepFailed = false;
                boolean grepFound = false;
                while (!grepFailed && !grepFound)
                {
                    final ExtCmd extCmd = extCmdFactory.create();
                    OutputData outputData = extCmd.exec(
                        "/bin/bash",
                        "-c",
                        "grep -H --color=never " + nqnUuid +
                        " /sys/devices/virtual/nvme-fabrics/ctl/nvme*/subsysnqn"
                    );
                    if (outputData.exitCode == 0)
                    {
                        String outString = new String(outputData.stdoutData);
                        Matcher matcher = PATTERN_NQN.matcher(outString);
                        if (matcher.find())
                        {
                            // although nvme supports multiple namespace, the current implementation
                            // relies on podmanager's limitation of only supporting one namespace per
                            // nvme device
                            path = "/dev/nvme" + matcher.group(1) + "n1";
                            grepFound = true;

                            clearAndSet(vlmData, SfVlmDataStlt.ATTACHED);
                        }
                    }

                    if (++grepTries >= pollGrepNvmeUuidMaxTries)
                    {
                        clearAndSet(vlmData, SfVlmDataStlt.WAITING_ATTACHING_TIMEOUT);
                        grepFailed = true;
                    }
                    else
                    {
                        Thread.sleep(pollGrepNvmeUuidTimeout);
                    }
                }
            }
            if (path == null)
            {
                throw new StorageException("Could not extract system path of volume");
            }
        }
        catch (ClassCastException ccExc)
        {
            throw new StorageException("Unexpected json structure", ccExc);
        }
        catch (ChildProcessTimeoutException timeoutExc)
        {
            throw new StorageException("External operation timed out", timeoutExc);
        }
        catch (InterruptedException interruptedExc)
        {
            throw new StorageException("Interrupted exception", interruptedExc);
        }
        catch (IOException ioExc)
        {
            throw new StorageException("IO exception occured", ioExc);
        }
        return path;
    }

    private String getComposedNodeId()
    {
        return composedNodeId;
    }

    private RestResponse<Map<String, Object>> getSfVlm(Volume vlm)
        throws StorageException, AccessDeniedException, SQLException
    {
        SfVlmDataStlt vlmData = (SfVlmDataStlt) vlm.getLayerData(sysCtx);

        return getSwordfishResource(
            vlm,
            vlmData.vlmDfnData.vlmOdata,
            false
        );
    }
}