package com.linbit.linstor.debug;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.multibindings.Multibinder;
import com.linbit.linstor.core.CoreModule;
import com.linbit.linstor.security.ControllerSecurityModule;
import com.linbit.linstor.security.ObjectProtection;

import javax.inject.Named;
import java.util.concurrent.locks.ReadWriteLock;

public class ControllerDebugModule extends AbstractModule
{
    @Override
    protected void configure()
    {
        Multibinder<CommonDebugCmd> commandsBinder =
            Multibinder.newSetBinder(binder(), CommonDebugCmd.class);

        commandsBinder.addBinding().to(CmdDisplayConfValue.class);
        commandsBinder.addBinding().to(CmdSetConfValue.class);
        commandsBinder.addBinding().to(CmdDeleteConfValue.class);
        commandsBinder.addBinding().to(CmdDisplayObjectStatistics.class);
        commandsBinder.addBinding().to(CmdDisplayObjProt.class);
    }

    // Use Provides methods because the ObjectProtection objects are not present on the satellite
    @Provides
    CmdDisplayNodes cmdDisplayNodes(
        @Named(CoreModule.RECONFIGURATION_LOCK) ReadWriteLock reconfigurationLockRef,
        @Named(CoreModule.NODES_MAP_LOCK) ReadWriteLock nodesMapLockRef,
        @Named(ControllerSecurityModule.NODES_MAP_PROT) ObjectProtection nodesMapProtRef,
        CoreModule.NodesMap nodesMapRef
    )
    {
        return new CmdDisplayNodes(reconfigurationLockRef, nodesMapLockRef, nodesMapProtRef, nodesMapRef);
    }

    @Provides
    CmdDisplayResource cmdDisplayResource(
        @Named(CoreModule.RECONFIGURATION_LOCK) ReadWriteLock reconfigurationLockRef,
        @Named(CoreModule.NODES_MAP_LOCK) ReadWriteLock nodesMapLockRef,
        @Named(CoreModule.RSC_DFN_MAP_LOCK) ReadWriteLock rscDfnMapLockRef,
        @Named(ControllerSecurityModule.RSC_DFN_MAP_PROT) ObjectProtection rscDfnMapProtRef,
        CoreModule.ResourceDefinitionMap rscDfnMapRef
    )
    {
        return new CmdDisplayResource(
            reconfigurationLockRef, nodesMapLockRef, rscDfnMapLockRef, rscDfnMapProtRef, rscDfnMapRef);
    }

    @Provides
    CmdDisplayResourceDfn cmdDisplayResourceDfn(
        @Named(CoreModule.RECONFIGURATION_LOCK) ReadWriteLock reconfigurationLockRef,
        @Named(CoreModule.RSC_DFN_MAP_LOCK) ReadWriteLock rscDfnMapLockRef,
        @Named(ControllerSecurityModule.RSC_DFN_MAP_PROT) ObjectProtection rscDfnMapProtRef,
        CoreModule.ResourceDefinitionMap rscDfnMapRef
    )
    {
        return new CmdDisplayResourceDfn(reconfigurationLockRef, rscDfnMapLockRef, rscDfnMapProtRef, rscDfnMapRef);
    }

    @Provides
    CmdDisplayStorPool cmdDisplayStorPool(
        @Named(CoreModule.RECONFIGURATION_LOCK) ReadWriteLock reconfigurationLockRef,
        @Named(CoreModule.STOR_POOL_DFN_MAP_LOCK) ReadWriteLock storPoolDfnMapLockRef,
        @Named(ControllerSecurityModule.STOR_POOL_DFN_MAP_PROT) ObjectProtection storPoolDfnMapProtRef,
        CoreModule.StorPoolDefinitionMap storPoolDfnMapRef
    )
    {
        return new CmdDisplayStorPool(
            reconfigurationLockRef, storPoolDfnMapLockRef, storPoolDfnMapProtRef, storPoolDfnMapRef);
    }

    @Provides
    CmdDisplayStorPoolDfn cmdDisplayStorPoolDfn(
        @Named(CoreModule.RECONFIGURATION_LOCK) ReadWriteLock reconfigurationLockRef,
        @Named(CoreModule.STOR_POOL_DFN_MAP_LOCK) ReadWriteLock storPoolDfnMapLockRef,
        @Named(ControllerSecurityModule.STOR_POOL_DFN_MAP_PROT) ObjectProtection storPoolDfnMapProtRef,
        CoreModule.StorPoolDefinitionMap storPoolDfnMapRef
    )
    {
        return new CmdDisplayStorPoolDfn(
            reconfigurationLockRef, storPoolDfnMapLockRef, storPoolDfnMapProtRef, storPoolDfnMapRef);
    }
}
