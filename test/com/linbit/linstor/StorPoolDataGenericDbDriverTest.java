package com.linbit.linstor;

import javax.inject.Inject;
import com.linbit.InvalidNameException;
import com.linbit.linstor.StorPool.InitMaps;
import com.linbit.linstor.core.LinStor;
import com.linbit.linstor.security.GenericDbBase;
import com.linbit.linstor.storage.LvmDriver;
import org.junit.Before;
import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StorPoolDataGenericDbDriverTest extends GenericDbBase
{
    private static final String SELECT_ALL_STOR_POOLS =
        " SELECT " + UUID + ", " + NODE_NAME + ", " + POOL_NAME + ", " + DRIVER_NAME +
        " FROM " + TBL_NODE_STOR_POOL +
        " WHERE " + POOL_NAME + " <> '" + LinStor.DISKLESS_STOR_POOL_NAME.toUpperCase() + "'";

    private final NodeName nodeName;
    private final StorPoolName spName;

    private NodeData node;

    private java.util.UUID uuid;

    private StorPoolDefinitionData spdd;
    @Inject private StorPoolDefinitionDataGenericDbDriver spdDriver;
    @Inject private StorPoolDataGenericDbDriver driver;

    private StorPoolDefinitionData disklessSpdd;


    public StorPoolDataGenericDbDriverTest() throws InvalidNameException
    {
        nodeName = new NodeName("TestNodeName");
        spName = new StorPoolName("TestStorPoolDefinition");
    }

    @SuppressWarnings("checkstyle:magicnumber")
    @Before
    public void setUp() throws Exception
    {
        super.setUpAndEnterScope();
        assertEquals(
            TBL_NODE_STOR_POOL + " table's column count has changed. Update tests accordingly!",
            4,
            TBL_COL_COUNT_NODE_STOR_POOL
        );

        node = nodeDataFactory.getInstance(SYS_CTX, nodeName, null, null, true, false);
        spdd = storPoolDefinitionDataFactory.getInstance(SYS_CTX, spName, true, false);

        uuid = randomUUID();
    }

    @Test
    public void testPersist() throws Exception
    {
        StorPoolData storPool = new StorPoolData(
            uuid,
            node,
            spdd,
            LvmDriver.class.getSimpleName(),
            false,
            driver,
            propsContainerFactory,
            transObjFactory,
            transMgrProvider,
            new TreeMap<>()
        );
        driver.create(storPool);
        commit();

        PreparedStatement stmt = getConnection().prepareStatement(SELECT_ALL_STOR_POOLS);
        ResultSet resultSet = stmt.executeQuery();
        assertTrue("Database did not persist storPool", resultSet.next());
        assertEquals(uuid, java.util.UUID.fromString(resultSet.getString(UUID)));
        assertEquals(nodeName.value, resultSet.getString(NODE_NAME));
        assertEquals(spName.value, resultSet.getString(POOL_NAME));
        assertEquals(LvmDriver.class.getSimpleName(), resultSet.getString(DRIVER_NAME));
        assertFalse("Database persisted too many storPools", resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testPersistStorGetInstance() throws Exception
    {
        StorPool pool = storPoolDataFactory.getInstance(
            SYS_CTX,
            node,
            spdd,
            LvmDriver.class.getSimpleName(),
            true, // create
            false
        );
        commit();

        // we do not check if node gets created, as testPersistSimple() does that already
        // thus, we only check if the net interface got persisted
        PreparedStatement stmt = getConnection().prepareStatement(SELECT_ALL_STOR_POOLS);
        ResultSet resultSet = stmt.executeQuery();
        assertTrue("Database did not persist storPool", resultSet.next());
        assertEquals(pool.getUuid(), java.util.UUID.fromString(resultSet.getString(UUID)));
        assertEquals(nodeName.value, resultSet.getString(NODE_NAME));
        assertEquals(pool.getName().value, resultSet.getString(POOL_NAME));
        assertEquals(LvmDriver.class.getSimpleName(), resultSet.getString(DRIVER_NAME));
        assertFalse("Database persisted too many storPools", resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testLoadAll() throws Exception
    {
        StorPoolData storPool = new StorPoolData(
            uuid,
            node,
            spdd,
            LvmDriver.class.getSimpleName(),
            false,
            driver,
            propsContainerFactory,
            transObjFactory,
            transMgrProvider,
            new TreeMap<>()
        );
        driver.create(storPool);

        Map<NodeName, Node> tmpNodesMap = new HashMap<>();
        Map<StorPoolName, StorPoolDefinition> tmpStorPoolDfnMap = new HashMap<>();

        tmpNodesMap.put(nodeName, node);

        tmpStorPoolDfnMap = spdDriver.loadAll().keySet().stream().collect(
            Collectors.toMap(
                tmpSpdd -> tmpSpdd.getName(),
                Function.identity()
            )
        );

        Map<StorPoolData, InitMaps> storPools = driver.loadAll(tmpNodesMap, tmpStorPoolDfnMap);

        assertNotNull(storPools);
        assertEquals(2, storPools.size());

        // one of the entries should be the default diskless stor pool, we just skip that
        StorPoolData storPoolData = storPools.keySet().stream()
            .filter(sp -> sp.getName().equals(spName))
            .findFirst()
            .get();

        assertNotNull(storPoolData);
        assertNotNull(storPoolData.getProps(SYS_CTX));
        StorPoolDefinition spDfn = storPoolData.getDefinition(SYS_CTX);
        assertNotNull(spDfn);
        assertEquals(spName, spDfn.getName());
        assertNull(storPoolData.getDriver(SYS_CTX, null, null, null, null));
        assertEquals(LvmDriver.class.getSimpleName(), storPoolData.getDriverName());
        assertEquals(spName, storPoolData.getName());
    }

    @Test
    public void testCache() throws Exception
    {
        StorPoolData storedInstance = storPoolDataFactory.getInstance(
            SYS_CTX,
            node,
            spdd,
            LvmDriver.class.getSimpleName(),
            true,
            false
        );

        // no clearCaches

        assertEquals(storedInstance, storPoolDataFactory.getInstance(
            SYS_CTX,
            node,
            spdd,
            null,
            false,
            false
        ));
    }

    @Test
    public void testLoadGetInstance() throws Exception
    {
        StorPoolData loadedStorPool = storPoolDataFactory.getInstance(
            SYS_CTX,
            node,
            spdd,
            LvmDriver.class.getSimpleName(),
            false,
            false
        );

        assertNull(loadedStorPool);

        StorPoolData storPool = new StorPoolData(
            uuid,
            node,
            spdd,
            LvmDriver.class.getSimpleName(),
            false,
            driver,
            propsContainerFactory,
            transObjFactory,
            transMgrProvider,
            new TreeMap<>()
        );
        driver.create(storPool);
        node.addStorPool(SYS_CTX, storPool);
        spdd.addStorPool(SYS_CTX, storPool);

        loadedStorPool = storPoolDataFactory.getInstance(
            SYS_CTX,
            node,
            spdd,
            LvmDriver.class.getSimpleName(),
            false,
            false
        );

        assertEquals(uuid, loadedStorPool.getUuid());
        assertEquals(spName, loadedStorPool.getDefinition(SYS_CTX).getName());
        assertEquals(spdd, loadedStorPool.getDefinition(SYS_CTX));
        assertNull(loadedStorPool.getDriver(SYS_CTX, null, null, null, null));
        assertEquals(LvmDriver.class.getSimpleName(), loadedStorPool.getDriverName());
        assertEquals(spName, loadedStorPool.getName());
    }

    @Test
    public void testDelete() throws Exception
    {
        StorPoolData storPool = storPoolDataFactory.getInstance(
            SYS_CTX,
            node,
            spdd,
            LvmDriver.class.getSimpleName(),
            true, // create
            false
        );
        commit();

        PreparedStatement stmt = getConnection().prepareStatement(SELECT_ALL_STOR_POOLS);
        ResultSet resultSet = stmt.executeQuery();
        assertTrue("Database did not persist storPool", resultSet.next());

        resultSet.close();

        driver.delete(storPool);
        commit();

        resultSet = stmt.executeQuery();

        assertFalse("Database did not delete storPool", resultSet.next());

        resultSet.close();
        stmt.close();
    }

    @Test
    public void testEnsureExist() throws Exception
    {
        StorPoolData storPool = new StorPoolData(
            uuid,
            node,
            spdd,
            LvmDriver.class.getSimpleName(),
            false,
            driver,
            propsContainerFactory,
            transObjFactory,
            transMgrProvider,
            new TreeMap<>()
        );

        PreparedStatement stmt = getConnection().prepareStatement(SELECT_ALL_STOR_POOLS);
        ResultSet resultSet = stmt.executeQuery();
        assertFalse(resultSet.next());
        resultSet.close();

        driver.ensureEntryExists(storPool);
        commit();

        resultSet = stmt.executeQuery();
        assertTrue(resultSet.next());
        resultSet.close();

        driver.ensureEntryExists(storPool);

        resultSet = stmt.executeQuery();
        assertTrue(resultSet.next());
        resultSet.close();

        stmt.close();
    }

    @Test (expected = LinStorDataAlreadyExistsException.class)
    public void testAlreadyExists() throws Exception
    {
        StorPoolData storPool = new StorPoolData(
            uuid,
            node,
            spdd,
            LvmDriver.class.getSimpleName(),
            false,
            driver,
            propsContainerFactory,
            transObjFactory,
            transMgrProvider,
            new TreeMap<>()
        );
        driver.create(storPool);
        node.addStorPool(SYS_CTX, storPool);
        spdd.addStorPool(SYS_CTX, storPool);

        storPoolDataFactory.getInstance(
            SYS_CTX,
            node,
            spdd,
            LvmDriver.class.getSimpleName(),
            false,
            true
        );
    }
}
