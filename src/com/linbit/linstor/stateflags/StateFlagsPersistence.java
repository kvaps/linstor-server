package com.linbit.linstor.stateflags;

import java.sql.SQLException;

/**
 * Updates the state flags of a linstor core object in the database
 * whenever the flags are changed
 *
 * @author Robert Altnoeder &lt;robert.altnoeder@linbit.com&gt;
 */
public interface StateFlagsPersistence<PK>
{
    void persist(PK primaryKey, long flags) throws SQLException;
}
