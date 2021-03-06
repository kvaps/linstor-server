package com.linbit.linstor;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.linstor.SnapshotDefinition.SnapshotDfnFlags;
import com.linbit.linstor.annotation.SystemContext;
import com.linbit.linstor.dbdrivers.GenericDbDriver;
import com.linbit.linstor.dbdrivers.derby.DbConstants;
import com.linbit.linstor.dbdrivers.interfaces.SnapshotDefinitionDataDatabaseDriver;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.stateflags.FlagsHelper;
import com.linbit.linstor.stateflags.StateFlagsPersistence;
import com.linbit.linstor.transaction.TransactionMgr;
import com.linbit.linstor.transaction.TransactionObjectFactory;
import com.linbit.utils.Pair;
import com.linbit.utils.StringUtils;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

@Singleton
public class SnapshotDefinitionDataGenericDbDriver implements SnapshotDefinitionDataDatabaseDriver
{
    private static final String TBL_SNAPSHOT_DFN = DbConstants.TBL_SNAPSHOT_DEFINITIONS;

    private static final String SD_UUID = DbConstants.UUID;
    private static final String SD_RES_NAME = DbConstants.RESOURCE_NAME;
    private static final String SD_NAME = DbConstants.SNAPSHOT_NAME;
    private static final String SD_DSP_NAME = DbConstants.SNAPSHOT_DSP_NAME;
    private static final String SD_FLAGS = DbConstants.SNAPSHOT_FLAGS;

    private static final String SD_SELECT_ALL =
        " SELECT " + SD_UUID + ", " + SD_RES_NAME + ", " + SD_NAME + ", " + SD_DSP_NAME + ", " + SD_FLAGS +
        " FROM " + TBL_SNAPSHOT_DFN;

    private static final String SD_INSERT =
        " INSERT INTO " + TBL_SNAPSHOT_DFN +
        " (" +
            SD_UUID + ", " + SD_RES_NAME + ", " + SD_NAME + ", " + SD_DSP_NAME + ", " +  SD_FLAGS +
        ") VALUES (?, ?, ?, ?, ?)";
    private static final String SD_UPDATE_FLAGS =
        " UPDATE " + TBL_SNAPSHOT_DFN +
        " SET " + SD_FLAGS + " = ? " +
        " WHERE " + SD_RES_NAME + " = ? AND " +
            SD_NAME + " = ?";
    private static final String SD_DELETE =
        " DELETE FROM " + TBL_SNAPSHOT_DFN +
        " WHERE " + SD_RES_NAME + " = ? AND " +
            SD_NAME + " = ?";

    private final AccessContext dbCtx;
    private final ErrorReporter errorReporter;

    private final FlagDriver flagsDriver;
    private final TransactionObjectFactory transObjFactory;
    private final Provider<TransactionMgr> transMgrProvider;

    @Inject
    public SnapshotDefinitionDataGenericDbDriver(
        @SystemContext AccessContext accCtx,
        ErrorReporter errorReporterRef,
        TransactionObjectFactory transObjFactoryRef,
        Provider<TransactionMgr> transMgrProviderRef
    )
    {
        dbCtx = accCtx;
        errorReporter = errorReporterRef;
        transObjFactory = transObjFactoryRef;
        transMgrProvider = transMgrProviderRef;
        flagsDriver = new FlagDriver();
    }

    @Override
    @SuppressWarnings("checkstyle:magicnumber")
    public void create(SnapshotDefinitionData snapshotDefinition) throws SQLException
    {
        try (PreparedStatement stmt = getConnection().prepareStatement(SD_INSERT))
        {
            errorReporter.logTrace("Creating SnapshotDefinition %s", getId(snapshotDefinition));

            stmt.setString(1, snapshotDefinition.getUuid().toString());
            stmt.setString(2, snapshotDefinition.getResourceName().value);
            stmt.setString(3, snapshotDefinition.getName().value);
            stmt.setString(4, snapshotDefinition.getName().displayValue);
            stmt.setLong(5, snapshotDefinition.getFlags().getFlagsBits(dbCtx));

            stmt.executeUpdate();

            errorReporter.logTrace("SnapshotDefinition created %s", getId(snapshotDefinition));
        }
        catch (AccessDeniedException accessDeniedExc)
        {
            GenericDbDriver.handleAccessDeniedException(accessDeniedExc);
        }
    }

    private Pair<SnapshotDefinitionData, SnapshotDefinition.InitMaps> restoreSnapshotDefinition(
        ResultSet resultSet,
        ResourceDefinition resDfn,
        SnapshotName snapshotName
    )
        throws SQLException
    {
        errorReporter.logTrace("Restoring SnapshotDefinition %s", getId(resDfn, snapshotName));
        SnapshotDefinitionData snapshotDfn;
        Pair<SnapshotDefinitionData, SnapshotDefinition.InitMaps> retPair;

        Map<VolumeNumber, SnapshotVolumeDefinition> snapshotVlmDfnMap = new TreeMap<>();
        Map<NodeName, Snapshot> snapshotMap = new TreeMap<>();

        snapshotDfn = new SnapshotDefinitionData(
            java.util.UUID.fromString(resultSet.getString(SD_UUID)),
            resDfn,
            snapshotName,
            resultSet.getLong(SD_FLAGS),
            this,
            transObjFactory,
            transMgrProvider,
            snapshotVlmDfnMap,
            snapshotMap
        );
        retPair = new Pair<>(snapshotDfn, new SnapshotDefinitionInitMaps(snapshotMap, snapshotVlmDfnMap));

        errorReporter.logTrace("SnapshotDefinition %s created during restore", getId(snapshotDfn));

        return retPair;
    }


