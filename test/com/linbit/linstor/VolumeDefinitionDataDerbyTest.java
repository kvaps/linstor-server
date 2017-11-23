package com.linbit.linstor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.linbit.TransactionMgr;
import com.linbit.linstor.DrbdDataAlreadyExistsException;
import com.linbit.linstor.MinorNumber;
import com.linbit.linstor.ResourceDefinition;
import com.linbit.linstor.ResourceDefinitionData;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.TcpPortNumber;
import com.linbit.linstor.VolumeDefinition;
import com.linbit.linstor.VolumeDefinitionData;
import com.linbit.linstor.VolumeDefinitionDataDerbyDriver;
import com.linbit.linstor.VolumeNumber;
import com.linbit.linstor.VolumeDefinition.VlmDfnFlags;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.propscon.PropsContainer;
import com.linbit.linstor.security.DerbyBase;
import com.linbit.utils.UuidUtils;

public class VolumeDefinitionDataDerbyTest extends DerbyBase
{
    private static final String SELECT_ALL_VOL_DFN =
        " SELECT " + UUID + ", " + RESOURCE_NAME + ", " + VLM_NR + ", " +
                     VLM_SIZE + ", " + VLM_MINOR_NR + ", " + VLM_FLAGS +
        " FROM " + TBL_VOLUME_DEFINITIONS;

    private TransactionMgr transMgr;

    private ResourceName resName;
    private TcpPortNumber resPort;
    private ResourceDefinition resDfn;

    private java.util.UUID uuid;
    private VolumeNumber volNr;
    private MinorNumber minor;
    private long volSize;

    private VolumeDefinitionData volDfn;

    private VolumeDefinitionDataDerbyDriver driver;

    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        assertEquals(TBL_VOLUME_DEFINITIONS + " table's column count has changed. Update tests accordingly!",
            6,
            TBL_COL_COUNT_VOLUME_DEFINITIONS
        );

        transMgr = new TransactionMgr(getConnection());

        resName = new ResourceName("TestResource");
        resPort = new TcpPortNumber(9001);
        resDfn = ResourceDefinitionData.getInstance(sysCtx, resName, resPort, null, "secret", transMgr, true, false);

        uuid = randomUUID();
        volNr = new VolumeNumber(13);
        minor = new MinorNumber(42);
        volSize = 5_000_000;
        volDfn = new VolumeDefinitionData(
            uuid,
            sysCtx,
            resDfn,
            volNr,
            minor,
            volSize,
            VlmDfnFlags.DELETE.flagValue,
            transMgr
        );

