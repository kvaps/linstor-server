package com.linbit.linstor.drbdstate;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.ServiceName;
import com.linbit.SystemService;
import com.linbit.SystemServiceStartException;
import com.linbit.extproc.DaemonHandler;
import com.linbit.extproc.OutputProxy.Event;
import com.linbit.extproc.OutputProxy.ExceptionEvent;
import com.linbit.extproc.OutputProxy.StdErrEvent;
import com.linbit.extproc.OutputProxy.StdOutEvent;
import com.linbit.linstor.core.DrbdStateChange;
import com.linbit.linstor.logging.ErrorReporter;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class DrbdEventService implements SystemService, Runnable, DrbdStateStore
{
    public static final ServiceName SERVICE_NAME;
    public static final String INSTANCE_PREFIX = "DrbdEventService-";
    public static final String SERVICE_INFO = "DrbdEventService";
    private static final AtomicInteger INSTANCE_COUNT = new AtomicInteger(0);
    public static final String DRBDSETUP_COMMAND = "drbdsetup";
    private static final int EVENT_QUEUE_DEFAULT_SIZE = 10_000;

    private ServiceName instanceName;
    private boolean started = false;

    private final BlockingDeque<Event> eventDeque;
    private Thread thread;
    private boolean running;

    private final DrbdEventsMonitor eventsMonitor;
    private boolean needsReinitialize = false;

    private DaemonHandler demonHandler;
    private final ErrorReporter errorReporter;
    private final DrbdStateTracker tracker;

    static
    {
        try
        {
            SERVICE_NAME = new ServiceName("DrbdEventService");
        }
        catch (InvalidNameException invalidNameExc)
        {
            throw new ImplementationError(invalidNameExc);
        }
    }

    @Inject
    public DrbdEventService(
        final ErrorReporter errorReporterRef,
        final DrbdStateTracker trackerRef
    )
    {
        try
        {
            instanceName = new ServiceName(INSTANCE_PREFIX + INSTANCE_COUNT.incrementAndGet());
            eventDeque = new LinkedBlockingDeque<>(EVENT_QUEUE_DEFAULT_SIZE);
            demonHandler = new DaemonHandler(eventDeque, DRBDSETUP_COMMAND, "events2", "all");
            running = false;
            errorReporter = errorReporterRef;
            tracker = trackerRef;
            eventsMonitor = new DrbdEventsMonitor(trackerRef, errorReporterRef);
        }
        catch (InvalidNameException invalidNameExc)
        {
            throw new ImplementationError(invalidNameExc);
        }
    }

    @Override
    public void run()
    {
        while (running)
        {
            Event event;
            try
            {
                event = eventDeque.take();
                if (event instanceof StdOutEvent)
                {
                    eventsMonitor.receiveEvent(new String(((StdOutEvent) event).data));
                }
                else
                if (event instanceof StdErrEvent)
                {
                    errorReporter.logTrace(
                        "DRBD 'events2' returned error: %n%s",
                        new String(((StdErrEvent) event).data)
                    );
                    errorReporter.logTrace("Restarting DRBD 'events2'");
                    demonHandler.stop(true);
                    demonHandler.start();
                }
                else
                if (event instanceof ExceptionEvent)
                {
                    errorReporter.logTrace("ExceptionEvent in DRBD 'events2':");
                    errorReporter.reportError(((ExceptionEvent) event).exc);
                // FIXME: Report the exception to the controller
                }
                else
                if (event instanceof PoisonEvent)
                {
                    break;
                }
            }
            catch (InterruptedException | IOException exc)
            {
                if (running)
                {
                    errorReporter.reportError(new ImplementationError(exc));
                }
            }
            catch (EventsSourceException exc)
            {
                errorReporter.reportError(new ImplementationError(
                    "Unable to process event line from DRBD",
                    exc
                ));
            }
        }
    }

    @Override
    public ServiceName getServiceName()
    {
        return SERVICE_NAME;
    }

    @Override
    public String getServiceInfo()
    {
        return SERVICE_INFO;
    }

    @Override
    public ServiceName getInstanceName()
    {
        return instanceName;
    }

    @Override
    public boolean isStarted()
    {
        return started;
    }

    @Override
    public void setServiceInstanceName(ServiceName instanceNameRef)
    {
        instanceName = instanceNameRef;
    }

    @Override
    public void start()
    {
        if (needsReinitialize)
        {
            eventsMonitor.reinitializing();
        }
        needsReinitialize = true;
        running = true;

        // on shutdown we interrupted the thread AND inserted a poison element
        // the poison element is needed in case the thread was not waiting in the .take()
        // in this case the thread would not receive the interrupt.
        // however, if the thread was in the .take() the poison element will poison the
        // next thread's run method.
        // although this .clear() might also clear other (not yet progressed) events, we
        // will also restart the demonHandler, thus the events2 stream will fill our
        // eventDeque properly.

        eventDeque.clear();

        thread = new Thread(this, "DrbdEventService");
        thread.start();
        try
        {
            demonHandler.start();
        }
        catch (IOException exc)
        {
            errorReporter.reportError(new SystemServiceStartException(
                "Unable to listen for DRBD events",
                "I/O error attempting to start '" + DRBDSETUP_COMMAND + "'",
                exc.getMessage(),
                "Ensure that '" + DRBDSETUP_COMMAND + "' is installed",
                null,
                exc
            ));
        }
        synchronized (this)
        {
            notifyAll();
        }
        started = true;
    }

    @Override
    public void shutdown()
    {
        running = false;
        demonHandler.stop(true);
        thread.interrupt();
        eventDeque.addFirst(new PoisonEvent());
        started = false;
    }

    @Override
    public void awaitShutdown(long timeout) throws InterruptedException
    {
        thread.join(timeout);
    }

    @Override
    public void addDrbdStateChangeObserver(DrbdStateChange obs)
    {
        tracker.addDrbdStateChangeObserver(obs);
    }

    @Override
    public boolean isDrbdStateAvailable()
    {
        return eventsMonitor.isStateAvailable();
    }

    @Override
    public void addObserver(ResourceObserver obs, long eventMask)
    {
        tracker.addObserver(obs, eventMask);
    }

    @Override
    public void removeObserver(ResourceObserver obs)
    {
        tracker.removeObserver(obs);
    }

    @Override
    public DrbdResource getDrbdResource(String name) throws NoInitialStateException
    {
        if (!isDrbdStateAvailable())
        {
            throw new NoInitialStateException("drbdsetup events2 not fully parsed yet");
        }
        return tracker.getResource(name);
    }

    @Override
    public Collection<DrbdResource> getAllDrbdResources() throws NoInitialStateException
    {
        if (!isDrbdStateAvailable())
        {
            throw new NoInitialStateException("drbdsetup events2 not fully parsed yet");
        }
        return tracker.getAllResources();
    }

    private static class PoisonEvent implements Event
    {
    }
}