    public Map<SnapshotDefinition, SnapshotDefinition.InitMaps> loadAll(
        Map<ResourceName, ? extends ResourceDefinition> rscDfnMap
    )
        throws SQLException
    {
        errorReporter.logTrace("Loading all SnapshotDefinitions");
        Map<SnapshotDefinition, SnapshotDefinition.InitMaps> ret = new TreeMap<>();
        try (PreparedStatement stmt = getConnection().prepareStatement(SD_SELECT_ALL))
        {
            try (ResultSet resultSet = stmt.executeQuery())
            {
                while (resultSet.next())
                {
                    ResourceName rscName;
                    SnapshotName snapshotName;
                    try
                    {
                        rscName = new ResourceName(resultSet.getString(SD_RES_NAME));
                        snapshotName = new SnapshotName(resultSet.getString(SD_DSP_NAME));
                    }
                    catch (InvalidNameException exc)
                    {
                        throw new ImplementationError(
                            TBL_SNAPSHOT_DFN + " contained invalid name: " + exc.invalidName,
                            exc
                        );
                    }

                    Pair<SnapshotDefinitionData, SnapshotDefinition.InitMaps> pair = restoreSnapshotDefinition(
                        resultSet,
                        rscDfnMap.get(rscName),
                        snapshotName
                    );

                    ret.put(pair.objA, pair.objB);

                    errorReporter.logTrace("SnapshotDefinition created %s", getId(pair.objA));
                }
            }
        }
        return ret;
    }

    @Override
    public void delete(SnapshotDefinitionData snapshotDefinition) throws SQLException
    {
        errorReporter.logTrace("Deleting SnapshotDefinition %s", getId(snapshotDefinition));
        try (PreparedStatement stmt = getConnection().prepareStatement(SD_DELETE))
        {
            stmt.setString(1, snapshotDefinition.getResourceName().value);
            stmt.setString(2, snapshotDefinition.getName().value);
            stmt.executeUpdate();
            errorReporter.logTrace("SnapshotDefinition deleted %s", getId(snapshotDefinition));
        }
    }

    @Override
    public StateFlagsPersistence<SnapshotDefinitionData> getStateFlagsPersistence()
    {
        return flagsDriver;
    }

    private Connection getConnection()
    {
        return transMgrProvider.get().getConnection();
    }

    private String getId(SnapshotDefinitionData snapshotDfn)
    {
        return getId(
            snapshotDfn.getResourceDefinition(),
            snapshotDfn.getName()
        );
    }

    private String getId(ResourceDefinition resourceDefinition, SnapshotName snapshotName)
    {
        return getId(
            resourceDefinition.getName().displayValue,
            snapshotName
        );
    }

    private String getId(String resName, SnapshotName snapshotName)
    {
        return "(ResName=" + resName + " SnapshotName=" + snapshotName.displayValue + ")";
    }

    private class FlagDriver implements StateFlagsPersistence<SnapshotDefinitionData>
    {
        @Override
        @SuppressWarnings("checkstyle:magicnumber")
        public void persist(SnapshotDefinitionData snapshotDefinition, long flags)
            throws SQLException
        {
            try (PreparedStatement stmt = getConnection().prepareStatement(SD_UPDATE_FLAGS))
            {
                String fromFlags = StringUtils.join(
                    FlagsHelper.toStringList(
                        SnapshotDfnFlags.class,
                        snapshotDefinition.getFlags().getFlagsBits(dbCtx)
                    ),
                    ", "
                );
                String toFlags = StringUtils.join(
                    FlagsHelper.toStringList(
                        SnapshotDfnFlags.class,
                        flags
                    ),
                    ", "
                );
                errorReporter.logTrace(
                    "Updating SnapshotDefinition's flags from [%s] to [%s] %s",
                    fromFlags,
                    toFlags,
                    getId(snapshotDefinition)
                );
                stmt.setLong(1, flags);
                stmt.setString(2, snapshotDefinition.getResourceName().value);
                stmt.setString(3, snapshotDefinition.getName().value);
                stmt.executeUpdate();

                errorReporter.logTrace(
                    "SnapshotDefinition's flags updated from [%s] to [%s] %s",
                    fromFlags,
                    toFlags,
                    getId(snapshotDefinition)
                );
            }
            catch (AccessDeniedException accessDeniedExc)
            {
                GenericDbDriver.handleAccessDeniedException(accessDeniedExc);
            }
        }
    }

    private class SnapshotDefinitionInitMaps implements SnapshotDefinition.InitMaps
    {
        private final Map<NodeName, Snapshot> snapshotMap;

        private final Map<VolumeNumber, SnapshotVolumeDefinition> snapshotVolumeDefinitionMap;

        SnapshotDefinitionInitMaps(
            Map<NodeName, Snapshot> snapshotMapRef,
            Map<VolumeNumber, SnapshotVolumeDefinition> snapshotVolumeDefinitionMapRef
        )
        {
            snapshotMap = snapshotMapRef;
            snapshotVolumeDefinitionMap = snapshotVolumeDefinitionMapRef;
        }

        @Override
        public Map<NodeName, Snapshot> getSnapshotMap()
        {
            return snapshotMap;
        }

        @Override
        public Map<VolumeNumber, SnapshotVolumeDefinition> getSnapshotVolumeDefinitionMap()
        {
            return snapshotVolumeDefinitionMap;
        }
    }
}