        driver = new VolumeDefinitionDataDerbyDriver(sysCtx, errorReporter);
    }

    @Test
    public void testPersist() throws Exception
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_ALL_VOL_DFN);
        ResultSet resultSet = stmt.executeQuery();

        assertFalse(resultSet.next());
        resultSet.close();

        driver.create(volDfn, transMgr);

        resultSet = stmt.executeQuery();
        assertTrue(resultSet.next());
        assertEquals(uuid, UuidUtils.asUuid(resultSet.getBytes(UUID)));
        assertEquals(resName.value, resultSet.getString(RESOURCE_NAME));
        assertEquals(volNr.value, resultSet.getInt(VLM_NR));
        assertEquals(volSize, resultSet.getLong(VLM_SIZE));
        assertEquals(minor.value, resultSet.getInt(VLM_MINOR_NR));
        assertEquals(VlmDfnFlags.DELETE.flagValue, resultSet.getLong(VLM_FLAGS));

        assertFalse(resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testPersistGetInstance() throws Exception
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_ALL_VOL_DFN);
        ResultSet resultSet = stmt.executeQuery();

        assertFalse(resultSet.next());
        resultSet.close();

        ResourceDefinition resDefinitionTest = ResourceDefinitionData.getInstance(
                sysCtx,
                new ResourceName("TestResource2"),
                resPort,
                null,
                "secret",
                transMgr,
                true,
                false);

        VolumeDefinitionData.getInstance(
            sysCtx,
            resDefinitionTest,
            volNr,
            minor,
            volSize,
            new VlmDfnFlags[] { VlmDfnFlags.DELETE },
            transMgr,
            true,
            false
        );

        resultSet = stmt.executeQuery();
        assertTrue(resultSet.next());
        assertEquals("TESTRESOURCE2", resultSet.getString(RESOURCE_NAME));
        assertEquals(volNr.value, resultSet.getInt(VLM_NR));
        assertEquals(volSize, resultSet.getLong(VLM_SIZE));
        assertEquals(minor.value, resultSet.getInt(VLM_MINOR_NR));
        assertEquals(VlmDfnFlags.DELETE.flagValue, resultSet.getLong(VLM_FLAGS));

        assertFalse(resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testLoad() throws Exception
    {
        driver.create(volDfn, transMgr);

        VolumeDefinitionData loadedVd = driver.load(resDfn, volNr, true, transMgr);
        assertNotNull(loadedVd);
        assertEquals(uuid, loadedVd.getUuid());
        assertEquals(resName, loadedVd.getResourceDefinition().getName());
        assertEquals(volNr, loadedVd.getVolumeNumber());
        assertEquals(volSize, loadedVd.getVolumeSize(sysCtx));
        assertEquals(minor, loadedVd.getMinorNr(sysCtx));
        assertTrue(loadedVd.getFlags().isSet(sysCtx, VlmDfnFlags.DELETE));
    }

    @Test
    public void testLoadGetInstance() throws Exception
    {
        driver.create(volDfn, transMgr);

        VolumeDefinitionData loadedVd = VolumeDefinitionData.getInstance(
            sysCtx,
            resDfn,
            volNr,
            minor,
            volSize,
            null,
            transMgr,
            false,
            false
        );

        assertNotNull(loadedVd);
        assertEquals(resName, loadedVd.getResourceDefinition().getName());
        assertEquals(volNr, loadedVd.getVolumeNumber());
        assertEquals(volSize, loadedVd.getVolumeSize(sysCtx));
        assertEquals(minor, loadedVd.getMinorNr(sysCtx));
        assertTrue(loadedVd.getFlags().isSet(sysCtx, VlmDfnFlags.DELETE));
    }

    @Test
    public void testLoadAll() throws Exception
    {
        driver.create(volDfn, transMgr);

        List<VolumeDefinition> volDfnList = driver.loadAllVolumeDefinitionsByResourceDefinition(
            resDfn,
            transMgr,
            sysCtx
        );

        assertNotNull(volDfnList);
        assertEquals(1, volDfnList.size());
        VolumeDefinition loadedVd = volDfnList.get(0);

        assertNotNull(loadedVd);
        assertEquals(resName, loadedVd.getResourceDefinition().getName());
        assertEquals(volNr, loadedVd.getVolumeNumber());
        assertEquals(volSize, loadedVd.getVolumeSize(sysCtx));
        assertEquals(minor, loadedVd.getMinorNr(sysCtx));
        assertTrue(loadedVd.getFlags().isSet(sysCtx, VlmDfnFlags.DELETE));
    }

    @Test
    public void testDelete() throws Exception
    {
        driver.create(volDfn, transMgr);

        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_ALL_VOL_DFN);
        ResultSet resultSet = stmt.executeQuery();

        assertTrue(resultSet.next());
        assertFalse(resultSet.next());
        resultSet.close();

        driver.delete(volDfn, transMgr);

        resultSet = stmt.executeQuery();

        assertFalse(resultSet.next());
        resultSet.close();
        stmt.close();
    }

    @Test
    public void testPropsConPersist() throws Exception
    {
        driver.create(volDfn, transMgr);
        volDfn.initialized();
        volDfn.setConnection(transMgr);

        Props props = volDfn.getProps(sysCtx);
        String testKey = "TestKey";
        String testValue = "TestValue";
        props.setProp(testKey, testValue);

        transMgr.commit();

        volDfn.setConnection(transMgr);
        Map<String, String> map = new HashMap<>();
        map.put(testKey, testValue);
        testProps(transMgr, PropsContainer.buildPath(resName, volNr), map);
    }

    @Test
    public void testMinorNrDriverUpdate() throws Exception
    {
        driver.create(volDfn, transMgr);
        MinorNumber newMinorNr = new MinorNumber(32);
        driver.getMinorNumberDriver().update(volDfn, newMinorNr, transMgr);

        transMgr.commit();

        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_ALL_VOL_DFN);
        ResultSet resultSet = stmt.executeQuery();

        assertTrue(resultSet.next());
        assertEquals(newMinorNr.value, resultSet.getInt(VLM_MINOR_NR));
        assertFalse(resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testMinorNrInstanceUpdate() throws Exception
    {
        driver.create(volDfn, transMgr);
        volDfn.initialized();
        volDfn.setConnection(transMgr);

        MinorNumber minor2 = new MinorNumber(32);
        volDfn.setMinorNr(sysCtx, minor2);

        transMgr.commit();

        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_ALL_VOL_DFN);
        ResultSet resultSet = stmt.executeQuery();

        assertTrue(resultSet.next());
        assertEquals(minor2.value, resultSet.getInt(VLM_MINOR_NR));
        assertFalse(resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testFlagsUpdateInstance() throws Exception
    {
        driver.create(volDfn, transMgr);
        volDfn.initialized();
        volDfn.setConnection(transMgr);

        volDfn.getFlags().disableAllFlags(sysCtx);

        transMgr.commit();

        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_ALL_VOL_DFN);
        ResultSet resultSet = stmt.executeQuery();

        assertTrue(resultSet.next());
        assertEquals(0, resultSet.getLong(VLM_FLAGS));
        assertFalse(resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testFlagsUpdateDriver() throws Exception
    {
        driver.create(volDfn, transMgr);
        long newFlagMask = 0;
        driver.getStateFlagsPersistence().persist(volDfn, newFlagMask, transMgr);

        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_ALL_VOL_DFN);
        ResultSet resultSet = stmt.executeQuery();

        assertTrue(resultSet.next());
        assertEquals(newFlagMask, resultSet.getLong(VLM_FLAGS));
        assertFalse(resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testVolSizeUpdateInstance() throws Exception
    {
        driver.create(volDfn, transMgr);

        volDfn.initialized();
        volDfn.setConnection(transMgr);

        long size = 9001;
        volDfn.setVolumeSize(sysCtx, size);

        transMgr.commit();

        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_ALL_VOL_DFN);
        ResultSet resultSet = stmt.executeQuery();

        assertTrue(resultSet.next());
        assertEquals(size, resultSet.getLong(VLM_SIZE));
        assertFalse(resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testVolSizeUpdateDriver() throws Exception
    {
        driver.create(volDfn, transMgr);
        long size = 9001;
        driver.getVolumeSizeDriver().update(volDfn, size, transMgr);

        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_ALL_VOL_DFN);
        ResultSet resultSet = stmt.executeQuery();

        assertTrue(resultSet.next());
        assertEquals(size, resultSet.getLong(VLM_SIZE));
        assertFalse(resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testSatelliteCreate() throws Exception
    {
        satelliteMode();

        VolumeDefinitionData volDfnSat = VolumeDefinitionData.getInstance(
            sysCtx,
            resDfn,
            volNr,
            minor,
            volSize,
            new VlmDfnFlags[] { VlmDfnFlags.DELETE },
            null,
            true,
            false
        );

        assertNotNull(volDfnSat);
        assertEquals(resName, volDfnSat.getResourceDefinition().getName());
        assertEquals(volNr, volDfnSat.getVolumeNumber());
        assertEquals(volSize, volDfnSat.getVolumeSize(sysCtx));
        assertEquals(minor, volDfnSat.getMinorNr(sysCtx));
        assertTrue(volDfnSat.getFlags().isSet(sysCtx, VlmDfnFlags.DELETE));

        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_ALL_VOL_DFN);
        ResultSet resultSet = stmt.executeQuery();

        assertFalse(resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testNoCreate() throws Exception
    {
        satelliteMode();

        ResourceDefinitionData resDfn2 = ResourceDefinitionData.getInstance(
                sysCtx,
                new ResourceName("Resource2"),
                resPort,
                null,
                "secret",
                transMgr,
                true,
                false);

        VolumeDefinitionData volDfnSat = VolumeDefinitionData.getInstance(
            sysCtx,
            resDfn2,
            volNr,
            minor,
            volSize,
            new VlmDfnFlags[] { VlmDfnFlags.DELETE },
            null,
            false,
            false
        );

        assertNull(volDfnSat);

        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_ALL_VOL_DFN);
        ResultSet resultSet = stmt.executeQuery();

        assertFalse(resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test (expected = DrbdDataAlreadyExistsException.class)
    public void testAlreadyExists() throws Exception
    {
        driver.create(volDfn, transMgr);

        VolumeDefinitionData.getInstance(sysCtx, resDfn, volNr, minor, volSize, null, transMgr, false, true);
    }
}
